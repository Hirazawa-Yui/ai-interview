package com.aiinterview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Schema(description = "知识库问答响应")
public class KbQueryResponse {
    @Schema(description = "回答内容") private String answer;
    @Schema(description = "知识库名称") private String kbName;
    @Schema(description = "检索到的文档片段数") private int retrievedChunks;
}
