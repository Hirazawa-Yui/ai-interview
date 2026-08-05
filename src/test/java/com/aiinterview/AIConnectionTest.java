package com.aiinterview;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 联通性测试 — 直接在 main 方法跑，不依赖 Spring 容器
 * <p>
 * 用法：把下面的 baseUrl / apiKey 改成你自己的，右键 Run。
 * 收到回复 = 联通成功；报错 = 检查 Key 或网络。
 */
public class AIConnectionTest {

    public static void main(String[] args) {
        // ========== 改这里 ==========
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String apiKey = "sk-828bed09df0d4d70b2ac85c24b37892a";
        String model = "qwen3.5-flash";
        // ============================

        System.out.println("=== AI 联通性测试 ===");
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Model   : " + model);
        System.out.println("---");

        try {
            // 1. 构建 API 客户端
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(10_000);
            requestFactory.setReadTimeout(60_000);

            RestClient.Builder restClientBuilder = RestClient.builder()
                    .requestFactory(requestFactory);

            // 注意：baseUrl 已含 /v1，必须显式指定 completionsPath 和 embeddingsPath，
            // 否则 Spring AI 默认拼 /v1/chat/completions → 变成 /v1/v1/chat/completions → 404
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .restClientBuilder(restClientBuilder)
                    .completionsPath("/chat/completions")
                    .embeddingsPath("/embeddings")
                    .build();

            // 2. 构建 ChatModel
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.2)
                    .build();

            OpenAiChatModel chatModel = new OpenAiChatModel(
                    openAiApi,
                    options,
                    ToolCallingManager.builder().build(),
                    RetryUtils.DEFAULT_RETRY_TEMPLATE,
                    ObservationRegistry.NOOP
            );

            // 3. 发一句简单对话
            String response = chatModel.call("你好，请用一句话介绍自己");
            System.out.println("AI 回复: " + response);
            System.out.println("\n✅ AI 联通成功！");

        } catch (Exception e) {
            System.out.println("❌ 联通失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
