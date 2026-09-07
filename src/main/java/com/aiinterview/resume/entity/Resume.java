package com.aiinterview.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resumes")
public class Resume {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件 MD5（32位Hex） */
    private String fileMd5;

    /** 原始文件名 */
    private String originalFilename;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** OSS 存储 Key */
    private String storageKey;

    /** OSS 访问 URL */
    private String storageUrl;

    /** Tika 解析后的纯文本 */
    private String parsedText;

    /** 分析状态：PENDING / PROCESSING / COMPLETED / FAILED */
    private String analyzeStatus;

    /** 分析失败原因 */
    private String analyzeError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
