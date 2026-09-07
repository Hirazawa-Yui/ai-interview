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
 * 评估结果返回前端 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "面试评估结果")
public class InterviewEvaluationDTO {

    @Schema(description = "评估ID")
    private Long id;

    @Schema(description = "综合评分 0-100")
    private Integer overallScore;

    @Schema(description = "综合评价")
    private String summary;

    @Schema(description = "逐题评分列表")
    private List<Map<String, Object>> perQuestion;

    @Schema(description = "优势列表")
    private List<String> strengths;

    @Schema(description = "改进建议列表")
    private List<Map<String, Object>> improvements;

    @Schema(description = "评估时间")
    private LocalDateTime evaluatedAt;
}
