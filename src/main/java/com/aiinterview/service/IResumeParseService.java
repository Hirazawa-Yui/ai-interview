package com.aiinterview.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务（Tika 封装）
 */
public interface IResumeParseService {

    /**
     * 解析文件为纯文本
     *
     * @param file 上传文件
     * @return 解析后的文本
     */
    String parse(MultipartFile file);

    /**
     * 检测文件 MIME 类型
     */
    String detectContentType(MultipartFile file);
}
