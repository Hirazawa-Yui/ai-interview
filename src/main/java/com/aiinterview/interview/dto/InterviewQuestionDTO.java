package com.aiinterview.interview.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 面试题目 DTO
 * <p>
 * 用于LLM结构化输出（出题结果）和前端展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "面试题目")
public class InterviewQuestionDTO {

    @JsonPropertyDescription("题号")
    @Schema(description = "题号", example = "1")
    private int questionNumber;

    @JsonPropertyDescription("题目文本 20-80字")
    @Schema(description = "题目文本", example = "请介绍一下你最有挑战性的项目经历")
    private String questionText;

    @JsonPropertyDescription("知识点标签")
    @Schema(description = "知识点标签", example = "[\"Java基础\",\"并发编程\"]")
    private List<String> tags;
}
