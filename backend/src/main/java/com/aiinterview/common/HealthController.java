package com.aiinterview.common;

import com.aiinterview.config.StorageProperties;
import com.aiinterview.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口
 * <p>
 * 检查 LLM Chat API、Embedding API、阿里云 OSS 的连通性。
 * <p>
 * 结果缓存 30s（D2）——实时探测有 token 成本与延迟，HealthPage 打开/轮询时命中缓存；
 * 手动"重新检测"传 {@code ?force=1} 绕过缓存强制实时。
 */
@Tag(name = "系统健康", description = "应用存活检查 + 外部服务连通性测试")
@Slf4j
@RestController
public class HealthController {

    /** 探测结果缓存时长（30s） */
    private static final long CACHE_TTL_MS = 30_000;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private S3Client s3Client;

    @Autowired(required = false)
    private StorageProperties storageProperties;

    /** 缓存结果（volatile 可见性；不深拷贝，序列化即用） */
    private volatile Map<String, Object> cachedReport;
    private volatile long cachedAtMs;

    @Operation(summary = "健康检查", description = "验证应用运行状态及外部服务连通性；结果缓存 30s，force=1 强制实时探测")
    @GetMapping("/api/health")
    public Result<Map<String, Object>> health(
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedReport;
        if (!force && cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return Result.ok(cached);
        }
        return Result.ok(checkAll(force, now));
    }

    /** 实时探测并刷新缓存（synchronized 防并发请求同时打 LLM） */
    private synchronized Map<String, Object> checkAll(boolean force, long now) {
        Map<String, Object> fresh = cachedReport;
        if (!force && fresh != null && now - cachedAtMs < CACHE_TTL_MS) {
            return fresh; // 双检：等待锁期间另一请求已刷新
        }
        log.info("健康检查（实时探测）");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("app", "UP");

        // 检查 LLM Chat API
        report.put("llm", checkLlm());

        // 检查 Embedding API
        report.put("embedding", checkEmbedding());

        // 检查 OSS
        report.put("oss", checkOss());

        cachedReport = report;
        cachedAtMs = now;
        return report;
    }

    // ========== LLM Chat API ==========

    private Map<String, Object> checkLlm() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String response = chatClient.prompt()
                    .user("Reply with OK only.")
                    .call()
                    .content();
            long cost = System.currentTimeMillis() - start;
            boolean ok = response != null && !response.isBlank();
            result.put("status", ok ? "UP" : "DOWN");
            result.put("latency", cost + "ms");
            if (!ok) result.put("error", "AI 返回空响应");
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("latency", (System.currentTimeMillis() - start) + "ms");
            result.put("error", truncate(e.getMessage(), 200));
            log.warn("LLM 健康检查失败: {}", e.getMessage());
        }
        return result;
    }

    // ========== Embedding API ==========

    private Map<String, Object> checkEmbedding() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            var embeddings = embeddingModel.embed("健康检查测试文本");
            long cost = System.currentTimeMillis() - start;
            result.put("status", embeddings != null && embeddings.length > 0 ? "UP" : "DOWN");
            result.put("latency", cost + "ms");
            if (embeddings != null) result.put("dimensions", embeddings.length);
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("latency", (System.currentTimeMillis() - start) + "ms");
            result.put("error", truncate(e.getMessage(), 200));
            log.warn("Embedding 健康检查失败: {}", e.getMessage());
        }
        return result;
    }

    // ========== OSS ==========

    private Map<String, Object> checkOss() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (s3Client == null || storageProperties == null) {
            result.put("status", "DISABLED");
            result.put("message", "OSS 未启用（app.storage.enabled=false）");
            return result;
        }
        long start = System.currentTimeMillis();
        try {
            s3Client.listObjectsV2(b -> b.bucket(storageProperties.getBucket()).maxKeys(1));
            long cost = System.currentTimeMillis() - start;
            result.put("status", "UP");
            result.put("latency", cost + "ms");
            result.put("bucket", storageProperties.getBucket());
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("latency", (System.currentTimeMillis() - start) + "ms");
            result.put("error", truncate(e.getMessage(), 200));
            log.warn("OSS 健康检查失败: {}", e.getMessage());
        }
        return result;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
