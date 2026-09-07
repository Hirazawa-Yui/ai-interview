package com.aiinterview.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("knowledge_bases")
public class KnowledgeBase {
    @TableId(type = IdType.AUTO) private Long id;
    private String fileMd5;
    private String kbName;
    private String category;
    private String originalFilename;
    private Long fileSize;
    private String contentType;
    private String storageKey;
    private String parsedText;
    private String vectorStatus;
    private String vectorError;
    private Integer chunkCount;
    private Integer questionCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
