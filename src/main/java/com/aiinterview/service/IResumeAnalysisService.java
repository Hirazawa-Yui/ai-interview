package com.aiinterview.service;

import com.aiinterview.dto.ResumeAnalysisResponse;

/**
 * 简历 AI 分析服务（LLM 评分）
 */
public interface IResumeAnalysisService {

    /**
     * 对简历文本进行 AI 分析和评分
     *
     * @param resumeText 简历纯文本
     * @return 分析结果（评分 + 优势 + 建议）
     */
    ResumeAnalysisResponse analyze(String resumeText);
}
