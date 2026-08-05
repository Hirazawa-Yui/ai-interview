package com.aiinterview.service;

import org.springframework.ai.document.Document;
import java.util.List;

/**
 * 知识库向量化服务
 */
public interface IKbVectorService {
    /**
     * 对文本进行切分、嵌入、存入 pgvector
     * @return 切分块数
     */
    int vectorize(Long kbId, String text);

    /**
     * 相似度检索
     * @param query 查询文本
     * @param kbIds 知识库ID列表
     * @param topK 返回条数
     * @param minScore 最低相似度阈值
     */
    List<Document> similaritySearch(String query, List<Long> kbIds, int topK, double minScore);

    /** 删除知识库的所有向量 */
    void deleteByKbId(Long kbId);
}
