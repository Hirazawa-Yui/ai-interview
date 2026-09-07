package com.aiinterview.service;

import com.aiinterview.dto.InterviewEvaluationResponse;

import java.util.List;
import java.util.Map;

/**
 * 面试评估服务接口
 * <p>
 * 负责：单批评估调LLM → 增量批次评估 → 多批汇总 → 降级兜底
 */
public interface IInterviewEvaluationService {

    /**
     * 评估入口（由Consumer调用，全量模式/降级模式）
     * <p>
     * 加载Q&A → 判断是否分批 → 调LLM → 保存结果 → 更新会话状态
     *
     * @param sessionId 会话ID
     */
    void evaluate(Long sessionId);

    /**
     * 增量批次评估（由Consumer调用，每答完 batchSize 题触发）
     * <p>
     * 加载指定范围的 Q&A → 单次 LLM 评估 → 结果存 Redis
     *
     * @param sessionId   会话ID
     * @param batchNumber 批次号（1-based）
     * @param qStart      起始题号（inclusive）
     * @param qEnd        结束题号（inclusive）
     */
    void evaluateBatchRange(Long sessionId, Integer batchNumber, Integer qStart, Integer qEnd);

    /**
     * 单批评估：调LLM对一组Q&A进行评分
     *
     * @param qaList    问答列表（每项含questionText + answerText）
     * @param direction 技术方向
     * @return LLM结构化输出
     */
    InterviewEvaluationResponse evaluateBatch(List<Map<String, String>> qaList, String direction);

    /**
     * 多批汇总：将各批次评估结果汇总为最终报告
     *
     * @param batchResults 各批次评估结果JSON字符串列表
     * @return 汇总后的评估结果
     */
    InterviewEvaluationResponse summarizeBatches(List<String> batchResults);
}
