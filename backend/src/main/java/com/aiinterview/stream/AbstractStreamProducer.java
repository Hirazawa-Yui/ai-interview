package com.aiinterview.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * Redis Stream 生产者模板（Spring Data Redis 版）
 * <p>
 * 子类只需实现 4 个抽象方法定义具体的 Stream Key、消息体、补偿逻辑。
 *
 * @param <T> 任务载荷类型
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    protected final StringRedisTemplate redisTemplate;

    public AbstractStreamProducer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送任务到 Redis Stream
     */
    public String sendTask(T payload) {
        try {
            Map<String, String> messageBody = buildMessage(payload);
            MapRecord<String, String, String> record = StreamRecords
                    .mapBacked(messageBody)
                    .withStreamKey(streamKey());

            var recordId = redisTemplate.opsForStream().add(record);
            // 裁剪 Stream 长度
            redisTemplate.opsForStream().trim(streamKey(), streamMaxLen());

            log.info("[{}] 任务入队成功: stream={}, messageId={}, payload={}",
                    taskDisplayName(), streamKey(), recordId, payloadIdentifier(payload));
            return recordId.getValue();
        } catch (Exception e) {
            log.error("[{}] 任务入队失败: stream={}, payload={}, error={}",
                    taskDisplayName(), streamKey(), payloadIdentifier(payload), e.getMessage());
            onSendFailed(payload, "任务入队失败: " + truncateError(e.getMessage()));
            return null;
        }
    }

    // ============================================================
    // 子类实现
    // ============================================================

    /** 任务中文名（用于日志） */
    protected abstract String taskDisplayName();

    /** Redis Stream Key */
    protected abstract String streamKey();

    /** Stream 最大长度（裁剪用） */
    protected long streamMaxLen() {
        return com.aiinterview.common.StreamKeys.STREAM_MAX_LEN;
    }

    /** 从 payload 构建消息体 Map */
    protected abstract Map<String, String> buildMessage(T payload);

    /** 载荷标识（用于日志） */
    protected abstract String payloadIdentifier(T payload);

    /** 发送失败补偿（如更新状态为 FAILED） */
    protected abstract void onSendFailed(T payload, String error);

    // ============================================================
    // 工具方法
    // ============================================================

    protected String truncateError(String error) {
        if (error == null) return null;
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
