package com.aiinterview.knowledge.listener;

import com.aiinterview.common.StreamKeys;
import com.aiinterview.knowledge.entity.KnowledgeBase;
import com.aiinterview.knowledge.mapper.KnowledgeBaseMapper;
import com.aiinterview.stream.AbstractStreamProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class VectorizeProducer extends AbstractStreamProducer<VectorizeProducer.VectorizePayload> {

    private final KnowledgeBaseMapper kbMapper;

    public VectorizeProducer(StringRedisTemplate redisTemplate, KnowledgeBaseMapper kbMapper) {
        super(redisTemplate);
        this.kbMapper = kbMapper;
    }

    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizePayload(kbId, content));
    }

    @Override protected String taskDisplayName() { return "知识库向量化"; }
    @Override protected String streamKey() { return StreamKeys.KB_VECTORIZE_STREAM; }

    @Override
    protected Map<String, String> buildMessage(VectorizePayload p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kbId", String.valueOf(p.kbId()));
        m.put(StreamKeys.FIELD_CONTENT, p.content());
        m.put(StreamKeys.FIELD_RETRY_COUNT, "0");
        return m;
    }

    @Override protected String payloadIdentifier(VectorizePayload p) { return "kbId=" + p.kbId(); }

    @Override
    protected void onSendFailed(VectorizePayload p, String error) {
        KnowledgeBase kb = kbMapper.selectById(p.kbId());
        if (kb != null) { kb.setVectorStatus("FAILED"); kb.setVectorError(truncateError(error)); kbMapper.updateById(kb); }
    }

    public record VectorizePayload(Long kbId, String content) {}
}
