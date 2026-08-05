package com.aiinterview.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.aiinterview.dto.KbListItemDTO;
import com.aiinterview.entity.KnowledgeBase;
import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.mapper.KnowledgeBaseMapper;
import com.aiinterview.service.IFileStorageService;
import com.aiinterview.service.IKnowledgeBaseService;
import com.aiinterview.stream.listener.VectorizeProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements IKnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final IFileStorageService storageService;
    private final VectorizeProducer vectorizeProducer;

    @Override
    @Transactional
    public Map<String, Object> upload(String fileKey, String kbName, String category) {
        // 1. 从OSS下载文件并解析文本
        byte[] fileBytes = storageService.downloadFile(fileKey);
        String text;
        try {
            text = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 二进制文件（PDF/Word），用Tika解析
            text = parseWithTika(fileBytes, fileKey);
        }

        // 2. 计算MD5去重
        String md5 = DigestUtil.md5Hex(fileBytes);
        KnowledgeBase existing = kbMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getFileMd5, md5));
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kbId", existing.getId());
            result.put("kbName", existing.getKbName());
            result.put("vectorStatus", existing.getVectorStatus());
            result.put("duplicate", true);
            return result;
        }

        // 3. 提取文件名
        String fileName = fileKey.contains("/") ? fileKey.substring(fileKey.lastIndexOf('/') + 1) : fileKey;
        long fileSize = storageService.getFileSize(fileKey);

        // 4. 入库
        KnowledgeBase kb = KnowledgeBase.builder()
                .fileMd5(md5)
                .kbName(kbName != null ? kbName : fileName)
                .category(category)
                .originalFilename(fileName)
                .fileSize(fileSize)
                .contentType("application/octet-stream")
                .storageKey(fileKey)
                .parsedText(text)
                .vectorStatus("PENDING")
                .chunkCount(0)
                .questionCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        kbMapper.insert(kb);

        // 5. 发送向量化任务
        vectorizeProducer.sendVectorizeTask(kb.getId(), text);

        log.info("知识库文档入库: id={}, name={}, vectorStatus=PENDING", kb.getId(), kb.getKbName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kbId", kb.getId());
        result.put("kbName", kb.getKbName());
        result.put("vectorStatus", "PENDING");
        result.put("duplicate", false);
        return result;
    }

    @Override
    public List<KbListItemDTO> list(String vectorStatus, String category, String sortBy) {
        var qw = new LambdaQueryWrapper<KnowledgeBase>();
        if (vectorStatus != null && !vectorStatus.isBlank())
            qw.eq(KnowledgeBase::getVectorStatus, vectorStatus);
        if (category != null && !category.isBlank())
            qw.eq(KnowledgeBase::getCategory, category);

        if ("size".equals(sortBy)) qw.orderByDesc(KnowledgeBase::getFileSize);
        else if ("question".equals(sortBy)) qw.orderByDesc(KnowledgeBase::getQuestionCount);
        else qw.orderByDesc(KnowledgeBase::getCreatedAt);

        return kbMapper.selectList(qw).stream().map(kb -> KbListItemDTO.builder()
                .id(kb.getId()).kbName(kb.getKbName()).category(kb.getCategory())
                .originalFilename(kb.getOriginalFilename()).fileSize(kb.getFileSize())
                .vectorStatus(kb.getVectorStatus()).chunkCount(kb.getChunkCount())
                .questionCount(kb.getQuestionCount()).createdAt(kb.getCreatedAt())
                .build()).toList();
    }

    @Override
    public Map<String, Object> detail(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.getId()); m.put("kbName", kb.getKbName());
        m.put("category", kb.getCategory()); m.put("originalFilename", kb.getOriginalFilename());
        m.put("fileSize", kb.getFileSize()); m.put("vectorStatus", kb.getVectorStatus());
        m.put("vectorError", kb.getVectorError()); m.put("chunkCount", kb.getChunkCount());
        m.put("questionCount", kb.getQuestionCount());
        m.put("parsedText", kb.getParsedText());
        m.put("createdAt", kb.getCreatedAt());
        return m;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        try { storageService.deleteFile(kb.getStorageKey()); } catch (Exception e) { log.warn("删OSS失败", e); }
        kbMapper.deleteById(id);
        log.info("知识库删除: id={}", id);
    }

    @Override
    public void updateCategory(Long id, String category) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        kb.setCategory(category);
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
    }

    @Override
    public void revectorize(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        kb.setVectorStatus("PENDING");
        kb.setVectorError(null);
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
        vectorizeProducer.sendVectorizeTask(kb.getId(), kb.getParsedText());
        log.info("重新向量化已触发: kbId={}", id);
    }

    private String parseWithTika(byte[] fileBytes, String fileName) {
        try {
            var handler = new org.apache.tika.sax.BodyContentHandler(5_000_000);
            var metadata = new org.apache.tika.metadata.Metadata();
            metadata.set("resourceName", fileName);
            var context = new org.apache.tika.parser.ParseContext();
            var parser = new org.apache.tika.parser.AutoDetectParser();
            parser.parse(new java.io.ByteArrayInputStream(fileBytes), handler, metadata, context);
            return handler.toString().trim();
        } catch (Exception e) {
            log.error("Tika解析失败: {}", fileName, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文档解析失败");
        }
    }
}
