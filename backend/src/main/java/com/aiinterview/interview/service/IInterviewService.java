package com.aiinterview.interview.service;

import java.util.List;
import java.util.Map;

/**
 * 面试服务接口
 * <p>
 * 负责面试会话的创建、答题流程、历史管理
 */
public interface IInterviewService {

    /**
     * 创建面试会话并生成题目
     *
     * @param resumeId       关联简历ID（可为null）
     * @param jdText         岗位JD文本
     * @param direction      技术方向
     * @param questionCount  题目数量
     * @return {sessionId, direction, questionCount, firstQuestion}
     */
    Map<String, Object> createSession(Long resumeId, String jdText,
                                      String direction, Integer questionCount);

    /**
     * 提交回答，返回下一题或完成信号
     *
     * @param sessionId      会话ID
     * @param questionNumber 题号（1-based）
     * @param answerText     回答文本
     * @return 如果未答完：{nextQuestion, progress: "2/8"}
     *         如果已答完：{allDone: true, message: "..."}
     */
    Map<String, Object> submitAnswer(Long sessionId, Integer questionNumber,
                                     String answerText);

    /**
     * 面试历史列表
     */
    List<Map<String, Object>> listSessions();

    /**
     * 会话详情（含Q&A消息）
     */
    Map<String, Object> getSessionDetail(Long sessionId);

    /**
     * 删除面试会话（级联删除答案）
     */
    void deleteSession(Long sessionId);

    /**
     * 触发异步评估（更新状态 → 发Stream任务）
     *
     * @return {evaluateStatus: "EVALUATING"}
     */
    Map<String, Object> triggerEvaluation(Long sessionId);

    /**
     * 获取评估结果
     */
    Map<String, Object> getEvaluation(Long sessionId);
}
