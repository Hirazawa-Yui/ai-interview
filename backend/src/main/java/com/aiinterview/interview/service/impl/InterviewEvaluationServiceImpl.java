package com.aiinterview.interview.service.impl;

import com.aiinterview.common.ai.PromptSecurityConstants;
import com.aiinterview.common.RedisKeys;
import com.aiinterview.interview.dto.InterviewEvaluationResponse;
import com.aiinterview.interview.entity.InterviewAnswer;
import com.aiinterview.interview.entity.InterviewEvaluation;
import com.aiinterview.interview.entity.InterviewSession;
import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.interview.mapper.InterviewAnswerMapper;
import com.aiinterview.interview.mapper.InterviewEvaluationMapper;
import com.aiinterview.interview.mapper.InterviewSessionMapper;
import com.aiinterview.interview.service.IInterviewEvaluationService;
import com.aiinterview.common.ai.IPromptDefenseService;
import com.aiinterview.interview.listener.InterviewEvaluationProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 面试评估服务实现
 * <p>
 * 分批评估 → 二次汇总 → 降级兜底
 */
@Slf4j
@Service
public class InterviewEvaluationServiceImpl implements IInterviewEvaluationService {

    private final ChatClient chatClient;
    private final IPromptDefenseService defenseService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final InterviewEvaluationMapper evaluationMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final String evalSystemTemplate;
    private final String evalUserTemplate;
    private final String summarySystemTemplate;

    private final int batchSize;
    private final InterviewEvaluationProducer evaluationProducer;

    public InterviewEvaluationServiceImpl(ChatClient chatClient,
                                           IPromptDefenseService defenseService,
                                           InterviewSessionMapper sessionMapper,
                                           InterviewAnswerMapper answerMapper,
                                           InterviewEvaluationMapper evaluationMapper,
                                           ObjectMapper objectMapper,
                                           StringRedisTemplate redisTemplate,
                                           InterviewEvaluationProducer evaluationProducer,
                                           @Value("${app.interview.evaluation.system-prompt:classpath:prompts/interview-evaluation-system.st}") String evalSysPath,
                                           @Value("${app.interview.evaluation.user-prompt:classpath:prompts/interview-evaluation-user.st}") String evalUsrPath,
                                           @Value("${app.interview.evaluation.summary-system-prompt:classpath:prompts/interview-summary-system.st}") String sumSysPath,
                                           @Value("${app.interview.evaluation.batch-size:4}") int batchSize) {
        this.chatClient = chatClient;
        this.defenseService = defenseService;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.evaluationMapper = evaluationMapper;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.evaluationProducer = evaluationProducer;
        this.evalSystemTemplate = loadTemplate(evalSysPath);
        this.evalUserTemplate = loadTemplate(evalUsrPath);
        this.summarySystemTemplate = loadTemplate(sumSysPath);
        this.batchSize = batchSize;
    }

    // ============================================================
    // 评估入口（Consumer 调用）
    // ============================================================

    @Override
    public void evaluate(Long sessionId) {
        // 1. 加载会话
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.error("评估失败：会话不存在 sessionId={}", sessionId);
            return;
        }

