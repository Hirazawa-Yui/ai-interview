package com.aiinterview.knowledge.listener;

import com.aiinterview.common.StreamKeys;
import com.aiinterview.knowledge.entity.KnowledgeBase;
import com.aiinterview.knowledge.mapper.KnowledgeBaseMapper;
import com.aiinterview.knowledge.service.IKbVectorService;
import com.aiinterview.stream.AbstractStreamConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class VectorizeConsumer extends AbstractStreamConsumer<VectorizeConsumer.VectorizePayload> {

    private final KnowledgeBaseMapper kbMapper;
    private final IKbVectorService vectorService;

    public VectorizeConsumer(StringRedisTemplate redisTemplate, KnowledgeBaseMapper kbMapper, IKbVectorService vectorService) {
        super(redisTemplate);
        this.kbMapper = kbMapper;
        this.vectorService = vectorService;
    }

    @Override protected String taskDisplayName() { return "知识库向量化"; }
    @Override protected String streamKey() { return StreamKeys.KB_VECTORIZE_STREAM; }
    @Override protected String groupName() { return StreamKeys.KB_VECTORIZE_GROUP; }
    @Override protected String consumerPrefix() { return StreamKeys.KB_VECTORIZE_CONSUMER_PREFIX; }
    @Override protected String threadName() { return "kb-vectorize-consumer"; }

    @Override
    protected VectorizePayload parsePayload(RecordId id, Map<String, String> data) {
        try {
            Long kbId = Long.valueOf(data.get("kbId"));
            String content = data.get(StreamKeys.FIELD_CONTENT);
            if (kbId == null || content == null) return null;
            return new VectorizePayload(kbId, content);
        } catch (Exception e) { return null; }
    }
    @Override protected String payloadIdentifier(VectorizePayload p) { return "kbId=" + p.kbId(); }
    @Override protected void markProcessing(VectorizePayload p) { updateStatus(p.kbId(), "PROCESSING", null); }
    @Override protected void markCompleted(VectorizePayload p) { updateStatus(p.kbId(), "COMPLETED", null); }
    @Override protected void markFailed(VectorizePayload p, String error) { updateStatus(p.kbId(), "FAILED", error); }

    @Override
    protected void processBusiness(VectorizePayload p) {
        KnowledgeBase kb = kbMapper.selectById(p.kbId());
        if (kb == null) return;
        int chunks = vectorService.vectorize(p.kbId(), p.content());
        kb = kbMapper.selectById(p.kbId());
        if (kb != null) { kb.setChunkCount(chunks); kbMapper.updateById(kb); }
    }

    @Override
    protected void retryMessage(VectorizePayload p, int retryCount) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("kbId", String.valueOf(p.kbId()));
        body.put(StreamKeys.FIELD_CONTENT, p.content());
        body.put(StreamKeys.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        redisTemplate.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(streamKey()));
    }

    private void updateStatus(Long kbId, String status, String error) {
        try {
            KnowledgeBase kb = kbMapper.selectById(kbId);
            if (kb != null) { kb.setVectorStatus(status); if (error != null) kb.setVectorError(error); kbMapper.updateById(kb); }
        } catch (Exception e) { log.warn("更新状态失败: kbId={}", kbId); }
    }

    public record VectorizePayload(Long kbId, String content) {}
}
