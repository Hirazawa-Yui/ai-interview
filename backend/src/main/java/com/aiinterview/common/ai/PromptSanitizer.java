package com.aiinterview.common.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Prompt 注入净化工具（第一层防御 + 第二层动态分隔符）。
 * <p>
 * 所有正则模式从 resources/defense/injection-patterns.json 加载，
 * 添加新模式只需编辑 JSON 文件，无需修改此代码。
 * <p>
 * 第一层：正则净化 — 纯代码层，命中后替换为占位符（只净化不报错）
 * 第二层：动态分隔符 — 随机 UUID 标签包裹用户数据
 */
@Component
public class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private final DefensePatternLoader patternLoader;

    @Value("${app.ai.defense.sanitizer.enabled:true}")
    private boolean sanitizerEnabled;

    @Value("${app.ai.defense.delimiter.enabled:true}")
    private boolean delimiterEnabled;

    public PromptSanitizer(DefensePatternLoader patternLoader) {
        this.patternLoader = patternLoader;
    }

    // ============================================================
    // 第一层：正则净化
    // ============================================================

    /**
     * 清洗用户文本，将所有匹配的攻击模式替换为对应占位符。
     * <p>
     * 只净化不报错——命中后替换为占位符继续执行，不给攻击者探测反馈。
     *
     * @param text 用户输入文本
     * @return 净化后的文本
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (!sanitizerEnabled) {
            return text;
        }

        boolean injected = false;
        String result = text;

        for (var p : patternLoader.getSanitizerPatterns()) {
            var matcher = p.pattern().matcher(result);
            if (matcher.find()) {
                // 用模式定义中指定的替换文本做 replaceAll（兼顾占位符和实际替换）
                result = matcher.replaceAll(p.replacement());
                injected = true;
            }
        }

        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length());
        }
        return result;
    }

    // ============================================================
    // 第二层：动态分隔符
    // ============================================================

    /**
     * 用不可预测的分隔符包裹用户文本。
     * 格式：{@code <data-boundary-{uuid片段}-{label}> ... </data-boundary-{uuid片段}-{label}>}
     * <p>
     * UUID 片段使攻击者无法提前构造伪造分隔符来逃逸数据区。
     * 每次调用的 UUID 都不同。
     *
     * @param label 数据标签（如 "resume"、"kb-doc"、"answer"）
     * @param text  用户输入文本
     * @return 包裹后的文本
     */
    public String wrapWithDelimiters(String label, String text) {
        if (!delimiterEnabled) {
            return text;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        return openTag + "\n" + text + "\n" + closeTag;
    }

    /**
     * 检测注入尝试（仅日志告警，不阻断）。
     */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (var p : patternLoader.getSanitizerPatterns()) {
            if (p.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
