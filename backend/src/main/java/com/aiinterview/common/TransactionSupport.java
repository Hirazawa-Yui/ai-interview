package com.aiinterview.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务边界工具（观察项修复：事务内 XADD 竞态，跨模块坑 #8）
 * <p>
 * 背景：@Transactional 方法内先入库后 XADD/发消息时，消费者可能先于事务提交
 * 读到行 → 静默丢弃任务（简历/知识库上传偶发丢分析/向量化）。把"发送"挪到
 * 事务提交后执行，保证消费者读到的必是已提交数据。
 */
@Slf4j
public final class TransactionSupport {

    private TransactionSupport() {
    }

    /**
     * 注册事务提交后回调。当前存在事务同步 → afterCommit 执行；无事务上下文则直接执行。
     * 回调内异常只记录（提交已发生无法回滚；业务侧有 onSendFailed/降级兜底）。
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.error("事务提交后回调执行失败", e);
                    }
                }
            });
        } else {
            // 无事务上下文（防御分支）
            action.run();
        }
    }
}
