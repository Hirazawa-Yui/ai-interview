package com.aiinterview.service.impl;

import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.service.IKbVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbVectorServiceImpl implements IKbVectorService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public int vectorize(Long kbId, String text) {
        // 1. 文本切分（800字符/块，100字符重叠）
        // Spring AI 2.0: TokenTextSplitter 默认按段落+句子边界切分
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .build();
        List<Document> chunks = splitter.split(List.of(new Document(text)));

        // 2. 删除旧向量（重新向量化场景）
        deleteByKbId(kbId);

        // 3. 添加 metadata（kb_id）并批量存储
        for (Document chunk : chunks) {
            chunk.getMetadata().put("kb_id", kbId.toString());
        }

        // 4. 批量嵌入（每批 ≤ 10）
        int total = chunks.size();
        for (int i = 0; i < total; i += 10) {
            int end = Math.min(i + 10, total);
            vectorStore.add(chunks.subList(i, end));
        }

        log.info("向量化完成: kbId={}, chunks={}", kbId, total);
        return total;
    }

    @Override
    public List<Document> similaritySearch(String query, List<Long> kbIds, int topK, double minScore) {
        // 构建 kb_id IN ('1','2') 过滤条件
        String filter = kbIds.stream()
                .map(id -> "'" + id + "'")
                .reduce((a, b) -> a + "," + b)
                .map(ids -> "kb_id in [" + ids + "]")
                .orElse("");

        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(minScore)
                            .filterExpression(filter)
                            .build()
            );
        } catch (Exception e) {
            // Fallback: 无过滤条件的检索，再本地过滤
            log.warn("过滤检索失败，使用Fallback: {}", e.getMessage());
            List<Document> all = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK * 3).build()
            );
            return all.stream()
                    .filter(d -> kbIds.contains(parseKbId(d.getMetadata().get("kb_id"))))
                    .limit(topK)
                    .toList();
        }
    }

    @Override
    public void deleteByKbId(Long kbId) {
        try {
            String sql = "DELETE FROM vector_store WHERE metadata->>'kb_id' = ?";
            jdbcTemplate.update(sql, kbId.toString());
        } catch (Exception e) {
            log.warn("删除向量失败: kbId={}", kbId, e);
        }
    }

    private Long parseKbId(Object metaValue) {
        if (metaValue == null) return null;
        try {
            return Long.valueOf(metaValue.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
