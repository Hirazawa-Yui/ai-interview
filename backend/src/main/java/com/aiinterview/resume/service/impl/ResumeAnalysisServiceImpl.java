package com.aiinterview.resume.service.impl;

import com.aiinterview.common.ai.PromptSecurityConstants;
import com.aiinterview.resume.dto.ResumeAnalysisResponse;
import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.common.ai.IPromptDefenseService;
import com.aiinterview.resume.service.IResumeAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 简历 AI 分析服务实现
 * <p>
 * 构建 Prompt → 调用 LLM → 结构化输出 JSON → Phase 3 输出护栏 → 返回结果
 */
@Slf4j
@Service
public class ResumeAnalysisServiceImpl implements IResumeAnalysisService {

    private final ChatClient chatClient;
    private final IPromptDefenseService defenseService;

    private final String systemPromptTemplate;
    private final String userPromptTemplate;

    public ResumeAnalysisServiceImpl(ChatClient chatClient,
                                      IPromptDefenseService defenseService,
                                      @Value("${app.resume.analysis.system-prompt:classpath:prompts/resume-analysis-system.st}") String systemPromptPath,
                                      @Value("${app.resume.analysis.user-prompt:classpath:prompts/resume-analysis-user.st}") String userPromptPath) {
        this.chatClient = chatClient;
        this.defenseService = defenseService;
        this.systemPromptTemplate = loadTemplate(systemPromptPath);
        this.userPromptTemplate = loadTemplate(userPromptPath);
    }

    @Override
    public ResumeAnalysisResponse analyze(String resumeText) {
        // 1. 构建结构化输出的 Converter
        BeanOutputConverter<ResumeAnalysisResponse> converter =
                new BeanOutputConverter<>(ResumeAnalysisResponse.class);

        // 2. 构建 System Prompt：模板 + Phase3 防注入指令 + JSON Schema
        String systemPrompt = systemPromptTemplate
                + "\n\n## JSON 输出格式\n" + converter.getFormat()
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

        // 3. 净化并包裹用户输入
        String safeText = defenseService.sanitizeAndWrap("resume", resumeText);

        // 4. 构建 User Prompt
        String userPrompt = userPromptTemplate.replace("{resumeText}", safeText);

        // 5. 调用 LLM
        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        if (llmResponse == null || llmResponse.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 服务返回空响应");
        }

        // 6. Phase3 输出护栏
        defenseService.guardOutput(llmResponse);

        // 7. 解析 JSON → ResumeAnalysisResponse（先剥离可能出现的 json 围栏）
        try {
            log.info("AI 简历分析成功");
            return converter.convert(defenseService.stripJsonFence(llmResponse));
        } catch (Exception e) {
            log.error("解析 AI 分析结果失败: {}", e.getMessage());
            log.debug("LLM 原始返回: {}", llmResponse);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 返回格式异常，请稍后重试");
        }
    }

    private String loadTemplate(String path) {
        try {
            // 去掉 classpath: 前缀
            String cleanPath = path.replace("classpath:", "");
            return new ClassPathResource(cleanPath).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("加载 Prompt 模板失败: {}", path, e);
            return "";
        }
    }
}
