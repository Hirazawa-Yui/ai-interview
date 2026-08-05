package com.aiinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class KbListItemDTO {
    private Long id;
    private String kbName;
    private String category;
    private String originalFilename;
    private Long fileSize;
    private String vectorStatus;
    private Integer chunkCount;
    private Integer questionCount;
    private LocalDateTime createdAt;
}
