package com.aiinterview.common.ai;

import com.aiinterview.common.ai.DefensePatternLoader;
import com.aiinterview.common.ai.OutputGuardConfig;
import com.aiinterview.common.ai.PromptSanitizer;
import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.common.ai.IPromptDefenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Prompt 注入防护服务实现
 * <p>
 * 三层防御的编排层，Phase 4~6 所有调用 LLM 的地方都通过此服务做防护：
 * <pre>
 *   // 调用前
 *   String safeInput = defenseService.sanitizeAndWrap("resume", userResumeText);
 *   String prompt = buildPrompt(safeInput);
 *
 *   // 调用后
 *   String llmResponse = chatClient.call(prompt);
 *   defenseService.guardOutput(llmResponse);
 * </pre>
 */
@Slf4j
@Service
public class PromptDefenseServiceImpl implements IPromptDefenseService {

    private final PromptSanitizer sanitizer;
    private final OutputGuardConfig guardConfig;
    private final DefensePatternLoader patternLoader;

    public PromptDefenseServiceImpl(PromptSanitizer sanitizer,
                                     OutputGuardConfig guardConfig,
                                     DefensePatternLoader patternLoader) {
        this.sanitizer = sanitizer;
        this.guardConfig = guardConfig;
        this.patternLoader = patternLoader;
    }

    // ============================================================
    // 第一层 + 第二层：净化 + 包裹（最常用入口）
    // ============================================================

    @Override
    public String sanitizeAndWrap(String label, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 1. 先净化（第一层）
        String cleaned = sanitize(text);
        // 2. 再包裹（第二层）
        return wrapWithDelimiters(label, cleaned);
    }

    // ============================================================
    // 第一层：正则净化
    // ============================================================

    @Override
    public String sanitize(String text) {
        return sanitizer.sanitize(text);
    }

    // ============================================================
    // 第二层：动态分隔符包裹
    // ============================================================

    @Override
    public String wrapWithDelimiters(String label, String text) {
        return sanitizer.wrapWithDelimiters(label, text);
    }

    // ============================================================
    // 第三层：输出护栏
    // ============================================================

    @Override
    public void guardOutput(String llmResponse) {
        if (!guardConfig.isEnabled()) {
            return;
        }
        if (llmResponse == null || llmResponse.isBlank()) {
            return;
        }

        for (var guardPattern : patternLoader.getGuardPatterns()) {
            if (guardPattern.pattern().matcher(llmResponse).find()) {
                log.warn("输出护栏触发! 命中模式 [{}]: \"{}\", 输出长度: {}",
                        guardPattern.name(), guardPattern.description(), llmResponse.length());
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 服务返回异常，请稍后重试");
            }
        }
    }

    // ============================================================
    // json 围栏剥离（解析前的防御处理）
    // ============================================================

    /** ```json ... ``` 围栏（语言标记可选）；非贪婪取首个围栏块 */
    private static final java.util.regex.Pattern JSON_FENCE =
            java.util.regex.Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override
    public String stripJsonFence(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return llmResponse;
        }
        var m = JSON_FENCE.matcher(llmResponse);
        if (m.find()) {
            String inner = m.group(1).trim();
            if (!inner.isEmpty()) {
                log.debug("剥离 json 围栏: 原始长度 {} → {}", llmResponse.length(), inner.length());
                return inner;
            }
        }
        return llmResponse;
    }
}
