package com.aiinterview.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * LLM 结构化输出 — 简历分析结果
 * <p>
 * 该类用于 BeanOutputConverter 的 JSON Schema 生成，
 * 字段上的 Jackson 注解帮助 LLM 理解输出格式要求。
 */
@Data
public class ResumeAnalysisResponse {

    @JsonPropertyDescription("综合评分 0-100")
    private Integer overallScore;

    @JsonPropertyDescription("AI评估摘要，2-3句话总结简历整体质量")
    private String summary;

    @JsonPropertyDescription("优势列表，每条一句话")
    private List<String> strengths;

    @JsonPropertyDescription("改进建议列表")
    private List<Suggestion> suggestions;

    @Data
    public static class Suggestion {
        @JsonPropertyDescription("建议类别：技术深度/项目经验/表达优化/结构排版")
        private String category;

        @JsonPropertyDescription("优先级：high/medium/low")
        private String priority;

        @JsonPropertyDescription("发现的问题")
        private String issue;

        @JsonPropertyDescription("修改建议")
        private String recommendation;
    }
}
