package com.aiinterview.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * LLM 配置（单Provider，OpenAI兼容接口）
 * <p>
 * 在 application.yml 中配置 app.ai.llm.* 即可切换模型。
 * 支持通义千问、DeepSeek、Kimi 等所有 OpenAI 兼容接口。
 * <p>
 * 为什么不做多Provider管理？
 * 90%的用户只会用一个模型，搞管理界面增加维护成本。
 * 真要换模型，改两行 yml 比开发一个管理后台快得多。
 */
@Configuration
public class LlmConfig {

    private static final int CONNECT_TIMEOUT = 10_000;  // 10秒连接超时
    private static final int READ_TIMEOUT = 60_000;     // 60秒读取超时（AI响应可能较慢）

    @Value("${app.ai.llm.base-url}")
    private String baseUrl;

    @Value("${app.ai.llm.api-key}")
    private String apiKey;

    @Value("${app.ai.llm.model}")
    private String model;

    @Value("${app.ai.llm.embedding-model}")
    private String embeddingModel;

    /** 思考模式开关（T18）：qwen3 系列默认先产出完整思维链再作答 */
    @Value("${app.ai.llm.enable-thinking:false}")
    private boolean enableThinking;

    /**
     * OpenAI兼容的 API 客户端（Builder模式 — Spring AI 2.0 API）
     */
    @Bean
    @Lazy
    public OpenAiApi openAiApi() {
        // 配置超时时间（连接到AI服务可能较慢）
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .build();
    }

    /**
     * ChatModel — 对话/内容生成
     */
    @Bean
    @Primary
    @Lazy
    public ChatModel chatModel(OpenAiApi openAiApi) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.2);  // 面试场景需要稳定输出，温度不宜太高

        // 关思考模式（T18，默认关）：qwen3 系列默认先产出完整 reasoning_content 思维链再作答，
        // 实测同样一次调用（"hi"，max_tokens=16）默认 4.82s、关闭后 0.58s。开启时会让 RAG 改写与
        // 作答、面试出题/评估、简历分析全线多花 1~30s。需要模型"想清楚"时可把开关置 true。
        if (!enableThinking) {
            optionsBuilder.extraBody(Map.of("enable_thinking", false));
        }
        OpenAiChatOptions options = optionsBuilder.build();

        return new OpenAiChatModel(
                openAiApi,
                options,
                ToolCallingManager.builder().build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP
        );
    }

    /**
     * ChatClient — Spring AI 的流式API封装（用于SSE流式输出等高级场景）
     */
    @Bean
    @Lazy
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * EmbeddingModel — 文本向量化（用于知识库RAG检索）
     */
    @Bean
    @Lazy
    public EmbeddingModel embeddingModel(OpenAiApi openAiApi) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embeddingModel)
                .dimensions(1024)
                .build();

        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                options,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP
        );
    }
}
