package com.aiinterview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI模拟面试平台 - 主启动类
 * <p>
 * 提供简历分析、知识库RAG、模拟面试、分片上传等功能。
 * 基础设施依赖：PostgreSQL+pgvector（向量检索）、Redis（缓存+Stream消息队列）、阿里云OSS（文件存储）。
 * <p>
 * exclude: 排除 Spring AI 音频/图片自动配置（本项目不做语音面试，不需要 TTS/STT）
 */
@EnableAsync
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan({
        "com.aiinterview.file.mapper",
        "com.aiinterview.resume.mapper",
        "com.aiinterview.knowledge.mapper",
        "com.aiinterview.interview.mapper"
})
@SpringBootApplication(exclude = {
        // 排除不需要的AI自动配置（本项目不做语音/图片/审核）
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
        // Chat/Embedding 由 LlmConfig 手动管理
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        // Redisson starter 不兼容 Boot 4.x，由 RedissonConfig 手动创建
        org.redisson.spring.starter.RedissonAutoConfigurationV2.class,
})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
