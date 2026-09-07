package com.aiinterview.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "知识库文档上传请求")
public class KbUploadRequest {
    @Schema(description = "Phase2合并后返回的OSS fileKey", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileKey;
    @Schema(description = "知识库文档名称", example = "Java面试题库")
    private String kbName;
    @Schema(description = "分类标签", example = "Java")
    private String category;
}
