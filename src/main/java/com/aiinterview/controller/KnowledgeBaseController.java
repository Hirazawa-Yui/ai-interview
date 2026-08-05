package com.aiinterview.controller;

import com.aiinterview.dto.KbListItemDTO;
import com.aiinterview.dto.KbUploadRequest;
import com.aiinterview.dto.Result;
import com.aiinterview.service.IKnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "文档上传、列表、详情、删除、分类、重向量化")
public class KnowledgeBaseController {

    private final IKnowledgeBaseService kbService;

    @Operation(summary = "文档入库", description = "将Phase2已上传到OSS的文件注册到知识库并触发向量化")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestBody KbUploadRequest req) {
        log.info("知识库上传: fileKey={}, kbName={}, category={}", req.getFileKey(), req.getKbName(), req.getCategory());
        return Result.ok(kbService.upload(req.getFileKey(), req.getKbName(), req.getCategory()));
    }

    @Operation(summary = "文档列表")
    @GetMapping("/list")
    public Result<List<KbListItemDTO>> list(
            @Parameter(description = "按状态筛选") @RequestParam(required = false) String vectorStatus,
            @Parameter(description = "按分类筛选") @RequestParam(required = false) String category,
            @Parameter(description = "排序: time/size/question") @RequestParam(required = false, defaultValue = "time") String sortBy) {
        log.info("知识库列表: status={}, category={}, sort={}", vectorStatus, category, sortBy);
        return Result.ok(kbService.list(vectorStatus, category, sortBy));
    }

    @Operation(summary = "文档详情")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        log.info("知识库详情: id={}", id);
        return Result.ok(kbService.detail(id));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        log.info("知识库删除: id={}", id);
        kbService.delete(id);
        return Result.ok("删除成功");
    }

    @Operation(summary = "更新分类")
    @PutMapping("/{id}/category")
    public Result<String> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        log.info("知识库分类更新: id={}, category={}", id, body.get("category"));
        kbService.updateCategory(id, body.get("category"));
        return Result.ok("分类已更新");
    }

    @Operation(summary = "重新向量化")
    @PostMapping("/{id}/revectorize")
    public Result<String> revectorize(@PathVariable Long id) {
        log.info("知识库重向量化: id={}", id);
        kbService.revectorize(id);
        return Result.ok("已触发重新向量化");
    }
}
