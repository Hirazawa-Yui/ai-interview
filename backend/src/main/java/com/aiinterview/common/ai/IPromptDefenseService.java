package com.aiinterview.common.ai;

/**
 * Prompt 注入防护服务接口
 * <p>
 * 三层防御：
 * 1. 正则净化（sanitize）—— 纯代码层，拦截已知攻击模式
 * 2. 动态分隔符（wrap）—— 随机 UUID 标签包裹用户数据
 * 3. 输出护栏（guard）—— 检测 LLM 输出中的投降语
 * <p>
 * Phase 4~6 所有调用 LLM 的地方都应先调用 sanitize + wrap，LLM 返回后调用 guard。
 */
public interface IPromptDefenseService {

    /**
     * 第一层 + 第二层：净化用户输入并用动态分隔符包裹
     * <p>
     * 这是最常用的入口方法，组合了 sanitize → wrap 两步操作。
     *
     * @param label 数据标签（如 "resume"、"kb-doc"、"answer"）
     * @param text  用户输入文本
     * @return 净化后并用 {@code <data-boundary-{uuid}-{label}>} 包裹的文本
     */
    String sanitizeAndWrap(String label, String text);

    /**
     * 第三层：检测 LLM 输出中是否包含投降语
     *
     * @param llmResponse LLM 返回的文本
     * @throws com.aiinterview.common.BusinessException 如果检测到注入攻击，抛出 AI_SERVICE_ERROR
     */
    void guardOutput(String llmResponse);

    /**
     * 防御性 json 围栏剥离：LLM 偶发用 ```json 代码块包裹 JSON 返回，解析前剥离围栏取内文。
     * 命中围栏且内文非空 → 返回内文；否则原样返回（不修其他畸形，留给解析层报错）。
     * <p>
     * 规范约定：所有 LLM 输出在结构化解析前必须经此方法处理。
     *
     * @param llmResponse LLM 返回的文本
     * @return 剥离围栏后的文本（null/空原样返回）
     */
    String stripJsonFence(String llmResponse);

    /**
     * 仅第一层：正则净化
     *
     * @param text 用户输入文本
     * @return 净化后的文本
     */
    String sanitize(String text);

    /**
     * 仅第二层：用动态分隔符包裹
     *
     * @param label 数据标签
     * @param text  用户输入文本
     * @return 包裹后的文本
     */
    String wrapWithDelimiters(String label, String text);
}
