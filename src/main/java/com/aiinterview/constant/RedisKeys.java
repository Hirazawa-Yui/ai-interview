package com.aiinterview.constant;

/**
 * Redis Key 常量
 * <p>
 * 统一管理所有 Redis Key 前缀，避免硬编码字符串。
 * 参照 flash-sale-platform 中 RedisConstants 的风格。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 文件上传 — 已上传分片序号集合（Set） */
    public static final String FILE_CHUNKS = "file:chunks:%s";

    /** 文件上传 — 上传会话信息（Hash: uploadId, totalChunks, fileName, fileSize） */
    public static final String FILE_UPLOAD = "file:upload:%s";

    /** 文件上传 — 各分片的 eTag（Hash: partNumber → eTag） */
    public static final String FILE_ETAGS = "file:etags:%s";

    /** 文件上传 — 合并锁（String, SETNX） */
    public static final String FILE_MERGE_LOCK = "file:merge:%s";

    /** 文件上传状态 TTL：24小时 */
    public static final long FILE_UPLOAD_TTL = 86400L;

    // ========== 面试缓存 ==========

    /** 面试题目缓存（String, JSON Array），TTL 2小时 */
    public static final String INTERVIEW_QUESTIONS = "interview:questions:%d";

    /** 面试增量评估 — 批次结果缓存（String, JSON），TTL 2小时 */
    public static final String INTERVIEW_BATCHEVAL = "interview:batcheval:%d:%d";

    /** 面试增量评估 — 已完成批次号集合（Set），TTL 2小时 */
    public static final String INTERVIEW_BATCHEVAL_DONE = "interview:batcheval:%d:batches:done";

    /** 面试增量评估 — 批次并发锁（String, SETNX），TTL 30s */
    public static final String INTERVIEW_BATCHEVAL_LOCK = "interview:batcheval:%d:lock";

    /** 面试题目缓存 TTL：2小时 */
    public static final long INTERVIEW_QUESTIONS_TTL = 7200L;

    /** 面试增量评估 — TTL */
    public static final long INTERVIEW_BATCHEVAL_TTL = 7200L;

    // ========== 工具方法 ==========

    /** 格式化 Key：file:chunks:{md5} */
    public static String fileChunks(String md5) {
        return String.format(FILE_CHUNKS, md5);
    }

    /** 格式化 Key：file:upload:{md5} */
    public static String fileUpload(String md5) {
        return String.format(FILE_UPLOAD, md5);
    }

    /** 格式化 Key：file:etags:{md5} */
    public static String fileEtags(String md5) {
        return String.format(FILE_ETAGS, md5);
    }

    /** 格式化 Key：file:merge:{md5} */
    public static String fileMergeLock(String md5) {
        return String.format(FILE_MERGE_LOCK, md5);
    }

    /** 格式化 Key：interview:questions:{sessionId} */
    public static String interviewQuestions(Long sessionId) {
        return String.format(INTERVIEW_QUESTIONS, sessionId);
    }

    /** 格式化 Key：interview:batcheval:{sessionId}:{batchNumber} */
    public static String interviewBatchEval(Long sessionId, int batchNumber) {
        return String.format(INTERVIEW_BATCHEVAL, sessionId, batchNumber);
    }

    /** 格式化 Key：interview:batcheval:{sessionId}:batches:done */
    public static String interviewBatchDone(Long sessionId) {
        return String.format(INTERVIEW_BATCHEVAL_DONE, sessionId);
    }

    /** 格式化 Key：interview:batcheval:{sessionId}:lock */
    public static String interviewBatchLock(Long sessionId) {
        return String.format(INTERVIEW_BATCHEVAL_LOCK, sessionId);
    }
}
