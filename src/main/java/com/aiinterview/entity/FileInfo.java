package com.aiinterview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已完成上传的文件记录（用于 MD5 秒传去重）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_info")
public class FileInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 完整文件的 MD5 值（Hex字符串，32位） */
    private String fileMd5;

    /** 原始文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** OSS 存储 Key（合并后的完整文件路径） */
    private String storageKey;

    /** OSS 访问 URL */
    private String storageUrl;

    /** 文件状态：COMPLETED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
