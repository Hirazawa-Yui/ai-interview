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
 * 面试评估实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_evaluations")
public class InterviewEvaluation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联面试会话ID */
    private Long sessionId;

    /** 综合评分 0-100 */
    private Integer overallScore;

    /** 综合评价 */
    private String summary;

    /** 逐题评分 JSON：[{questionNumber,score,comment}] */
    private String perQuestionJson;

    /** 优势列表 JSON数组 */
    private String strengthsJson;

    /** 改进建议 JSON数组 */
    private String improvementsJson;

    /** LLM原始返回（调试用） */
    private String aiRawResponse;

    private LocalDateTime evaluatedAt;
}
