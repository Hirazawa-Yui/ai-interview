package com.aiinterview.controller;

import com.aiinterview.dto.ChunkCheckRequest;
import com.aiinterview.dto.ChunkMergeRequest;
import com.aiinterview.dto.ChunkUploadResponse;
import com.aiinterview.dto.Result;
import com.aiinterview.service.IFileChunkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件分片上传控制器
 * <p>
 * 支持大文件（>50MB）的高可靠上传：
 * - MD5 秒传：相同文件无需重复上传
 * - 断点续传：网络中断后可从中断处继续
 * - 先落OSS后记账：保证 Redis 中记录的都是 OSS 确认写入的数据
 * - 前端触发合并 + SETNX 锁：避免并发合并冲突
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "文件分片上传", description = "大文件分片上传、秒传、断点续传")
public class FileChunkController {

    private final IFileChunkService fileChunkService;

    @Operation(
            summary = "检查上传状态",
            description = """
                    前端在正式上传前先调此接口，根据返回决定后续流程：
                    - completed=true → 秒传（文件已存在），跳过上传
                    - uploadedChunks 非空 → 断点续传，跳过已传分片
                    - uploadedChunks 为空 → 新任务，从第0片开始上传"""
    )
    @PostMapping("/check")
    public Result<ChunkUploadResponse> check(
            @Parameter(description = "文件MD5、文件名、总大小、总分片数") @RequestBody ChunkCheckRequest request) {
        log.info("上传检查: md5={}, totalChunks={}", request.getMd5(), request.getTotalChunks());
        ChunkUploadResponse result = fileChunkService.check(
                request.getMd5(),
                request.getFileName(),
                request.getFileSize(),
                request.getTotalChunks()
        );
        return Result.ok(result);
    }

    @Operation(
            summary = "上传单个分片",
            description = """
                    前端并发调用此接口（建议并发数 ≤ 3）。
                    后端先落盘 OSS → 校验分片 MD5 → 再记账 Redis。
                    分片 MD5 校验失败会直接拒绝，要求前端重传。"""
    )
    @PostMapping(value = "/chunk", consumes = "multipart/form-data")
    public Result<String> uploadChunk(
            @Parameter(description = "文件 MD5", required = true) @RequestParam("md5") String md5,
            @Parameter(description = "分片序号（0-based）", required = true) @RequestParam("chunkIndex") int chunkIndex,
            @Parameter(description = "此分片的 MD5（用于后端校验）") @RequestParam(value = "chunkMd5", required = false) String chunkMd5,
            @Parameter(description = "分片二进制数据") @RequestParam("file") MultipartFile file) {

        log.info("分片上传: md5={}, chunkIndex={}, size={}", md5, chunkIndex, file.getSize());
        try {
            byte[] chunkData = file.getBytes();
            fileChunkService.uploadChunk(md5, chunkIndex, chunkMd5, chunkData, file.getSize());
            return Result.ok("分片 " + chunkIndex + " 上传成功");
        } catch (IOException e) {
            log.error("读取分片数据失败: md5={}, chunkIndex={}", md5, chunkIndex, e);
            return Result.fail("分片数据读取失败");
        }
    }

    @Operation(
            summary = "合并所有分片",
            description = """
                    前端确认所有分片上传完毕后调用此接口（只调一次）。
                    后端做三重保护：
                    1. SETNX 合并锁（60秒过期，防并发）
                    2. 数据库幂等校验（防重复合并）
                    3. 分片完整性校验（SCARD 核对分片数）
                    合并成功后写入 file_info 表，清理 Redis 临时数据。"""
    )
    @PostMapping("/merge")
    public Result<String> merge(
            @Parameter(description = "文件 MD5 + 文件名") @RequestBody ChunkMergeRequest request) {
        log.info("触发合并: md5={}, fileName={}", request.getMd5(), request.getFileName());
        String url = fileChunkService.merge(request.getMd5(), request.getFileName());
        return Result.ok(url);
    }

    @Operation(
            summary = "查询上传进度",
            description = "前端暂停/刷新页面后恢复上传时，先调此接口获取已传分片列表"
    )
    @GetMapping("/preview/{md5}")
    public Result<ChunkUploadResponse> preview(
            @Parameter(description = "文件 MD5", required = true) @PathVariable String md5) {
        log.info("查询进度: md5={}", md5);
        ChunkUploadResponse result = fileChunkService.preview(md5);
        return Result.ok(result);
    }
}
