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
 * 面试会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_sessions")
public class InterviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联简历ID（可为NULL，表示未使用简历） */
    private Long resumeId;

    /** 用户填写的岗位JD */
    private String jdText;

    /** 技术方向：frontend/backend/test/algorithm/data/devops/fullstack */
    private String direction;

    /** 总题数 */
    private Integer questionCount;

    /** 当前进度（已答完的题数，0=未开始） */
    private Integer currentQuestion;

    /** 状态：CREATED/IN_PROGRESS/COMPLETED/EVALUATING/EVALUATED/FAILED */
    private String status;

    /** 预生成题目JSON数组：[{questionNumber,questionText,tags}] */
    private String questionsJson;

    /** 评估失败原因 */
    private String evaluateError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
