package com.aiinterview.file.service;

import java.io.InputStream;
import java.util.Map;

/**
 * 文件存储服务接口（OSS 操作封装）
 * <p>
 * 包含基础的文件 CRUD 和分片上传所需的 MultipartUpload 操作。
 */
public interface IFileStorageService {

    // ========== 基础操作 ==========

    /**
     * 上传单个文件到 OSS
     *
     * @param key         OSS 存储路径
     * @param inputStream 文件输入流
     * @param contentSize 文件大小（字节）
     * @param contentType MIME 类型
     */
    void uploadFile(String key, InputStream inputStream, long contentSize, String contentType);

    /**
     * 下载文件
     *
     * @param key OSS 存储路径
     * @return 文件字节数组
     */
    byte[] downloadFile(String key);

    /**
     * 删除文件
     *
     * @param key OSS 存储路径
     */
    void deleteFile(String key);

    /**
     * 检查文件是否存在
     *
     * @param key OSS 存储路径
     * @return 是否存在
     */
    boolean fileExists(String key);

    /**
     * 获取文件访问 URL
     *
     * @param key OSS 存储路径
     * @return 可访问的 URL
     */
    String getFileUrl(String key);

    // ========== 分片上传（Multipart Upload） ==========

    /**
     * 初始化分片上传会话
     *
     * @param key      最终合并后的文件存储路径
     * @param fileName 原始文件名（用于 Content-Disposition）
     * @return OSS 返回的 uploadId
     */
    String initiateMultipartUpload(String key, String fileName);

    /**
     * 上传单个分片（Part）
     *
     * @param key         最终文件路径（与 initiate 时一致）
     * @param uploadId    上传会话 ID
     * @param partNumber  分片序号（1-based，OSS MultipartUpload 要求）
     * @param inputStream 分片数据流
     * @param partSize    分片大小（字节）
     * @return OSS 返回的 eTag（用于最终合并）
     */
    String uploadPart(String key, String uploadId, int partNumber, InputStream inputStream, long partSize);

    /**
     * 完成分片上传（合并所有 Part）
     *
     * @param key      最终文件路径
     * @param uploadId 上传会话 ID
     * @param partETags 各分片的 eTag 映射（partNumber → eTag）
     */
    void completeMultipartUpload(String key, String uploadId, Map<Integer, String> partETags);

    /**
     * 获取文件大小（字节）
     */
    long getFileSize(String key);
}
