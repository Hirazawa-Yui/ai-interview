package com.aiinterview.common;

/**
 * Redis Stream 常量
 */
public final class StreamKeys {

    private StreamKeys() {
    }

    // ========== 简历分析 ==========
    public static final String RESUME_ANALYZE_STREAM = "resume:analyze:stream";
    public static final String RESUME_ANALYZE_GROUP = "analyze-group";
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";

    // ========== 知识库向量化 ==========
    public static final String KB_VECTORIZE_STREAM = "knowledgebase:vectorize:stream";
    public static final String KB_VECTORIZE_GROUP = "vectorize-group";
    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";

    // ========== 面试评估（Phase 6b） ==========
    public static final String INTERVIEW_EVALUATE_STREAM = "interview:evaluate:stream";
    public static final String INTERVIEW_EVALUATE_GROUP = "evaluate-group";
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";

    // ========== 公共配置 ==========
    public static final int MAX_RETRY_COUNT = 3;
    public static final int BATCH_SIZE = 10;
    public static final int POLL_INTERVAL_MS = 1000;
    public static final int STREAM_MAX_LEN = 1000;

    // ========== 消息字段 ==========
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_CONTENT = "content";
}
