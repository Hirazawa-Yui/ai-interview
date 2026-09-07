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
 * 简历 AI 分析结果（一对多，支持多次分析）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resume_analyses")
public class ResumeAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的简历 ID */
    private Long resumeId;

    /** 综合评分 0-100 */
    private Integer overallScore;

    /** AI 评语摘要 */
    private String summary;

    /** 优势列表（JSON 数组） */
    private String strengthsJson;

    /** 改进建议（JSON 数组） */
    private String suggestionsJson;

    /** LLM 原始返回（调试用） */
    private String aiRawResponse;

    private LocalDateTime analyzedAt;
}
