package com.aiinterview.stream.listener;

import com.aiinterview.constant.StreamKeys;
import com.aiinterview.entity.InterviewSession;
import com.aiinterview.mapper.InterviewSessionMapper;
import com.aiinterview.stream.AbstractStreamProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试评估任务生产者
 */
@Slf4j
@Component
public class InterviewEvaluationProducer extends AbstractStreamProducer<InterviewEvaluationProducer.EvaluateTaskPayload> {

    private final InterviewSessionMapper sessionMapper;

    public InterviewEvaluationProducer(StringRedisTemplate redisTemplate,
                                        InterviewSessionMapper sessionMapper) {
        super(redisTemplate);
        this.sessionMapper = sessionMapper;
    }

    /** 发送评估任务 */
    public void sendEvaluateTask(Long sessionId) {
        sendTask(new EvaluateTaskPayload(sessionId));
    }

    @Override
    protected String taskDisplayName() { return "面试评估"; }

    @Override
    protected String streamKey() { return StreamKeys.INTERVIEW_EVALUATE_STREAM; }

    @Override
    protected Map<String, String> buildMessage(EvaluateTaskPayload payload) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("sessionId", String.valueOf(payload.sessionId()));
        map.put(StreamKeys.FIELD_RETRY_COUNT, "0");
        return map;
    }

    @Override
    protected String payloadIdentifier(EvaluateTaskPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void onSendFailed(EvaluateTaskPayload payload, String error) {
        InterviewSession session = sessionMapper.selectById(payload.sessionId());
        if (session != null) {
            session.setStatus("FAILED");
            session.setEvaluateError(truncateError(error));
            sessionMapper.updateById(session);
        }
    }

    public record EvaluateTaskPayload(Long sessionId) {}
}
