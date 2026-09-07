package com.aiinterview.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分片上传 - /check 接口请求参数
 */
@Data
@Schema(description = "上传状态检查请求")
public class ChunkCheckRequest {

    @Schema(description = "完整文件的 MD5（32位Hex），作为全局唯一标识", example = "d41d8cd98f00b204e9800998ecf8427e", requiredMode = Schema.RequiredMode.REQUIRED)
    private String md5;

    @Schema(description = "原始文件名", example = "面试题库.pdf", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;

    @Schema(description = "文件总大小（字节）", example = "10485760", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fileSize;

    @Schema(description = "总分片数（= 文件大小 / 5MB 向上取整）", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer totalChunks;

    @Schema(description = "MIME 类型", example = "application/pdf")
    private String contentType;
}
