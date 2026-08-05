package com.aiinterview.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aiinterview.constant.StreamKeys.*;

/**
 * Redis Stream 消费者模板（Spring Data Redis 版）
 * <p>
 * 单线程消费循环 + Consumer Group + 应用层重试。
 * 子类实现 11 个抽象方法定义具体的业务处理逻辑。
 *
 * @param <T> 任务载荷类型
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    protected final StringRedisTemplate redisTemplate;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ThreadPoolExecutor executor;

    public AbstractStreamConsumer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @PostConstruct
    public void init() {
        String consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        log.info("[{}] 消费者启动: stream={}, group={}, consumer={}",
                taskDisplayName(), streamKey(), groupName(), consumerName);

        // 创建 Consumer Group（幂等，已存在则忽略）
        try {
            redisTemplate.opsForStream().createGroup(streamKey(), groupName());
        } catch (Exception e) {
            // BUSYGROUP → 已存在，忽略
            log.debug("[{}] Consumer Group 已存在: {}", taskDisplayName(), e.getMessage());
        }

        // 单线程执行器
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, threadName());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());

        running.set(true);
        executor.submit(this::consumeLoop);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[{}] 消费者关闭中...", taskDisplayName());
        running.set(false);
        if (executor != null) {
            executor.shutdown();
        }
    }

    // ============================================================
    // 消费循环
    // ============================================================

    private void consumeLoop() {
        while (running.get()) {
            try {
                // XREADGROUP GROUP <groupName> <consumerName> COUNT <BATCH_SIZE> BLOCK <POLL_INTERVAL_MS> STREAMS <streamKey> >
                List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                        .read(Consumer.from(groupName(), consumerPrefix()), // 指定消费者组名和消费者名，对应 XREADGROUP 的 GROUP 子句
                                StreamReadOptions.empty().count(BATCH_SIZE).block(Duration.ofMillis(POLL_INTERVAL_MS)),
                                StreamOffset.create(streamKey(), ReadOffset.lastConsumed()));
                // 只读取从未投递过的新消息（对应特殊 ID ">"）区别于 ReadOffset.from("0") 会读取已投递但未ACK的历史消息

                if (messages != null && !messages.isEmpty()) {
                    for (MapRecord<String, Object, Object> msg : messages) {
                        processMessage(msg);
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("[{}] 消费循环异常: {}", taskDisplayName(), e.getMessage(), e);
            }
        }
    }

    // ============================================================
    // 单条消息处理 + 重试逻辑
    // ============================================================

    private void processMessage(MapRecord<String, Object, Object> msg) {
        RecordId messageId = msg.getId();
        // Spring Data Redis 4.x 返回 Object/Object Map，需手动转 String
        Map<String, String> data = new java.util.HashMap<>();
        msg.getValue().forEach((k, v) -> data.put(String.valueOf(k), v != null ? v.toString() : ""));

        // 1. 解析载荷
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            ackMessage(messageId);  // 无效消息，ACK跳过
            return;
        }

        // 2. 解析重试次数
        int retryCount = parseRetryCount(data);

        try {
            // 3. 执行业务逻辑
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            log.info("[{}] 任务处理完成: id={}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("[{}] 任务处理失败: id={}, retry={}/{}, error={}",
                    taskDisplayName(), payloadIdentifier(payload), retryCount, MAX_RETRY_COUNT, e.getMessage());

            if (retryCount < MAX_RETRY_COUNT) {
                // 重试 — 重新入队
                retryMessage(payload, retryCount + 1);
            } else {
                // 最终失败
                markFailed(payload, truncateError(e.getMessage()));
            }
            ackMessage(messageId);  // 无论重试还是失败，都 ACK 原消息
        }
    }

    // ============================================================
    // 子类实现的抽象方法（11个）
    // ============================================================

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    /**
     * 解析消息为载荷对象，返回 null 表示跳过此消息
     */
    protected abstract T parsePayload(RecordId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    /**
     * 标记为处理中
     */
    protected abstract void markProcessing(T payload);

    /**
     * 核心业务逻辑
     */
    protected abstract void processBusiness(T payload);

    /**
     * 标记为已完成
     */
    protected abstract void markCompleted(T payload);

    /**
     * 标记为失败
     */
    protected abstract void markFailed(T payload, String error);

    /**
     * 重试：重新 XADD 一条消息
     */
    protected abstract void retryMessage(T payload, int retryCount);

    // ============================================================
    // 工具方法
    // ============================================================

    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected void ackMessage(RecordId messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey(), groupName(), messageId.getValue());
        } catch (Exception e) {
            log.warn("[{}] ACK失败: messageId={}", taskDisplayName(), messageId);
        }
    }

    protected String truncateError(String error) {
        if (error == null) return null;
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
