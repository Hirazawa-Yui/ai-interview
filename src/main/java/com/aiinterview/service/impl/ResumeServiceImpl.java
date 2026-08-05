package com.aiinterview.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.aiinterview.dto.ResumeUploadResponse;
import com.aiinterview.entity.Resume;
import com.aiinterview.entity.ResumeAnalysis;
import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.mapper.ResumeAnalysisMapper;
import com.aiinterview.mapper.ResumeMapper;
import com.aiinterview.service.IFileStorageService;
import com.aiinterview.service.IResumeParseService;
import com.aiinterview.service.IResumeService;
import com.aiinterview.stream.listener.ResumeAnalysisProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 简历管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeServiceImpl implements IResumeService {

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final IResumeParseService parseService;
    private final IFileStorageService storageService;
    private final ResumeAnalysisProducer analysisProducer;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );

    // ============================================================
    // 上传
    // ============================================================

    @Override
    @Transactional
    public ResumeUploadResponse upload(MultipartFile file) {
        // 1. 校验文件类型
        String contentType = parseService.detectContentType(file);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.RESUME_FILE_TYPE_NOT_SUPPORTED,
                    "不支持的文件类型: " + contentType);
        }

        // 2. 解析文本
        String parsedText = parseService.parse(file);

        // 3. 计算 MD5 去重
        String md5;
        try {
            md5 = DigestUtil.md5Hex(file.getBytes());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "文件读取失败");
        }

        // 查是否已有同 MD5 的简历
        Resume existing = resumeMapper.selectOne(
                new LambdaQueryWrapper<Resume>().eq(Resume::getFileMd5, md5)
        );
        if (existing != null && "COMPLETED".equals(existing.getAnalyzeStatus())) {
            // 秒返：已有分析结果
            return ResumeUploadResponse.builder()
                    .resumeId(existing.getId())
                    .fileName(existing.getOriginalFilename())
                    .analyzeStatus("COMPLETED")
                    .duplicate(true)
                    .build();
        }

        // 4. 上传到 OSS
        String fileKey = generateFileKey(file.getOriginalFilename());
        try {
            storageService.uploadFile(fileKey, file.getInputStream(), file.getSize(), contentType);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "OSS上传失败: " + e.getMessage());
        }
        String fileUrl = storageService.getFileUrl(fileKey);

        // 5. 入库
        Resume resume = Resume.builder()
                .fileMd5(md5)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .contentType(contentType)
                .storageKey(fileKey)
                .storageUrl(fileUrl)
                .parsedText(parsedText)
                .analyzeStatus("PENDING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        resumeMapper.insert(resume);

        // 6. 发送异步分析任务
        analysisProducer.sendAnalyzeTask(resume.getId(), parsedText);

        log.info("简历上传完成: id={}, file={}, analyzeStatus=PENDING", resume.getId(), file.getOriginalFilename());
        return ResumeUploadResponse.builder()
                .resumeId(resume.getId())
                .fileName(file.getOriginalFilename())
                .analyzeStatus("PENDING")
                .duplicate(false)
                .build();
    }

    // ============================================================
    // 列表
    // ============================================================

    @Override
    public List<Map<String, Object>> list() {
        List<Resume> resumes = resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>().orderByDesc(Resume::getCreatedAt)
        );

        return resumes.stream().map(r -> {
            // 查最新分析分数
            ResumeAnalysis latest = analysisMapper.selectOne(
                    new LambdaQueryWrapper<ResumeAnalysis>()
                            .eq(ResumeAnalysis::getResumeId, r.getId())
                            .orderByDesc(ResumeAnalysis::getAnalyzedAt)
                            .last("LIMIT 1")
            );

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("fileName", r.getOriginalFilename());
            item.put("fileSize", r.getFileSize());
            item.put("analyzeStatus", r.getAnalyzeStatus());
            item.put("latestScore", latest != null ? latest.getOverallScore() : null);
            item.put("createdAt", r.getCreatedAt());
            return item;
        }).collect(Collectors.toList());
    }

    // ============================================================
    // 详情
    // ============================================================

    @Override
    public Map<String, Object> detail(Long id) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        List<ResumeAnalysis> analyses = analysisMapper.selectList(
                new LambdaQueryWrapper<ResumeAnalysis>()
                        .eq(ResumeAnalysis::getResumeId, id)
                        .orderByDesc(ResumeAnalysis::getAnalyzedAt)
        );

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", resume.getId());
        detail.put("fileName", resume.getOriginalFilename());
        detail.put("fileSize", resume.getFileSize());
        detail.put("contentType", resume.getContentType());
        detail.put("storageUrl", resume.getStorageUrl());
        detail.put("parsedText", resume.getParsedText());
        detail.put("analyzeStatus", resume.getAnalyzeStatus());
        detail.put("analyzeError", resume.getAnalyzeError());
        detail.put("createdAt", resume.getCreatedAt());
        detail.put("analyses", analyses);
        return detail;
    }

    // ============================================================
    // 删除
    // ============================================================

    @Override
    @Transactional
    public void delete(Long id) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        // 删 OSS 文件
        try {
            storageService.deleteFile(resume.getStorageKey());
        } catch (Exception e) {
            log.warn("删除OSS文件失败: key={}", resume.getStorageKey(), e);
        }

        // 删分析结果
        analysisMapper.delete(new LambdaQueryWrapper<ResumeAnalysis>()
                .eq(ResumeAnalysis::getResumeId, id));

        // 删简历记录
        resumeMapper.deleteById(id);
        log.info("简历删除完成: id={}", id);
    }

    // ============================================================
    // 重新分析
    // ============================================================

    @Override
    public void reanalyze(Long id) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        // 重置状态
        resume.setAnalyzeStatus("PENDING");
        resume.setAnalyzeError(null);
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);

        // 重新发送分析任务
        analysisProducer.sendAnalyzeTask(resume.getId(), resume.getParsedText());
        log.info("重新分析已触发: resumeId={}", id);
    }

    // ============================================================
    // 私有方法
    // ============================================================

    private String generateFileKey(String originalFilename) {
        String datePath = LocalDateTime.now().format(DATE_PATH);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = originalFilename != null ? originalFilename : "unknown";
        return String.format("resumes/%s/%s_%s", datePath, uuid, safeName);
    }
}
