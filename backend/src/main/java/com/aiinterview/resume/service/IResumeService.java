package com.aiinterview.resume.service;

import com.aiinterview.resume.dto.ResumeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 简历管理服务接口
 */
public interface IResumeService {

    /**
     * 上传简历并触发异步分析
     */
    ResumeUploadResponse upload(MultipartFile file);

    /**
     * 简历列表
     */
    List<Map<String, Object>> list();

    /**
     * 简历详情（含分析结果）
     */
    Map<String, Object> detail(Long id);

    /**
     * 删除简历（含 OSS 文件 + 关联分析）
     */
    void delete(Long id);

    /**
     * 重新分析
     */
    void reanalyze(Long id);
}
