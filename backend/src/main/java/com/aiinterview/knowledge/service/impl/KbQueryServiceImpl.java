package com.aiinterview.knowledge.service.impl;

import com.aiinterview.common.StageWatch;
import com.aiinterview.common.ai.PromptSecurityConstants;
import com.aiinterview.knowledge.entity.KnowledgeBase;
import com.aiinterview.knowledge.mapper.KnowledgeBaseMapper;
import com.aiinterview.knowledge.service.IKbQueryService;
import com.aiinterview.knowledge.service.IKbVectorService;
import com.aiinterview.common.ai.IPromptDefenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KbQueryServiceImpl implements IKbQueryService {

    private final ChatClient chatClient;
    private final IKbVectorService vectorService;
    private final IPromptDefenseService defenseService;
    private final KnowledgeBaseMapper kbMapper;

    private final String systemPrompt;
    private final String userPrompt;
    private final String rewritePrompt;

    @Value("${app.ai.rag.rewrite.enabled:true}")
    private boolean rewriteEnabled;

    @Value("${app.ai.rag.search.short-query-length:4}")
    private int shortQueryLen;
    @Value("${app.ai.rag.search.long-query-length:16}")
    private int longQueryLen;
    @Value("${app.ai.rag.search.topk-short:20}")
    private int topkShort;
    @Value("${app.ai.rag.search.topk-medium:12}")
    private int topkMedium;
    @Value("${app.ai.rag.search.topk-long:8}")
    private int topkLong;
    @Value("${app.ai.rag.search.min-score-short:0.18}")
    private double minScoreShort;
    @Value("${app.ai.rag.search.min-score-default:0.28}")
    private double minScoreDefault;

    private static final int STREAM_PROBE_CHARS = 120;
    private static final String NO_RESULT_MSG = "抱歉，在选定的知识库中未检索到相关信息，无法回答此问题。";

    public KbQueryServiceImpl(ChatClient chatClient, IKbVectorService vectorService,
                               IPromptDefenseService defenseService, KnowledgeBaseMapper kbMapper) {
        this.chatClient = chatClient;
        this.vectorService = vectorService;
        this.defenseService = defenseService;
        this.kbMapper = kbMapper;
        this.systemPrompt = load("prompts/kb-query-system.st");
        this.userPrompt = load("prompts/kb-query-user.st");
        this.rewritePrompt = load("prompts/kb-query-rewrite.st");
    }

    @Override
    public Flux<String> answerQuestionStream(List<Long> kbIds, String question, List<Message> history) {
        // T17：全链路分段计时（不改行为），末尾打一行 [RAG耗时]
        StageWatch watch = new StageWatch();

        // 1. Query Rewrite
        String rewritten = rewriteEnabled ? rewriteQuestion(question, history) : question;
        watch.mark("rewrite");

        // 2. 动态 topK + minScore
        int topK = resolveTopK(rewritten);
        double minScore = resolveMinScore(rewritten);

        // 3. 向量检索
        List<Document> docs = vectorService.similaritySearch(rewritten, kbIds, topK, minScore);
        watch.mark("search");
        if (docs.isEmpty()) {
            logTiming(watch, kbIds, question, 0, 0, topK, minScore);
            return Flux.just(NO_RESULT_MSG);
        }

        // 4. 构建 RAG Prompt
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        // Phase3：检索内容用动态分隔符包裹
        String safeContext = defenseService.sanitizeAndWrap("kb-doc", context);
        String safeQuestion = defenseService.sanitize(question);

        String sysPrompt = systemPrompt + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        String usrPrompt = userPrompt.replace("{context}", safeContext).replace("{question}", safeQuestion);
        watch.mark("prompt");

        // 5. SSE 流式输出 + 探测窗口
        Flux<String> rawStream = chatClient.prompt()
                .system(sysPrompt)
                .user(usrPrompt)
                .stream()
                .content()
                .doOnNext(c -> watch.markOnce("llm首字"));

        int hits = docs.size();
        int ctxChars = context.length();
        return applyProbeWindow(rawStream)
                .doOnNext(c -> watch.markOnce("流首字"))
                .doFinally(sig -> {
                    watch.mark("生成");
                    logTiming(watch, kbIds, question, hits, ctxChars, topK, minScore);
                });
    }

    // ========== T17 耗时观测 ==========

    /** 单行耗时日志：各段是"相对上一段"的增量，总= 是端到端墙钟（complete/error/cancel 都会打） */
    private void logTiming(StageWatch watch, List<Long> kbIds, String question,
                           int hits, int ctxChars, int topK, double minScore) {
        log.info("[RAG耗时] {} | hits={} ctxChars={} topK={} minScore={} kbIds={} q=\"{}\"",
                watch, hits, ctxChars, topK, minScore, kbIds, abbreviate(question));
    }

    /** 日志里只留问题前 30 字，避免长问题刷屏 */
    private String abbreviate(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 30 ? flat : flat.substring(0, 30) + "…";
    }

    // ========== Query Rewrite ==========

    /** 改写时注入的助手回复最大字符数（截断，避免Prompt过长） */
    @Value("${app.ai.rag.rewrite.max-history-chars:200}")
    private int maxRewriteHistoryChars;

    private String rewriteQuestion(String question, List<Message> history) {
        try {
            String historyText = formatHistoryForRewrite(history);
            String userMsg = rewritePrompt
                    .replace("{question}", question)
                    .replace("{history}", historyText);
            String result = chatClient.prompt().user(userMsg).call().content();
            if (result != null && !result.isBlank() && result.length() <= 200) {
                log.info("Query Rewrite: \"{}\" → \"{}\" (historySize={})",
                        question, result.trim(), history != null ? history.size() : 0);
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("Query Rewrite失败，使用原始问题: {}", e.getMessage());
        }
        return question;
    }

    /** 格式化历史消息为文本，注入 Rewrite Prompt */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n对话历史：\n");
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                String text = msg.getText();
                if (text.length() > maxRewriteHistoryChars) {
                    text = text.substring(0, maxRewriteHistoryChars) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ========== 动态 topK ==========
    private int resolveTopK(String query) {
        int len = query.replaceAll("\\s+", "").length();
        if (len <= shortQueryLen) return topkShort;
        if (len >= longQueryLen) return topkLong;
        return topkMedium;
    }

    private double resolveMinScore(String query) {
        int len = query.replaceAll("\\s+", "").length();
        return len <= shortQueryLen ? minScoreShort : minScoreDefault;
    }

    // ========== SSE 探测窗口 ==========
    private Flux<String> applyProbeWindow(Flux<String> raw) {
        return Flux.create(sink -> {
            StringBuilder probe = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean probeDone = new java.util.concurrent.atomic.AtomicBoolean(false);
            raw.subscribe(
                    chunk -> {
                        if (probeDone.get()) {
                            sink.next(chunk);
                            return;
                        }
                        probe.append(chunk);
                        if (probe.length() >= STREAM_PROBE_CHARS) {
                            probeDone.set(true);
                            String full = probe.toString();
                            if (isNoResultLike(full)) {
                                sink.next(NO_RESULT_MSG);
                                sink.complete();
                            } else {
                                sink.next(full);
                            }
                        }
                    },
                    err -> { if (!probeDone.get()) { sink.next("【错误】知识库查询失败"); } sink.error(err); },
                    () -> {
                        if (!probeDone.get()) {
                            String full = probe.toString();
                            sink.next(isNoResultLike(full) ? NO_RESULT_MSG : full);
                        }
                        sink.complete();
                    }
            );
        });
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
                || text.contains("未检索到相关信息")
                || text.contains("信息不足")
                || text.contains("无法根据提供内容");
    }

    private String load(String path) {
        try { return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
}
