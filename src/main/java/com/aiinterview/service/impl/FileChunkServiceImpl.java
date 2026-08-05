package com.aiinterview.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aiinterview.constant.RedisKeys;
import com.aiinterview.dto.ChunkUploadResponse;
import com.aiinterview.entity.FileInfo;
import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.mapper.FileInfoMapper;
import com.aiinterview.service.IFileChunkService;
import com.aiinterview.service.IFileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分片上传服务实现
 * <p>
 * 核心流程：
 * 1. 前端计算文件 MD5 → POST /check（秒传/断点续传/新任务）
 * 2. 前端并发上传分片 → POST /chunk（先落盘 OSS，后记账 Redis）
 * 3. 前端所有分片传完后调用 → POST /merge（SETNX 锁 + 幂等保护 + OSS CompleteMultipartUpload）
 */
@Slf4j
@Service
public class FileChunkServiceImpl implements IFileChunkService {

    private final IFileStorageService storageService;
    private final FileInfoMapper fileInfoMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public FileChunkServiceImpl(IFileStorageService storageService,
                                 FileInfoMapper fileInfoMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 RedissonClient redissonClient) {
        this.storageService = storageService;
        this.fileInfoMapper = fileInfoMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    // ============================================================
    // check — 查询上传状态（秒传 / 断点续传 / 新任务）
    // ============================================================

    @Override
    public ChunkUploadResponse check(String md5, String fileName, long fileSize, int totalChunks) {
        // 1. 查数据库 — 秒传判断
        FileInfo existing = fileInfoMapper.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileMd5, md5)
        );
        if (existing != null) {
            log.info("秒传命中: md5={}, url={}", md5, existing.getStorageUrl());
            return ChunkUploadResponse.builder()
                    .completed(true)
                    .url(existing.getStorageUrl())
                    .build();
        }

        // 2. 查 Redis — 断点续传判断
        String uploadKey = RedisKeys.fileUpload(md5);
        Map<Object, Object> uploadInfo = stringRedisTemplate.opsForHash().entries(uploadKey);

        if (!uploadInfo.isEmpty()) {
            // 已有上传会话，返回已上传的分片列表
            String existingUploadId = (String) uploadInfo.get("uploadId");
            Set<String> uploadedChunkSet = stringRedisTemplate.opsForSet()
                    .members(RedisKeys.fileChunks(md5));

            Set<Integer> uploadedChunks = Collections.emptySet();
            if (uploadedChunkSet != null && !uploadedChunkSet.isEmpty()) {
                uploadedChunks = uploadedChunkSet.stream()
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet());
            }

