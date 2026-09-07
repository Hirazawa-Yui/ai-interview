package com.aiinterview.stream.listener;

import com.aiinterview.constant.StreamKeys;
import com.aiinterview.service.IInterviewEvaluationService;
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
 * 支持两种消息模式：
 * 1. 增量批次（有 batchNumber/qStart/qEnd）→ 评估指定题目范围，结果存 Redis
 * 2. 全量评估（无 batchNumber）→ 全量分批评估 + 汇总（兼容旧逻辑/降级）
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

            Integer batchNumber = null;
            Integer qStart = null;
            Integer qEnd = null;
            if (data.containsKey("batchNumber")) {
                batchNumber = Integer.valueOf(data.get("batchNumber"));
                qStart = Integer.valueOf(data.get("qStart"));
                qEnd = Integer.valueOf(data.get("qEnd"));
            }
            return new EvaluatePayload(sessionId, batchNumber, qStart, qEnd);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        if (payload.batchNumber() != null) {
            return "sessionId=" + payload.sessionId() + ", batch=" + payload.batchNumber();
        }
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) { /* 无需操作 */ }

    @Override
    protected void processBusiness(EvaluatePayload payload) {
        if (payload.batchNumber() != null) {
            // 增量批次模式：评估指定题目范围
            evaluationService.evaluateBatchRange(
                    payload.sessionId(), payload.batchNumber(),
                    payload.qStart(), payload.qEnd());
        } else {
            // 全量模式（降级/兼容旧逻辑）
            evaluationService.evaluate(payload.sessionId());
        }
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) { /* 无需操作 */ }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) { /* 无需操作 */ }

    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sessionId", String.valueOf(payload.sessionId()));
        if (payload.batchNumber() != null) {
            body.put("batchNumber", String.valueOf(payload.batchNumber()));
            body.put("qStart", String.valueOf(payload.qStart()));
            body.put("qEnd", String.valueOf(payload.qEnd()));
        }
        body.put(StreamKeys.FIELD_RETRY_COUNT, String.valueOf(retryCount));

        var record = StreamRecords.mapBacked(body).withStreamKey(streamKey());
        redisTemplate.opsForStream().add(record);
    }

    /** payload：sessionId + 可选的增量批次参数 */
    public record EvaluatePayload(Long sessionId, Integer batchNumber, Integer qStart, Integer qEnd) {}
}
