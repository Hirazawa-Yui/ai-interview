package com.aiinterview.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 分片上传 - check/preview 接口响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "上传状态响应")
public class ChunkUploadResponse {

    @Schema(description = "是否已完成（秒传时返回 true）", example = "false")
    private boolean completed;

    @Schema(description = "OSS MultipartUpload 的 uploadId", example = "F395FC2F65474B5A8BC45C09FDD929BE")
    private String uploadId;

    @Schema(description = "总分片数", example = "2")
    private Integer totalChunks;

    @Schema(description = "已上传的分片序号集合（0-based）", example = "[0, 1]")
    private Set<Integer> uploadedChunks;

    @Schema(description = "文件访问 URL（秒传或合并完成后返回）", example = "https://bucket.oss-cn-beijing.aliyuncs.com/files/2026/08/03/abc123_面试题库.pdf")
    private String url;
}
