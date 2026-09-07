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
 * 用法：API Key 从环境变量 DASHSCOPE_API_KEY 读取（本地经 .env + IDEA EnvFile 注入，
 * 见仓库根 .env.example），右键 Run。
 * 收到回复 = 联通成功；报错 = 检查 Key 或网络。
 */
public class AIConnectionTest {

    public static void main(String[] args) {
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String model = "qwen3.7-flash";
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("❌ 未配置 DASHSCOPE_API_KEY：请复制 .env.example 为 .env 填入 Key，并在 IDEA Run Configuration 用 EnvFile 插件注入");
            return;
        }

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
