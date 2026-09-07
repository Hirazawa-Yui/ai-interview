package com.aiinterview.resume.listener;

import com.aiinterview.common.StreamKeys;
import com.aiinterview.resume.entity.Resume;
import com.aiinterview.resume.mapper.ResumeMapper;
import com.aiinterview.stream.AbstractStreamProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历分析任务生产者
 */
@Slf4j
@Component
public class ResumeAnalysisProducer extends AbstractStreamProducer<ResumeAnalysisProducer.AnalyzeTaskPayload> {

    private final ResumeMapper resumeMapper;

    public ResumeAnalysisProducer(StringRedisTemplate redisTemplate, ResumeMapper resumeMapper) {
        super(redisTemplate);
        this.resumeMapper = resumeMapper;
    }

    public void sendAnalyzeTask(Long resumeId, String resumeText) {
        sendTask(new AnalyzeTaskPayload(resumeId, resumeText));
    }

    @Override
    protected String taskDisplayName() { return "简历分析"; }

    @Override
    protected String streamKey() { return StreamKeys.RESUME_ANALYZE_STREAM; }

    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("resumeId", String.valueOf(payload.resumeId()));
        map.put(StreamKeys.FIELD_CONTENT, payload.content());
        map.put(StreamKeys.FIELD_RETRY_COUNT, "0");
        return map;
    }

    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        Resume resume = resumeMapper.selectById(payload.resumeId());
        if (resume != null) {
            resume.setAnalyzeStatus("FAILED");
            resume.setAnalyzeError(truncateError(error));
            resumeMapper.updateById(resume);
        }
    }

    public record AnalyzeTaskPayload(Long resumeId, String content) {}
}
