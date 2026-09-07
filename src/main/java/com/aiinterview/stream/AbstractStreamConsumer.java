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

import static com.aiinterview.common.StreamKeys.*;

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

        // 确保 Consumer Group 存在（幂等：流不存在时先写占位消息再建组，见 ensureGroupExists）
        ensureGroupExists();

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
    // Consumer Group 初始化（幂等建组 + NOGROUP 自恢复）
    // ============================================================

    /** 占位消息字段名：Stream 不存在时先 XADD 一条占位消息使流存在（等效 MKSTREAM），
     *  占位消息不含业务字段，会被各 parsePayload 判为 null 后 ACK 跳过 */
    private static final String BOOTSTRAP_FIELD = "__bootstrap";

    /**
     * 确保 Consumer Group 存在。
     * <p>
     * 原实现吞掉建组异常导致两个静默失败场景：
     * 1. Stream key 尚不存在（首次启动，消息是第一个 XADD 才创建流的）→ XREADGROUP 永远 NOGROUP；
     * 2. 运行中被 FLUSHDB 清掉 → 消费者永久空转。
     * 现在：建组失败若不是 BUSYGROUP，先写占位消息保证流存在，再重试建组；仍失败才记录错误。
     */
    private void ensureGroupExists() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey(), groupName());
            log.info("[{}] Consumer Group 就绪: stream={}, group={}", taskDisplayName(), streamKey(), groupName());
        } catch (Exception e) {
            if (isRedisError(e, "BUSYGROUP")) {
                // 组已存在，正常
                log.debug("[{}] Consumer Group 已存在: {}", taskDisplayName(), e.getMessage());
                return;
            }
            log.warn("[{}] 建组失败（{}），尝试先创建 Stream 再建组: stream={}, group={}",
                    taskDisplayName(), e.getMessage(), streamKey(), groupName());
            try {
                // 流不存在时 createGroup 会报 NOGROUP/ERR —— XADD 一条占位消息即可创建流
                redisTemplate.opsForStream().add(
                        StreamRecords.mapBacked(Map.of(BOOTSTRAP_FIELD, "1")).withStreamKey(streamKey()));
                redisTemplate.opsForStream().createGroup(streamKey(), groupName());
                log.info("[{}] Consumer Group 就绪（含占位消息引导）: stream={}, group={}",
                        taskDisplayName(), streamKey(), groupName());
            } catch (Exception e2) {
                if (!isRedisError(e2, "BUSYGROUP")) {
                    log.error("[{}] Consumer Group 创建失败: stream={}, group={}, error={}",
                            taskDisplayName(), streamKey(), groupName(), e2.getMessage());
                }
            }
        }
    }

    /** 沿异常链查找 Redis 服务端错误码（BUSYGROUP/NOGROUP 等） */
    private boolean isRedisError(Exception e, String code) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(code)) {
                return true;
            }
        }
        return false;
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
                if (isRedisError(e, "NOGROUP")) {
                    // 组被删（如 FLUSHDB）→ 幂等重建后继续消费，避免永久空转
                    log.warn("[{}] 消费组缺失（NOGROUP），尝试重建: stream={}, group={}",
                            taskDisplayName(), streamKey(), groupName());
                    ensureGroupExists();
                }
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
