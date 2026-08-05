package com.aiinterview.service.impl;

import com.aiinterview.config.StorageProperties;
import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.service.IFileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件存储服务实现（阿里云 OSS，S3 兼容模式）
 * <p>
 * 基础 CRUD 操作 + 分片上传（MultipartUpload）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements IFileStorageService {

    private final S3Client s3Client;
    private final StorageProperties storageConfig;

    // ============================================================
    // 基础操作
    // ============================================================

    /**
     * 上传单个文件到 OSS
     */
    @Override
    public void uploadFile(String key, InputStream inputStream, long contentSize, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(contentSize)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentSize));
            log.info("文件上传成功: {}", key);
        } catch (S3Exception e) {
            log.error("上传文件到OSS失败: {} - {}", key, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] downloadFile(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(key)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + key);
        } catch (S3Exception e) {
            log.error("下载文件失败: {} - {}", key, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(key)
                    .build();
            s3Client.deleteObject(request);
            log.info("文件删除成功: {}", key);
        } catch (S3Exception e) {
            log.error("删除文件失败: {} - {}", key, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: " + e.getMessage());
        }
    }

    @Override
    public boolean fileExists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("检查文件存在性失败: {} - {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public String getFileUrl(String key) {
        // 阿里云 OSS 虚拟主机风格 URL: https://{bucket}.{endpoint_host}/{key}
        String endpoint = storageConfig.getEndpoint();
        String host = endpoint.replaceFirst("^https?://", "");
        return String.format("https://%s.%s/%s", storageConfig.getBucket(), host, key);
    }

    @Override
    public long getFileSize(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(key)
                    .build();
            return s3Client.headObject(request).contentLength();
        } catch (S3Exception e) {
            log.error("获取文件大小失败: {} - {}", key, e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 分片上传（OSS MultipartUpload）
    // ============================================================

    /**
     * 初始化分片上传
     */
    @Override
    public String initiateMultipartUpload(String key, String fileName) {
        // 注意：不设置 contentDisposition，中文文件名会导致 OSS 签名校验失败
        // 文件下载时的文件名由前端自己控制，不影响存储
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(storageConfig.getBucket())
                .key(key)
                .build();

        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
        log.info("分片上传初始化: key={}, uploadId={}", key, response.uploadId());
        return response.uploadId();
    }

    /**
     * 上传单个分片（Part）
     * <p>
     * OSS MultipartUpload 的 partNumber 从 1 开始。
     * 这里接收 0-based 的 chunkIndex，内部转为 1-based。
     */
    @Override
    public String uploadPart(String key, String uploadId, int partNumber, InputStream inputStream, long partSize) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(storageConfig.getBucket())
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        UploadPartResponse response = s3Client.uploadPart(request, RequestBody.fromInputStream(inputStream, partSize));
        log.debug("分片上传: key={}, uploadId={}, partNumber={}, eTag={}", key, uploadId, partNumber, response.eTag());
        return response.eTag();
    }

    /**
     * 完成分片上传（合并所有 Part）
     */
    @Override
    public void completeMultipartUpload(String key, String uploadId, Map<Integer, String> partETags) {
        // 将 partETags 转为 OSS 需要的 CompletedPart 列表，按 partNumber 排序
        var completedParts = partETags.entrySet().stream()
                .map(entry -> CompletedPart.builder()
                        .partNumber(entry.getKey())
                        .eTag(entry.getValue())
                        .build())
                .sorted((a, b) -> Integer.compare(a.partNumber(), b.partNumber()))
                .collect(Collectors.toList());

        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(storageConfig.getBucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();

        s3Client.completeMultipartUpload(request);
        log.info("分片合并完成: key={}, uploadId={}, totalParts={}", key, uploadId, completedParts.size());
    }
}
