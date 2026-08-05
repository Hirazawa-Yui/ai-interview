package com.aiinterview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("rag_chat_messages")
public class RagChatMessage {
    @TableId(type = IdType.AUTO) private Long id;
    private Long sessionId;
    private String role;         // user / assistant
    private String content;
    private Integer messageOrder;
    private Boolean completed;
    private LocalDateTime createdAt;
}
