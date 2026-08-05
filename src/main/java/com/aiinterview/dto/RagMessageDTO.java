package com.aiinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RagMessageDTO {
    private Long id;
    private String role;
    private String content;
    private Integer messageOrder;
    private Boolean completed;
    private LocalDateTime createdAt;
}