        // 2. 加载所有 Q&A（按题号排序）
        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .orderByAsc(InterviewAnswer::getQuestionNumber)
        );

        if (answers.isEmpty()) {
            log.error("评估失败：没有回答记录 sessionId={}", sessionId);
            session.setStatus("FAILED");
            session.setEvaluateError("没有回答记录");
            sessionMapper.updateById(session);
            return;
        }

        int totalQuestions = answers.size();
        log.info("开始评估: sessionId={}, direction={}, totalQuestions={}, batchSize={}",
                sessionId, session.getDirection(), totalQuestions, batchSize);

        try {
            InterviewEvaluationResponse finalResult;

            if (totalQuestions <= batchSize) {
                // 3a. 单次评估（不超批大小）
                log.info("单次评估: sessionId={}", sessionId);
                List<Map<String, String>> qaList = buildQaList(answers);
                finalResult = evaluateBatch(qaList, session.getDirection());
            } else {
                // 3b. 分批评估 + 汇总
                log.info("分批评估: sessionId={}", sessionId);
                List<String> batchResults = new ArrayList<>();

                for (int i = 0; i < totalQuestions; i += batchSize) {
                    int end = Math.min(i + batchSize, totalQuestions);
                    List<InterviewAnswer> batchAnswers = answers.subList(i, end);
                    List<Map<String, String>> qaList = buildQaList(batchAnswers);

                    log.info("评估批次: sessionId={}, batch={}-{}", sessionId, i + 1, end);
                    InterviewEvaluationResponse batchResult = evaluateBatch(qaList, session.getDirection());
                    batchResults.add(toJson(batchResult));
                }

                // 汇总
                try {
                    finalResult = summarizeBatches(batchResults);
                } catch (Exception e) {
                    // 降级：拼接各批次结果
                    log.warn("汇总失败，降级为简单拼接: sessionId={}", sessionId, e);
                    finalResult = degradeMerge(batchResults);
                }
            }

            // 4. 保存评估结果 + 更新会话状态
            persistEvaluation(sessionId, finalResult);

            log.info("评估完成: sessionId={}, overallScore={}", sessionId, finalResult.getOverallScore());

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("评估异常: sessionId={}", sessionId, e);
            markSessionFailed(sessionId, e.getMessage());
        }
    }

    // ============================================================
    // 增量批次评估
    // ============================================================

    @Override
    public void evaluateBatchRange(Long sessionId, Integer batchNumber, Integer qStart, Integer qEnd) {
        log.info("增量批次评估: sessionId={}, batch={}, range={}-{}", sessionId, batchNumber, qStart, qEnd);

        // 1. 加载指定范围的 Q&A
        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .ge(InterviewAnswer::getQuestionNumber, qStart)
                        .le(InterviewAnswer::getQuestionNumber, qEnd)
                        .orderByAsc(InterviewAnswer::getQuestionNumber)
        );

        if (answers.isEmpty()) {
            log.warn("增量批次无回答记录: sessionId={}, range={}-{}", sessionId, qStart, qEnd);
            return;
        }

        // 2. 加载会话获取方向
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.error("增量批次会话不存在: sessionId={}", sessionId);
            return;
        }

        // 3. 单批评估（不超4题，一次 LLM 调用）
        try {
            List<Map<String, String>> qaList = buildQaList(answers);
            InterviewEvaluationResponse result = evaluateBatch(qaList, session.getDirection());

            // 4. 结果存 Redis
            String batchKey = RedisKeys.interviewBatchEval(sessionId, batchNumber);
            String doneKey = RedisKeys.interviewBatchDone(sessionId);
            String lockKey = RedisKeys.interviewBatchLock(sessionId);

            redisTemplate.opsForValue().set(batchKey, toJson(result), Duration.ofSeconds(RedisKeys.INTERVIEW_BATCHEVAL_TTL));
            redisTemplate.opsForSet().add(doneKey, String.valueOf(batchNumber));
            redisTemplate.expire(doneKey, Duration.ofSeconds(RedisKeys.INTERVIEW_BATCHEVAL_TTL));

            // 释放锁
            redisTemplate.delete(lockKey);

            log.info("增量批次评估完成: sessionId={}, batch={}, score={}",
                    sessionId, batchNumber, result.getOverallScore());
        } catch (Exception e) {
            log.error("增量批次评估失败: sessionId={}, batch={}", sessionId, batchNumber, e);
            // 释放锁，允许重试
            String lockKey = RedisKeys.interviewBatchLock(sessionId);
            redisTemplate.delete(lockKey);
            throw new RuntimeException("增量批次评估失败", e);
        }
    }

    // ============================================================
    // 汇总并入库（SUMMARIZE 消息入口，替代原裸 new Thread 汇总）
    // ============================================================

    @Override
    public void summarizeAndPersist(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.error("汇总失败：会话不存在 sessionId={}", sessionId);
            return;
        }
        // 幂等：仅 EVALUATING 状态执行汇总（防重复消息 / 乱序旧消息 / 启动恢复补发重复）
        if (!"EVALUATING".equals(session.getStatus())) {
            log.info("跳过汇总（状态非 EVALUATING）: sessionId={}, status={}", sessionId, session.getStatus());
            return;
        }

        int totalQuestions = session.getQuestionCount() == null ? 0 : session.getQuestionCount();
        int totalBatches = (totalQuestions + batchSize - 1) / batchSize;
        if (totalBatches <= 0) {
            log.warn("无题目数，降级全量评估: sessionId={}", sessionId);
            evaluationProducer.sendEvaluateTask(sessionId);
            return;
        }
        String doneKey = RedisKeys.interviewBatchDone(sessionId);

        try {
            // 1. 等待所有增量批次完成（最多等 30s；批次消息先于汇总消息入队，正常无需等待）
            Set<String> doneBatches = new HashSet<>();
            for (int attempt = 0; attempt < 30; attempt++) {
                doneBatches = redisTemplate.opsForSet().members(doneKey);
                if (doneBatches != null && doneBatches.size() >= totalBatches) break;
                Thread.sleep(1000);
            }

            if (doneBatches == null || doneBatches.size() < totalBatches) {
                // 2. 批次不齐 → 降级全量评估（消费线程处理，不阻塞其他消息类型）
                log.warn("增量批次不完整，降级全量评估: sessionId={}, done={}/{}",
                        sessionId, doneBatches != null ? doneBatches.size() : 0, totalBatches);
                evaluationProducer.sendEvaluateTask(sessionId);
                return;
            }

            // 3. 收集所有批次结果
            List<String> batchJsons = new ArrayList<>();
            for (String bn : doneBatches) {
                String json = redisTemplate.opsForValue()
                        .get(RedisKeys.interviewBatchEval(sessionId, Integer.parseInt(bn)));
                if (json != null) batchJsons.add(json);
            }
            if (batchJsons.isEmpty()) {
                log.warn("增量批次结果为空，降级全量评估: sessionId={}", sessionId);
                evaluationProducer.sendEvaluateTask(sessionId);
                return;
            }

            // 4. 汇总（1次 LLM 调用，失败降级本地拼接）
            InterviewEvaluationResponse finalResult;
            try {
                finalResult = summarizeBatches(batchJsons);
            } catch (Exception e) {
                log.warn("汇总LLM调用失败，降级拼接: sessionId={}", sessionId, e);
                finalResult = degradeMerge(batchJsons);
            }

            // 5. 入库 + 更新状态 EVALUATED
            persistEvaluation(sessionId, finalResult);

            // 6. 清理 Redis 增量缓存
            redisTemplate.delete(doneKey);
            for (String bn : doneBatches) {
                redisTemplate.delete(RedisKeys.interviewBatchEval(sessionId, Integer.parseInt(bn)));
            }

            log.info("评估汇总完成: sessionId={}, overallScore={}", sessionId, finalResult.getOverallScore());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("评估汇总被中断: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("评估汇总异常: sessionId={}", sessionId, e);
            markSessionFailed(sessionId, e.getMessage());
        }
    }

    /** 评估结果入库 + 更新会话状态 EVALUATED（全量评估与汇总共用） */
    private void persistEvaluation(Long sessionId, InterviewEvaluationResponse finalResult) {
        InterviewEvaluation evaluation = InterviewEvaluation.builder()
                .sessionId(sessionId)
                .overallScore(finalResult.getOverallScore())
                .summary(finalResult.getSummary())
                .perQuestionJson(toJson(finalResult.getPerQuestion()))
                .strengthsJson(toJson(finalResult.getStrengths()))
                .improvementsJson(toJson(finalResult.getImprovements()))
                .aiRawResponse(toJson(finalResult))
                .evaluatedAt(LocalDateTime.now())
                .build();
        evaluationMapper.insert(evaluation);

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setStatus("EVALUATED");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    /** 置会话 FAILED + 失败原因（全量评估与汇总共用） */
    private void markSessionFailed(Long sessionId, String error) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setStatus("FAILED");
            session.setEvaluateError(truncate(error, 500));
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    // ============================================================
    // 单批评估
    // ============================================================

    @Override
    public InterviewEvaluationResponse evaluateBatch(List<Map<String, String>> qaList, String direction) {
        // 1. 构建结构化输出 Converter
        BeanOutputConverter<InterviewEvaluationResponse> converter =
                new BeanOutputConverter<>(InterviewEvaluationResponse.class);

        // 2. 构建 System Prompt
        String systemPrompt = evalSystemTemplate
                .replace("{direction}", direction)
                + "\n\n## JSON 输出格式\n" + converter.getFormat()
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

        // 3. 构建 Q&A 文本（每对包裹在分隔符中）
        StringBuilder qaText = new StringBuilder();
        for (int i = 0; i < qaList.size(); i++) {
            Map<String, String> qa = qaList.get(i);
            qaText.append("### 第").append(i + 1).append("题\n");
            qaText.append("**问题**: ").append(qa.get("questionText")).append("\n");
            qaText.append("**回答**: ").append(qa.get("answerText")).append("\n\n");
        }
        String safeQaText = defenseService.sanitizeAndWrap("qa-record", qaText.toString());

        // 4. 构建 User Prompt
        String userPrompt = evalUserTemplate
                .replace("{direction}", direction)
                .replace("{questionsAndAnswers}", safeQaText);

        // 5. 调用 LLM
        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        if (llmResponse == null || llmResponse.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 评估返回空响应");
        }

        // 6. Phase3 输出护栏
        defenseService.guardOutput(llmResponse);

        // 7. 解析 JSON
        try {
            log.info("单批评估完成: {}题", qaList.size());
            return converter.convert(llmResponse);
        } catch (Exception e) {
            log.error("解析评估结果失败: {}", e.getMessage());
            log.debug("LLM原始返回: {}", llmResponse);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 评估返回格式异常，请稍后重试");
        }
    }

    // ============================================================
    // 多批汇总
    // ============================================================

    @Override
    public InterviewEvaluationResponse summarizeBatches(List<String> batchResults) {
        log.info("汇总评估: batchCount={}", batchResults.size());

        // 1. 构建汇总 Converter（只处理 score/summary/strengths/improvements，不含 perQuestion）
        BeanOutputConverter<InterviewEvaluationResponse> converter =
                new BeanOutputConverter<>(InterviewEvaluationResponse.class);

        // 2. System Prompt（汇总模板不需要 direction）
        String systemPrompt = summarySystemTemplate
                + "\n\n## JSON 输出格式\n" + converter.getFormat()
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

        // 3. User Prompt：拼接各批次结果
        StringBuilder batchesText = new StringBuilder();
        for (int i = 0; i < batchResults.size(); i++) {
            batchesText.append("### 批次").append(i + 1).append("\n");
            batchesText.append(batchResults.get(i)).append("\n\n");
        }
        String safeBatches = defenseService.sanitizeAndWrap("batch-results", batchesText.toString());

        // 4. 调 LLM
        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user("## 各批次评估结果\n\n" + safeBatches)
                .call()
                .content();

        if (llmResponse == null || llmResponse.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 汇总返回空响应");
        }

        defenseService.guardOutput(llmResponse);

        try {
            InterviewEvaluationResponse summary = converter.convert(llmResponse);
            // perQuestion 从各批次拼接
            List<InterviewEvaluationResponse.QuestionEvaluation> allPerQuestion = new ArrayList<>();
            for (String batchJson : batchResults) {
                try {
                    InterviewEvaluationResponse batch = objectMapper.readValue(batchJson,
                            InterviewEvaluationResponse.class);
                    if (batch.getPerQuestion() != null) {
                        allPerQuestion.addAll(batch.getPerQuestion());
                    }
                } catch (Exception e) {
                    log.warn("解析批次结果失败，跳过: {}", e.getMessage());
                }
            }
            summary.setPerQuestion(allPerQuestion);
            log.info("汇总完成: overallScore={}", summary.getOverallScore());
            return summary;
        } catch (Exception e) {
            log.error("解析汇总结果失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 汇总返回格式异常");
        }
    }

    // ============================================================
    // 降级兜底
    // ============================================================

    /**
     * 汇总失败时的降级方案：简单拼接各批次结果，取平均分
     */
    private InterviewEvaluationResponse degradeMerge(List<String> batchResults) {
        List<InterviewEvaluationResponse.QuestionEvaluation> allPerQuestion = new ArrayList<>();
        List<String> allStrengths = new ArrayList<>();
        List<InterviewEvaluationResponse.Suggestion> allImprovements = new ArrayList<>();
        int totalScore = 0;
        int batchCount = 0;

        for (String batchJson : batchResults) {
            try {
                InterviewEvaluationResponse batch = objectMapper.readValue(batchJson,
                        InterviewEvaluationResponse.class);
                totalScore += batch.getOverallScore();
                batchCount++;

                if (batch.getPerQuestion() != null) {
                    allPerQuestion.addAll(batch.getPerQuestion());
                }
                if (batch.getStrengths() != null) {
                    allStrengths.addAll(batch.getStrengths());
                }
                if (batch.getImprovements() != null) {
                    allImprovements.addAll(batch.getImprovements());
                }
            } catch (Exception e) {
                log.warn("降级合并时解析批次失败: {}", e.getMessage());
            }
        }

        InterviewEvaluationResponse result = new InterviewEvaluationResponse();
        result.setOverallScore(batchCount > 0 ? totalScore / batchCount : 0);
        result.setSummary("评估报告（降级模式）：共" + allPerQuestion.size() + "道题，平均分"
                + result.getOverallScore() + "。");
        result.setPerQuestion(allPerQuestion);
        result.setStrengths(allStrengths.stream().distinct().limit(5).collect(Collectors.toList()));
        result.setImprovements(allImprovements.stream().limit(5).collect(Collectors.toList()));

        log.info("降级合并完成: overallScore={}, questions={}", result.getOverallScore(), allPerQuestion.size());
        return result;
    }

    // ============================================================
    // 私有方法
    // ============================================================

    private List<Map<String, String>> buildQaList(List<InterviewAnswer> answers) {
        return answers.stream().map(a -> {
            Map<String, String> qa = new LinkedHashMap<>();
            qa.put("questionNumber", String.valueOf(a.getQuestionNumber()));
            qa.put("questionText", a.getQuestionText());
            qa.put("answerText", a.getAnswerText());
            return qa;
        }).collect(Collectors.toList());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON序列化失败", e);
            return "{}";
        }
    }

    private String loadTemplate(String path) {
        try {
            String cleanPath = path.replace("classpath:", "");
            return new ClassPathResource(cleanPath).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("加载 Prompt 模板失败: {}", path, e);
            return "";
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
