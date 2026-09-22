package com.aiinterview.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话重命名请求（T21）
 * <p>
 * 50 是产品上限（会话标题就是列表里一格），不是 DB 上限——
 * 表里是 VARCHAR(200)，留足余量让超长永远在这里被拦下，
 * 不会掉进 Postgres value too long → 500「系统繁忙」那种糟糕的错误形态。
 */
@Data
@Schema(description = "会话重命名请求")
public class RagRenameSessionRequest {

    @NotBlank(message = "会话标题不能为空")
    @Size(max = 50, message = "会话标题不能超过 50 个字符")
    @Schema(description = "新标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "Redis 复习")
    private String title;
}
