package com.aiinterview.resume.listener;

import com.aiinterview.common.StreamKeys;
import com.aiinterview.resume.dto.ResumeAnalysisResponse;
import com.aiinterview.resume.entity.Resume;
import com.aiinterview.resume.entity.ResumeAnalysis;
import com.aiinterview.resume.mapper.ResumeAnalysisMapper;
import com.aiinterview.resume.mapper.ResumeMapper;
import com.aiinterview.resume.service.IResumeAnalysisService;
import com.aiinterview.stream.AbstractStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历分析任务消费者（单线程守护）
 */
@Slf4j
@Component
public class ResumeAnalysisConsumer extends AbstractStreamConsumer<ResumeAnalysisConsumer.AnalyzePayload> {

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final IResumeAnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public ResumeAnalysisConsumer(StringRedisTemplate redisTemplate,
                                   ResumeMapper resumeMapper,
                                   ResumeAnalysisMapper analysisMapper,
                                   IResumeAnalysisService analysisService,
                                   ObjectMapper objectMapper) {
        super(redisTemplate);
        this.resumeMapper = resumeMapper;
        this.analysisMapper = analysisMapper;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // 抽象方法实现
    // ============================================================

    @Override protected String taskDisplayName() { return "简历分析"; }
    @Override protected String streamKey() { return StreamKeys.RESUME_ANALYZE_STREAM; }
    @Override protected String groupName() { return StreamKeys.RESUME_ANALYZE_GROUP; }
    @Override protected String consumerPrefix() { return StreamKeys.RESUME_ANALYZE_CONSUMER_PREFIX; }
    @Override protected String threadName() { return "resume-analyze-consumer"; }

    @Override
    protected AnalyzePayload parsePayload(RecordId messageId, Map<String, String> data) {
        try {
            Long resumeId = Long.valueOf(data.get("resumeId"));
            String content = data.get(StreamKeys.FIELD_CONTENT);
            if (resumeId == null || content == null) return null;
            return new AnalyzePayload(resumeId, content);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateStatus(payload.resumeId(), "PROCESSING", null);
    }

    @Override
    protected void processBusiness(AnalyzePayload payload) {
        // 1. 检查简历是否存在
        Resume resume = resumeMapper.selectById(payload.resumeId());
        if (resume == null) return;

        // 2. 调用 AI 分析
        ResumeAnalysisResponse aiResult = analysisService.analyze(payload.content());

        // 3. 再查一次（分析可能耗时较长，简历可能已被删除）
        resume = resumeMapper.selectById(payload.resumeId());
        if (resume == null) return;

        // 4. 保存分析结果
        try {
            ResumeAnalysis analysis = ResumeAnalysis.builder()
                    .resumeId(payload.resumeId())
                    .overallScore(aiResult.getOverallScore())
                    .summary(aiResult.getSummary())
                    .strengthsJson(objectMapper.writeValueAsString(aiResult.getStrengths()))
                    .suggestionsJson(objectMapper.writeValueAsString(aiResult.getSuggestions()))
                    .aiRawResponse("")  // 简化：不存原始返回
                    .analyzedAt(LocalDateTime.now())
                    .build();
            analysisMapper.insert(analysis);
        } catch (Exception e) {
            log.error("保存分析结果失败: resumeId={}", payload.resumeId(), e);
            throw new RuntimeException("保存分析结果失败", e);
        }
    }

    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateStatus(payload.resumeId(), "COMPLETED", null);
    }

    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateStatus(payload.resumeId(), "FAILED", error);
    }

    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        // 重新 XADD 到同一个 Stream
        Map<String, String> body = new LinkedHashMap<>();
        body.put("resumeId", String.valueOf(payload.resumeId()));
        body.put(StreamKeys.FIELD_CONTENT, payload.content());
        body.put(StreamKeys.FIELD_RETRY_COUNT, String.valueOf(retryCount));

        var record = StreamRecords.mapBacked(body).withStreamKey(streamKey());
        redisTemplate.opsForStream().add(record);
    }

    // ============================================================
    // 私有方法，持久化
    // ============================================================

    private void updateStatus(Long resumeId, String status, String error) {
        try {
            Resume resume = resumeMapper.selectById(resumeId);
            if (resume != null) {
                resume.setAnalyzeStatus(status);
                if (error != null) resume.setAnalyzeError(error);
                resumeMapper.updateById(resume);
            }
        } catch (Exception e) {
            log.warn("更新状态失败: resumeId={}, status={}", resumeId, status);
        }
    }

    public record AnalyzePayload(Long resumeId, String content) {}
}
