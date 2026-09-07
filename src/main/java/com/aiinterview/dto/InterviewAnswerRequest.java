package com.aiinterview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "提交面试回答请求")
public class InterviewAnswerRequest {

    @NotNull(message = "题号不能为空")
    @Min(1)
    @Schema(description = "题号（1-based）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer questionNumber;

    @NotBlank(message = "回答不能为空")
    @Schema(description = "回答文本", requiredMode = Schema.RequiredMode.REQUIRED)
    private String answerText;
}
