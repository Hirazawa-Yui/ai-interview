package com.aiinterview.file.service;

import com.aiinterview.file.dto.ChunkUploadResponse;

/**
 * 分片上传服务接口
 * <p>
 * 协调前端分片请求、OSS MultipartUpload、Redis 状态追踪、数据库去重。
 */
public interface IFileChunkService {

    /**
     * 检查上传状态（秒传/断点续传/新任务）
     *
     * @param md5         文件 MD5
     * @param fileName    原始文件名
     * @param fileSize    文件总大小
     * @param totalChunks 总分片数
     * @return 上传状态
     */
    ChunkUploadResponse check(String md5, String fileName, long fileSize, int totalChunks);

    /**
     * 上传单个分片
     *
     * @param md5        文件 MD5
     * @param chunkIndex 分片序号（0-based）
     * @param chunkMd5   前端传来的分片 MD5
     * @param chunkData  分片二进制数据
     * @param chunkSize  分片大小
     */
    void uploadChunk(String md5, int chunkIndex, String chunkMd5, byte[] chunkData, long chunkSize);

    /**
     * 合并所有分片（前端触发，只调用一次）
     *
     * @param md5      文件 MD5
     * @param fileName 原始文件名
     * @return 合并后的文件访问 URL
     */
    String merge(String md5, String fileName);

    /**
     * 查询分片上传进度
     *
     * @param md5 文件 MD5
     * @return 上传进度（totalChunks + uploadedChunks）
     */
    ChunkUploadResponse preview(String md5);

    /**
     * 按 OSS Key 删除 file_info 里的秒传登记（T25，知识库文档删除时调用）。
     * <p>
     * 不清理会留下死链：OSS 对象已删、登记还在，同一文件再上传时 {@code check()} 会秒传命中一个
     * 已不存在的 URL，随后 {@code /api/knowledge/upload} 下载该对象失败。
     * <p>
     * 维持不变式「file_info 有行 ⇔ 对象存在」。storageKey 为空直接跳过；
     * 删 0 行是正常情况（该文件不是走分片上传链路的），不报错。
     *
     * @param storageKey OSS Key
     */
    void deleteByStorageKey(String storageKey);
}
