package com.aiinterview.interview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 面试会话详情 DTO（含Q&A消息列表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "面试会话详情")
public class InterviewSessionDTO {

    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "关联简历ID")
    private Long resumeId;

    @Schema(description = "岗位JD")
    private String jdText;

    @Schema(description = "技术方向")
    private String direction;

    @Schema(description = "总题数")
    private Integer questionCount;

    @Schema(description = "当前进度")
    private Integer currentQuestion;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "预生成题目列表")
    private List<Map<String, Object>> questions;

    @Schema(description = "已答题目列表（含问题和回答）")
    private List<Map<String, Object>> answers;

    @Schema(description = "评估错误信息")
    private String evaluateError;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
