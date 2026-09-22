package com.aiinterview.common;

import java.util.HashSet;
import java.util.Set;

/**
 * 请求级分段耗时统计（T17）。
 * <p>
 * 一次请求一个实例：{@link #mark(String)} 记录"距上一次标记"的耗时并追加进摘要，
 * 末尾把整个对象塞进日志占位符就能看到各段占比，例如：
 * <pre>总=8750ms | rewrite=1420ms search=260ms prompt=18ms llm首字=1200ms 流首字=1520ms 生成=5200ms</pre>
 * <p>
 * 流式回调可能在不同线程触发，故各方法 synchronized；只做计时和拼串，开销可忽略。
 */
public final class StageWatch {

    private final long start = System.nanoTime();
    private long last = start;
    private final StringBuilder summary = new StringBuilder();
    private final Set<String> onceKeys = new HashSet<>();

    /** 记录自上次 mark（或构造）以来的耗时，返回毫秒 */
    public synchronized long mark(String stage) {
        long now = System.nanoTime();
        long ms = (now - last) / 1_000_000;
        last = now;
        if (summary.length() > 0) {
            summary.append(' ');
        }
        summary.append(stage).append('=').append(ms).append("ms");
        return ms;
    }

    /** 只在首次调用时生效（用于"第一个 token 到达"这类一次性事件） */
    public synchronized void markOnce(String stage) {
        if (onceKeys.add(stage)) {
            mark(stage);
        }
    }

    /** 从构造到现在的总耗时（毫秒） */
    public synchronized long totalMs() {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public synchronized String toString() {
        return "总=" + totalMs() + "ms | " + summary;
    }
}
