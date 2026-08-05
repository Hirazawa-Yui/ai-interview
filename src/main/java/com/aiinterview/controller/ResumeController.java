package com.aiinterview.controller;

import com.aiinterview.dto.ResumeUploadResponse;
import com.aiinterview.dto.Result;
import com.aiinterview.service.IResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 简历管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
@Tag(name = "简历管理", description = "简历上传、列表、详情、删除、重分析")
public class ResumeController {

    private final IResumeService resumeService;

    @Operation(summary = "上传简历", description = "上传简历文件（PDF/DOCX/TXT），自动触发AI异步分析")
    @PostMapping("/upload")
    public Result<ResumeUploadResponse> upload(
            @Parameter(description = "简历文件") @RequestParam("file") MultipartFile file) {
        log.info("简历上传: fileName={}, size={}", file.getOriginalFilename(), file.getSize());
        ResumeUploadResponse result = resumeService.upload(file);
        return Result.ok(result);
    }

    @Operation(summary = "简历列表", description = "获取所有简历，含最新分析评分")
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        log.info("简历列表");
        return Result.ok(resumeService.list());
    }

    @Operation(summary = "简历详情", description = "获取简历详情，含解析文本、分析历史")
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(
            @Parameter(description = "简历ID") @PathVariable Long id) {
        log.info("简历详情: id={}", id);
        return Result.ok(resumeService.detail(id));
    }

    @Operation(summary = "删除简历", description = "删除简历及其OSS文件和分析结果")
    @DeleteMapping("/{id}")
    public Result<String> delete(
            @Parameter(description = "简历ID") @PathVariable Long id) {
        log.info("简历删除: id={}", id);
        resumeService.delete(id);
        return Result.ok("删除成功");
    }

    @Operation(summary = "重新分析", description = "对已有简历重新触发AI分析")
    @PostMapping("/{id}/reanalyze")
    public Result<String> reanalyze(
            @Parameter(description = "简历ID") @PathVariable Long id) {
        log.info("简历重分析: id={}", id);
        resumeService.reanalyze(id);
        return Result.ok("已触发重新分析");
    }
}
