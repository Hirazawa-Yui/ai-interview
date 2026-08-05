package com.aiinterview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
@Schema(description = "知识库问答请求")
public class KbQueryRequest {
    @NotEmpty
    @Schema(description = "知识库ID列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> knowledgeBaseIds;
    @NotBlank
    @Schema(description = "用户问题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;
}
