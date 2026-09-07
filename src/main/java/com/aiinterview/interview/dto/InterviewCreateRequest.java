package com.aiinterview.interview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "创建面试会话请求")
public class InterviewCreateRequest {

    @Schema(description = "关联简历ID（可选，不选则仅基于JD出题）", example = "1")
    private Long resumeId;

    @NotBlank(message = "岗位JD不能为空")
    @Schema(description = "岗位JD文本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jdText;

    @NotBlank(message = "技术方向不能为空")
    @Schema(description = "技术方向：frontend/backend/test/algorithm/data/devops/fullstack",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "backend")
    private String direction;

    @NotNull(message = "题目数量不能为空")
    @Min(5) @Max(15)
    @Schema(description = "题目数量（5/8/10/15）", example = "8")
    private Integer questionCount;
}
