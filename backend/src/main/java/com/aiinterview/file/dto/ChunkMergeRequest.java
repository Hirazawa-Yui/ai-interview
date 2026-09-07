package com.aiinterview.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分片上传 - /merge 接口请求参数
 */
@Data
@Schema(description = "合并分片请求")
public class ChunkMergeRequest {

    @Schema(description = "完整文件的 MD5", example = "d41d8cd98f00b204e9800998ecf8427e", requiredMode = Schema.RequiredMode.REQUIRED)
    private String md5;

    @Schema(description = "原始文件名", example = "面试题库.pdf", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;
}
