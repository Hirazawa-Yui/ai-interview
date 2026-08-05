package com.aiinterview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 简历上传响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "简历上传响应")
public class ResumeUploadResponse {

    @Schema(description = "简历ID", example = "1")
    private Long resumeId;

    @Schema(description = "文件名", example = "张三个人简历.pdf")
    private String fileName;

    @Schema(description = "分析状态", example = "PENDING")
    private String analyzeStatus;

    @Schema(description = "是否重复上传（已有分析结果）", example = "false")
    private boolean duplicate;

    @Schema(description = "重复上传时的历史分析ID", example = "null")
    private Long existingAnalysisId;
}
