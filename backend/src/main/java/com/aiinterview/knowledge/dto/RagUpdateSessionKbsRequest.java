package com.aiinterview.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 会话更换知识库请求（T23）
 * <p>
 * 会话创建时把 kbIds 存进了 rag_chat_sessions.kb_ids，后续问答一直用它检索；
 * 本接口让前端在选择框里的改动真正落到会话上（否则改了什么也不会生效）。
 */
@Data
@Schema(description = "会话更换知识库请求")
public class RagUpdateSessionKbsRequest {

    @NotEmpty(message = "至少选择一个知识库")
    @Schema(description = "新的知识库 id 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> kbIds;
}
