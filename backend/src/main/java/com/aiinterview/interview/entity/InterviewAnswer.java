package com.aiinterview.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试回答实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_answers")
public class InterviewAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的面试会话ID */
    private Long sessionId;

    /** 题号（1-based） */
    private Integer questionNumber;

    /** 题目文本（冗余存储，方便查询） */
    private String questionText;

    /** 用户回答 */
    private String answerText;

    private LocalDateTime createdAt;
}
