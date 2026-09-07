package com.aiinterview.interview.listener;

import com.aiinterview.common.StreamKeys;
import com.aiinterview.interview.service.IInterviewEvaluationService;
import com.aiinterview.stream.AbstractStreamConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试评估任务消费者（单线程守护）
 * <p>
 * 支持三种消息模式（按 body 字段区分）：
 * 1. 增量批次（有 batchNumber/qStart/qEnd）→ 评估指定题目范围，结果存 Redis
 * 2. 汇总（type=summarize）→ 收集增量批次结果 + 1次 LLM 汇总 → 入库（EVALUATING → EVALUATED）
 * 3. 全量评估（无 batchNumber、无 type）→ 全量分批评估 + 汇总（兼容旧消息 / 降级兜底）
 */
@Slf4j
@Component
public class InterviewEvaluationConsumer extends AbstractStreamConsumer<InterviewEvaluationConsumer.EvaluatePayload> {

    private final IInterviewEvaluationService evaluationService;

    public InterviewEvaluationConsumer(StringRedisTemplate redisTemplate,
                                        IInterviewEvaluationService evaluationService) {
        super(redisTemplate);
        this.evaluationService = evaluationService;
    }

    // ============================================================
    // 抽象方法实现
    // ============================================================

    @Override protected String taskDisplayName() { return "面试评估"; }
    @Override protected String streamKey() { return StreamKeys.INTERVIEW_EVALUATE_STREAM; }
    @Override protected String groupName() { return StreamKeys.INTERVIEW_EVALUATE_GROUP; }
    @Override protected String consumerPrefix() { return StreamKeys.INTERVIEW_EVALUATE_CONSUMER_PREFIX; }
    @Override protected String threadName() { return "interview-evaluate-consumer"; }

    @Override
    protected EvaluatePayload parsePayload(RecordId messageId, Map<String, String> data) {
        try {
            Long sessionId = Long.valueOf(data.get("sessionId"));
            if (sessionId == null) return null;

            // 汇总消息：type=summarize
            if ("summarize".equals(data.get("type"))) {
                return new EvaluatePayload(sessionId, null, null, null, TaskType.SUMMARIZE);
            }
            // 增量批次消息
            if (data.containsKey("batchNumber")) {
                Integer batchNumber = Integer.valueOf(data.get("batchNumber"));
                Integer qStart = Integer.valueOf(data.get("qStart"));
                Integer qEnd = Integer.valueOf(data.get("qEnd"));
                return new EvaluatePayload(sessionId, batchNumber, qStart, qEnd, TaskType.BATCH);
            }
            // 全量评估（旧消息/降级兜底）
            return new EvaluatePayload(sessionId, null, null, null, TaskType.FULL);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        if (payload.type() == TaskType.BATCH) {
            return "sessionId=" + payload.sessionId() + ", batch=" + payload.batchNumber();
        }
        return "sessionId=" + payload.sessionId() + ", type=" + payload.type().name().toLowerCase();
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) { /* 无需操作 */ }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        switch (payload.type()) {
            case BATCH -> evaluationService.evaluateBatchRange(
                    payload.sessionId(), payload.batchNumber(), payload.qStart(), payload.qEnd());
            case SUMMARIZE -> evaluationService.summarizeAndPersist(payload.sessionId());
            case FULL -> evaluationService.evaluate(payload.sessionId());
        }
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) { /* 无需操作（状态由业务方法内更新） */ }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        // BATCH 失败由模板重试，最终由汇总/全量兜底；FULL/SUMMARIZE 失败在业务方法内已置 FAILED
    }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sessionId", String.valueOf(payload.sessionId()));
        if (payload.type() == TaskType.BATCH) {
            body.put("batchNumber", String.valueOf(payload.batchNumber()));
            body.put("qStart", String.valueOf(payload.qStart()));
            body.put("qEnd", String.valueOf(payload.qEnd()));
        } else if (payload.type() == TaskType.SUMMARIZE) {
            body.put("type", "summarize");
        }
        body.put(StreamKeys.FIELD_RETRY_COUNT, String.valueOf(retryCount));

        var record = StreamRecords.mapBacked(body).withStreamKey(streamKey());
        redisTemplate.opsForStream().add(record);
    }

    /** 消息模式：BATCH=增量批次评估 / SUMMARIZE=汇总入库 / FULL=全量评估 */
    public enum TaskType { BATCH, SUMMARIZE, FULL }

    /** payload：sessionId + 增量批次参数（BATCH 时非空）+ 消息模式 */
    public record EvaluatePayload(Long sessionId, Integer batchNumber, Integer qStart, Integer qEnd, TaskType type) {}
}
