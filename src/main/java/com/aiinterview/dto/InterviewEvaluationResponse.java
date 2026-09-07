package com.aiinterview.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * LLM 评估结构化输出 DTO
 * <p>
 * 用于 BeanOutputConverter，LLM 按此格式返回 JSON
 */
@Data
@Schema(description = "LLM评估响应")
public class InterviewEvaluationResponse {

    @JsonPropertyDescription("综合评分 0-100")
    @Schema(description = "综合评分", example = "82")
    private int overallScore;

    @JsonPropertyDescription("综合评价 100-200字")
    @Schema(description = "综合评价")
    private String summary;

    @JsonPropertyDescription("逐题评价")
    @Schema(description = "逐题评价列表")
    private List<QuestionEvaluation> perQuestion;

    @JsonPropertyDescription("优势列表 3-5条")
    @Schema(description = "优势列表")
    private List<String> strengths;

    @JsonPropertyDescription("改进建议 3-5条")
    @Schema(description = "改进建议列表")
    private List<Suggestion> improvements;

    @Data
    @Schema(description = "单题评价")
    public static class QuestionEvaluation {
        @JsonPropertyDescription("题号")
        private int questionNumber;

        @JsonPropertyDescription("该题评分 0-100")
        private int score;

        @JsonPropertyDescription("评语 20-50字")
        private String comment;
    }

    @Data
    @Schema(description = "改进建议")
    public static class Suggestion {
        @JsonPropertyDescription("分类：技术基础/项目经验/表达沟通/系统设计/其他")
        private String category;

        @JsonPropertyDescription("具体问题描述")
        private String issue;

        @JsonPropertyDescription("改进建议")
        private String suggestion;
    }
}