            log.info("断点续传: md5={}, uploadId={}, uploaded={}/{}",
                    md5, existingUploadId, uploadedChunks.size(), totalChunks);
            return ChunkUploadResponse.builder()
                    .completed(false)
                    .uploadId(existingUploadId)
                    .totalChunks(totalChunks)
                    .uploadedChunks(uploadedChunks)
                    .build();
        }

        // 3. 新任务 — 初始化 OSS MultipartUpload
        // 用 MD5 + 扩展名作为 Key，避免中文文件名导致 OSS 签名错误
        String fileKey = generateFileKey(md5, fileName);
        String uploadId = storageService.initiateMultipartUpload(fileKey, fileName);

        // 存储上传会话信息到 Redis
        Map<String, String> info = new HashMap<>();
        info.put("uploadId", uploadId);
        info.put("totalChunks", String.valueOf(totalChunks));
        info.put("fileName", fileName);
        info.put("fileSize", String.valueOf(fileSize));
        info.put("fileKey", fileKey);
        stringRedisTemplate.opsForHash().putAll(uploadKey, info);
        // 设置 TTL：24小时
        stringRedisTemplate.expire(uploadKey, RedisKeys.FILE_UPLOAD_TTL, TimeUnit.SECONDS);

        log.info("新任务初始化: md5={}, uploadId={}, totalChunks={}, fileKey={}",
                md5, uploadId, totalChunks, fileKey);
        return ChunkUploadResponse.builder()
                .completed(false)
                .uploadId(uploadId)
                .totalChunks(totalChunks)
                .uploadedChunks(Collections.emptySet())
                .build();
    }

    // ============================================================
    // uploadChunk — 上传单个分片
    // ============================================================

    @Override
    public void uploadChunk(String md5, int chunkIndex, String chunkMd5, byte[] chunkData, long chunkSize) {
        // 1. 获取上传会话信息
        String uploadKey = RedisKeys.fileUpload(md5);
        Map<Object, Object> uploadInfo = stringRedisTemplate.opsForHash().entries(uploadKey);
        if (uploadInfo.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传会话不存在或已过期，请重新发起上传");
        }

        String uploadId = (String) uploadInfo.get("uploadId");
        String fileKey = (String) uploadInfo.get("fileKey");

        // 2. 校验分片 MD5（先验后传，避免把损坏的数据存到 OSS）
        String serverChunkMd5 = DigestUtil.md5Hex(chunkData);
        if (StrUtil.isNotBlank(chunkMd5) && !serverChunkMd5.equalsIgnoreCase(chunkMd5)) {
            log.warn("分片MD5校验失败: md5={}, chunkIndex={}, clientMd5={}, serverMd5={}",
                    md5, chunkIndex, chunkMd5, serverChunkMd5);
            throw new BusinessException(ErrorCode.FILE_MD5_MISMATCH,
                    "分片 " + chunkIndex + " 数据损坏（MD5不一致），请重传");
        }

        // 3. 先落盘 — 上传到 OSS（OSS MultipartUpload partNumber 从 1 开始）
        int partNumber = chunkIndex + 1;
        String eTag = storageService.uploadPart(fileKey, uploadId, partNumber,
                new ByteArrayInputStream(chunkData), chunkSize);

        // 4. 后记账 — OSS 写入成功后，才在 Redis 中标记此分片完成
        String chunksKey = RedisKeys.fileChunks(md5);
        String etagsKey = RedisKeys.fileEtags(md5);

        stringRedisTemplate.opsForSet().add(chunksKey, String.valueOf(chunkIndex));
        stringRedisTemplate.opsForHash().put(etagsKey, String.valueOf(partNumber), eTag);

        // 续期 TTL
        stringRedisTemplate.expire(chunksKey, RedisKeys.FILE_UPLOAD_TTL, TimeUnit.SECONDS);
        stringRedisTemplate.expire(etagsKey, RedisKeys.FILE_UPLOAD_TTL, TimeUnit.SECONDS);
        stringRedisTemplate.expire(uploadKey, RedisKeys.FILE_UPLOAD_TTL, TimeUnit.SECONDS);

        log.debug("分片上传完成: md5={}, chunkIndex={}, partNumber={}, eTag={}", md5, chunkIndex, partNumber, eTag);
    }

    // ============================================================
    // merge — 合并所有分片
    // ============================================================

    @Override
    public String merge(String md5, String fileName) {
        // 1. Redisson 分布式锁（看门狗自动续期，不会合并到一半锁过期）
        RLock lock = redissonClient.getLock(RedisKeys.fileMergeLock(md5));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "合并被中断");
        }

        if (!locked) {
            // 没抢到锁 — 可能另一个请求正在合并，去数据库查结果
            FileInfo existing = fileInfoMapper.selectOne(
                    new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileMd5, md5)
            );
            if (existing != null) {
                log.info("合并已完成（等他请求）: md5={}, url={}", md5, existing.getStorageUrl());
                return existing.getStorageUrl();
            }
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件正在合并中，请稍后查询");
        }

        try {
            // 2. 数据库二次校验 — 幂等保护
            FileInfo existing = fileInfoMapper.selectOne(
                    new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileMd5, md5)
            );
            if (existing != null) {
                log.info("合并已完成（DB幂等）: md5={}, url={}", md5, existing.getStorageUrl());
                return existing.getStorageUrl();
            }

            // 3. 校验分片是否完整
            String uploadKey = RedisKeys.fileUpload(md5);
            Map<Object, Object> uploadInfo = stringRedisTemplate.opsForHash().entries(uploadKey);
            if (uploadInfo.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "上传会话不存在，请重新发起上传");
            }

            String uploadId = (String) uploadInfo.get("uploadId");
            String fileKey = (String) uploadInfo.get("fileKey");
            int totalChunks = Integer.parseInt((String) uploadInfo.get("totalChunks"));

            String chunksKey = RedisKeys.fileChunks(md5);
            Long uploadedCount = stringRedisTemplate.opsForSet().size(chunksKey);
            if (uploadedCount == null || uploadedCount.intValue() != totalChunks) {
                throw new BusinessException(ErrorCode.FILE_CHUNK_INCOMPLETE,
                        String.format("分片未全部上传（%d/%d）", uploadedCount == null ? 0 : uploadedCount, totalChunks));
            }

            // 4. 读取所有 eTag，构建 partETags Map
            String etagsKey = RedisKeys.fileEtags(md5);
            Map<Object, Object> etagMap = stringRedisTemplate.opsForHash().entries(etagsKey);

            Map<Integer, String> partETags = new HashMap<>();
            for (Map.Entry<Object, Object> entry : etagMap.entrySet()) {
                int partNumber = Integer.parseInt((String) entry.getKey());
                String eTag = (String) entry.getValue();
                partETags.put(partNumber, eTag);
            }

            // 5. OSS 合并 — CompleteMultipartUpload
            storageService.completeMultipartUpload(fileKey, uploadId, partETags);

            // 6. OSS 合并后校验文件完整性：用文件大小比对
            long ossFileSize = storageService.getFileSize(fileKey);
            long expectedSize = Long.parseLong((String) uploadInfo.get("fileSize"));
            if (ossFileSize != expectedSize) {
                log.error("合并后文件大小不一致: expected={}, actual={}", expectedSize, ossFileSize);
                throw new BusinessException(ErrorCode.FILE_MD5_MISMATCH, "文件合并后校验失败（大小不一致）");
            }
            log.info("文件完整性校验通过: md5={}, size={} bytes", md5, ossFileSize);

            // 7. 入库 — file_info 表
            String fileUrl = storageService.getFileUrl(fileKey);
            FileInfo fileInfo = FileInfo.builder()
                    .fileMd5(md5)
                    .fileName(fileName)
                    .fileSize(ossFileSize)
                    .contentType("application/octet-stream")
                    .storageKey(fileKey)
                    .storageUrl(fileUrl)
                    .status("COMPLETED")
                    .createdAt(LocalDateTime.now())
                    .build();
            fileInfoMapper.insert(fileInfo);
            log.info("文件入库: md5={}, url={}", md5, fileUrl);

            return fileUrl;

        } finally {
            // 8. 释放 Redisson 锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            // 9. 清理 Redis 中的临时数据
            stringRedisTemplate.delete(RedisKeys.fileChunks(md5));
            stringRedisTemplate.delete(RedisKeys.fileEtags(md5));
            stringRedisTemplate.delete(RedisKeys.fileUpload(md5));
            log.info("Redis 临时数据已清理: md5={}", md5);
        }
    }

    // ============================================================
    // preview — 查询上传进度
    // ============================================================

    @Override
    public ChunkUploadResponse preview(String md5) {
        Map<Object, Object> uploadInfo = stringRedisTemplate.opsForHash()
                .entries(RedisKeys.fileUpload(md5));

        if (uploadInfo.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该文件的上传记录");
        }

        int totalChunks = Integer.parseInt((String) uploadInfo.get("totalChunks"));
        String uploadId = (String) uploadInfo.get("uploadId");

        Set<String> uploadedSet = stringRedisTemplate.opsForSet()
                .members(RedisKeys.fileChunks(md5));
        Set<Integer> uploadedChunks = uploadedSet != null
                ? uploadedSet.stream().map(Integer::parseInt).collect(Collectors.toSet())
                : Collections.emptySet();

        return ChunkUploadResponse.builder()
                .completed(false)
                .uploadId(uploadId)
                .totalChunks(totalChunks)
                .uploadedChunks(uploadedChunks)
                .build();
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    /**
     * 生成 OSS 文件存储路径
     * 格式：files/{yyyy/MM/dd}/{uuid8}_{safeName}
     */
    /**
     * 生成 OSS 文件存储路径
     * 格式：files/{yyyy/MM/dd}/{md5前8位}_{文件扩展名}
     * 用 MD5 做文件名主体，避免中文文件名导致 OSS 签名错误
     */
    private String generateFileKey(String md5, String originalFilename) {
        String datePath = LocalDateTime.now().format(DATE_PATH_FORMAT);
        String md5Prefix = md5.substring(0, 8);
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return String.format("files/%s/%s%s", datePath, md5Prefix, extension);
    }
}
