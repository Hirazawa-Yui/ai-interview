package com.aiinterview.file.dto;

import java.time.Instant;

/**
 * OSS 未完成的分片上传信息（供孤儿分片清理任务使用）
 *
 * @param fileKey     OSS 存储路径
 * @param uploadId    上传会话 ID
 * @param initiatedAt 发起时间（OSS 服务器时间）
 */
public record MultipartUploadInfo(String fileKey, String uploadId, Instant initiatedAt) {
}
