package com.aiinterview.config;

import com.aiinterview.entity.InterviewSession;
import com.aiinterview.mapper.InterviewSessionMapper;
import com.aiinterview.stream.listener.InterviewEvaluationProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复器：上次运行中断在 EVALUATING 的面试会话，重启后补发汇总任务。
 * <p>
 * 背景：评估汇总原实现用裸 new Thread 在进程内执行，应用重启后线程消失，
 * 会话永久卡在 EVALUATING 无法恢复。现汇总改为 Stream 消息（type=summarize）
 * 驱动（见 InterviewEvaluationServiceImpl.summarizeAndPersist），本类在启动时
 * 扫描状态为 EVALUATING 的会话并重新入队，由消费者完成 汇总/降级 闭环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewRecoveryRunner implements ApplicationRunner {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewEvaluationProducer evaluationProducer;

    @Override
    public void run(ApplicationArguments args) {
        List<InterviewSession> stuck = sessionMapper.selectList(
                new LambdaQueryWrapper<InterviewSession>().eq(InterviewSession::getStatus, "EVALUATING"));
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("检测到 {} 个中断在 EVALUATING 的面试会话，补发汇总任务恢复: {}",
                stuck.size(), stuck.stream().map(s -> s.getId().toString()).reduce((a, b) -> a + "," + b).orElse(""));
        for (InterviewSession session : stuck) {
            evaluationProducer.sendSummarizeTask(session.getId());
        }
    }
}
