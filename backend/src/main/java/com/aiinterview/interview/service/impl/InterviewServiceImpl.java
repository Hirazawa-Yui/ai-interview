package com.aiinterview.interview.service.impl;

import com.aiinterview.common.ai.PromptSecurityConstants;
import com.aiinterview.common.RedisKeys;
import com.aiinterview.common.TransactionSupport;
import com.aiinterview.interview.dto.InterviewQuestionDTO;
import com.aiinterview.interview.entity.InterviewAnswer;
import com.aiinterview.interview.entity.InterviewSession;
import com.aiinterview.resume.entity.Resume;
import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.interview.entity.InterviewEvaluation;
import com.aiinterview.interview.mapper.InterviewAnswerMapper;
import com.aiinterview.interview.mapper.InterviewEvaluationMapper;
import com.aiinterview.interview.mapper.InterviewSessionMapper;
import com.aiinterview.resume.mapper.ResumeMapper;
import com.aiinterview.common.ai.IPromptDefenseService;
import com.aiinterview.interview.service.IInterviewService;
import com.aiinterview.interview.listener.InterviewEvaluationProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 面试服务实现
 * <p>
 * 负责：创建面试(LLM出题) → 逐题作答 → 会话管理
 */
@Slf4j
@Service
public class InterviewServiceImpl implements IInterviewService {

    private final ChatClient chatClient;
    private final IPromptDefenseService defenseService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final InterviewEvaluationMapper evaluationMapper;
    private final ResumeMapper resumeMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final InterviewEvaluationProducer evaluationProducer;

    private final String questionSystemTemplate;
    private final String questionUserTemplate;

    private final int evaluationBatchSize;

    public InterviewServiceImpl(ChatClient chatClient,
                                IPromptDefenseService defenseService,
                                InterviewSessionMapper sessionMapper,
                                InterviewAnswerMapper answerMapper,
                                InterviewEvaluationMapper evaluationMapper,
                                ResumeMapper resumeMapper,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                InterviewEvaluationProducer evaluationProducer,
                                @Value("${app.interview.question-system-prompt:classpath:prompts/interview-question-system.st}") String sysPath,
                                @Value("${app.interview.question-user-prompt:classpath:prompts/interview-question-user.st}") String usrPath,
                                @Value("${app.interview.evaluation.batch-size:4}") int evaluationBatchSize) {
        this.chatClient = chatClient;
        this.defenseService = defenseService;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.evaluationMapper = evaluationMapper;
        this.resumeMapper = resumeMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.evaluationProducer = evaluationProducer;
        this.evaluationBatchSize = evaluationBatchSize;
        this.questionSystemTemplate = loadTemplate(sysPath);
        this.questionUserTemplate = loadTemplate(usrPath);
    }

    // ============================================================
    // 创建面试 + LLM出题
    // ============================================================

    @Override
    @Transactional
    public Map<String, Object> createSession(Long resumeId, String jdText,
                                             String direction, Integer questionCount) {
        // 1. 加载简历文本
        String resumeText = "";
        if (resumeId != null) {
            Resume resume = resumeMapper.selectById(resumeId);
            if (resume != null && resume.getParsedText() != null) {
                resumeText = resume.getParsedText();
                log.info("加载简历文本: resumeId={}, textLen={}", resumeId, resumeText.length());
            }
        }
        String resumeInfo = resumeText.isEmpty()
                ? "候选人未提供简历，请完全基于岗位JD出题"
                : resumeText;

        // 2. 构建 System Prompt：模板 + JSON Schema + Phase3防注入指令
        BeanOutputConverter<InterviewQuestionDTO> converter =
                new BeanOutputConverter<>(InterviewQuestionDTO.class);

        String systemPrompt = questionSystemTemplate
                .replace("{direction}", direction)
                .replace("{questionCount}", String.valueOf(questionCount))
                .replace("{resumeInfo}", resumeInfo)
                + "\n\n## 单题JSON格式\n" + converter.getFormat()
                + "\n**返回格式必须是 JSON 数组，如 [{...}, {...}]**"
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

        // 3. 净化并包裹用户输入
        String safeResume = defenseService.sanitizeAndWrap("resume", resumeInfo);
        String safeJd = defenseService.sanitizeAndWrap("jd", jdText);

        // 4. 构建 User Prompt
        String userPrompt = questionUserTemplate
                .replace("{direction}", direction)
                .replace("{questionCount}", String.valueOf(questionCount))
                .replace("{resumeText}", safeResume)
                .replace("{jdText}", safeJd);

        // 5. 调用 LLM 生成题目
        String llmResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        if (llmResponse == null || llmResponse.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 出题返回空响应");
        }

        // 6. Phase3 输出护栏
        defenseService.guardOutput(llmResponse);

        // 7. 解析 JSON 数组 → List<InterviewQuestionDTO>（先剥离可能出现的 json 围栏）
        List<InterviewQuestionDTO> questions;
        try {
            questions = parseQuestionList(defenseService.stripJsonFence(llmResponse));
        } catch (Exception e) {
            log.error("解析AI出题结果失败: {}", e.getMessage());
            log.debug("LLM原始返回: {}", llmResponse);
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "AI 返回格式异常，请稍后重试");
        }

        if (questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "AI 未生成任何题目");
        }

        // 题号归一化：LLM 偶发跳号/乱序，按数组顺序重写 1..N，保证"数组下标=题号-1"连续性假设成立
        renumberQuestions(questions);

        // 8. 入库
        String questionsJson;
        try {
            questionsJson = objectMapper.writeValueAsString(questions);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目序列化失败");
        }

        InterviewSession session = InterviewSession.builder()
                .resumeId(resumeId)
                .jdText(jdText)
                .direction(direction)
                .questionCount(questions.size())
                .currentQuestion(0)
                .status("IN_PROGRESS")
                .questionsJson(questionsJson)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sessionMapper.insert(session);

        // 9. 缓存题目到 Redis（TTL 2h）
        String cacheKey = RedisKeys.interviewQuestions(session.getId());
        redisTemplate.opsForValue().set(cacheKey, questionsJson, Duration.ofSeconds(RedisKeys.INTERVIEW_QUESTIONS_TTL));

        // 10. 构建返回结果
        InterviewQuestionDTO firstQuestion = questions.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("direction", direction);
        result.put("questionCount", questions.size());
        result.put("firstQuestion", firstQuestion);

        log.info("面试创建完成: sessionId={}, direction={}, questionCount={}",
                session.getId(), direction, questions.size());
        return result;
    }

    // ============================================================
    // 提交回答
    // ============================================================

    /**
     * 提交回答。DB 多步写入事务化（answer 入库 + 进度更新 + 答完置 COMPLETED 原子）；
     * 增量评估消息经事务提交后回调发出——否则消费者可能在事务提交前读到未提交的 answer
     * 而静默丢批次（跨模块坑 #8 同型，修复见 B3）。
     */
    @Override
    @Transactional
    public Map<String, Object> submitAnswer(Long sessionId, Integer questionNumber,
                                            String answerText) {
        return doSubmitAnswer(sessionId, questionNumber, answerText);
    }

    private Map<String, Object> doSubmitAnswer(Long sessionId, Integer questionNumber,
                                                String answerText) {
        // 1. 加载并校验会话
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED,
                    "当前状态不允许提交回答: " + session.getStatus());
        }

        // 2. 校验题号：只能回答下一题
        int expectedNumber = session.getCurrentQuestion() + 1;
        if (questionNumber != expectedNumber) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("请按顺序作答，当前应为第%d题，而非第%d题", expectedNumber, questionNumber));
        }

        // 3. 校验不重复回答
        Long answeredCount = answerMapper.selectCount(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .eq(InterviewAnswer::getQuestionNumber, questionNumber)
        );
        if (answeredCount > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该题已经回答过，不允许重复提交");
        }

        // 4. 加载题目文本
        List<InterviewQuestionDTO> questions = loadQuestionsFromCache(session);
        InterviewQuestionDTO currentQuestion = questions.stream()
                .filter(q -> q.getQuestionNumber() == questionNumber)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                        "题目#" + questionNumber + "不存在"));

        // 5. 保存回答
        InterviewAnswer answer = InterviewAnswer.builder()
                .sessionId(sessionId)
                .questionNumber(questionNumber)
                .questionText(currentQuestion.getQuestionText())
                .answerText(answerText)
                .createdAt(LocalDateTime.now())
                .build();
        answerMapper.insert(answer);

        // 6. 更新进度
        session.setCurrentQuestion(questionNumber);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        // 7. 增量评估：每攒够 batchSize 题或最后一题，事务提交后触发异步评估批次
        //    （afterCommit 保证消费者读到的 answer/进度必为已提交数据，见坑 #8）
        TransactionSupport.afterCommit(() -> triggerIncrementalEvaluationAfterCommit(sessionId));

        // 8. 判断是否答完
        if (questionNumber >= questions.size()) {
            // 全部答完
            session.setStatus("COMPLETED");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("allDone", true);
            result.put("message", "所有题目已完成，请提交评估");
            result.put("progress", questionNumber + "/" + questions.size());
            log.info("面试答题完成: sessionId={}", sessionId);
            return result;
        }

        // 8. 返回下一题
        InterviewQuestionDTO nextQuestion = questions.get(questionNumber); // 数组索引 = 题号
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nextQuestion", nextQuestion);
        result.put("progress", questionNumber + "/" + questions.size());
        log.info("提交回答: sessionId={}, question={}/{}, next={}",
                sessionId, questionNumber, questions.size(), questionNumber + 1);
        return result;
    }

    // ============================================================
    // 历史列表
    // ============================================================

    @Override
    public List<Map<String, Object>> listSessions() {
        List<InterviewSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<InterviewSession>()
                        .orderByDesc(InterviewSession::getCreatedAt)
        );

        return sessions.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("direction", s.getDirection());
            item.put("questionCount", s.getQuestionCount());
            item.put("currentQuestion", s.getCurrentQuestion());
            item.put("status", s.getStatus());
            item.put("createdAt", s.getCreatedAt());
            item.put("updatedAt", s.getUpdatedAt());
            return item;
        }).collect(Collectors.toList());
    }

    // ============================================================
    // 会话详情
    // ============================================================

    @Override
    public Map<String, Object> getSessionDetail(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        // 加载回答列表
        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .orderByAsc(InterviewAnswer::getQuestionNumber)
        );

        // 解析题目列表
        List<InterviewQuestionDTO> questions = loadQuestionsFromCache(session);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", session.getId());
        detail.put("resumeId", session.getResumeId());
        detail.put("jdText", session.getJdText());
        detail.put("direction", session.getDirection());
        detail.put("questionCount", session.getQuestionCount());
        detail.put("currentQuestion", session.getCurrentQuestion());
        detail.put("status", session.getStatus());
        detail.put("evaluateError", session.getEvaluateError());
        detail.put("questions", questions);
        detail.put("answers", answers);
        detail.put("createdAt", session.getCreatedAt());
        detail.put("updatedAt", session.getUpdatedAt());
        return detail;
    }

    // ============================================================
    // 删除
    // ============================================================

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        // 级联删除答案
        answerMapper.delete(new LambdaQueryWrapper<InterviewAnswer>()
                .eq(InterviewAnswer::getSessionId, sessionId));

        // 级联删除评估记录
        evaluationMapper.delete(new LambdaQueryWrapper<InterviewEvaluation>()
                .eq(InterviewEvaluation::getSessionId, sessionId));

        // 删除会话
        sessionMapper.deleteById(sessionId);

        // 清理 Redis 缓存
        String cacheKey = RedisKeys.interviewQuestions(sessionId);
        redisTemplate.delete(cacheKey);

        log.info("面试会话删除完成: sessionId={}", sessionId);
    }

    // ============================================================
    // 触发评估
    // ============================================================

    @Override
    public Map<String, Object> triggerEvaluation(Long sessionId) {
        // 1. 校验会话存在
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        // 2. 检查是否已提交过评估
        if ("EVALUATING".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED,
                    "评估正在进行中，请等待完成");
        }
        if ("EVALUATED".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED,
                    "该面试已完成评估，请勿重复提交");
        }

        // 3. 检查答题是否完成
        if (!"COMPLETED".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED,
                    "请先完成所有题目再提交评估");
        }

        // 4. 更新状态为 EVALUATING
        session.setStatus("EVALUATING");
        session.setEvaluateError(null);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        // 5. 发送汇总任务到 Stream（由消费者线程收集批次 + 1次 LLM 汇总；替代原裸 new Thread，
        //    应用重启后由 InterviewRecoveryRunner 扫描补发，避免会话永久卡 EVALUATING）
        evaluationProducer.sendSummarizeTask(sessionId);

        log.info("评估汇总已触发: sessionId={}", sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evaluateStatus", "EVALUATING");
        result.put("message", "评估已提交，AI正在汇总分析中...");
        return result;
    }

    // ============================================================
    // 获取评估结果
    // ============================================================

    @Override
    public Map<String, Object> getEvaluation(Long sessionId) {
        // 1. 校验会话存在
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        // 2. 查最新评估结果
        InterviewEvaluation evaluation = evaluationMapper.selectOne(
                new LambdaQueryWrapper<InterviewEvaluation>()
                        .eq(InterviewEvaluation::getSessionId, sessionId)
                        .orderByDesc(InterviewEvaluation::getEvaluatedAt)
                        .last("LIMIT 1")
        );

        // 3. 构建返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("sessionStatus", session.getStatus());

        if (evaluation != null) {
            result.put("overallScore", evaluation.getOverallScore());
            result.put("summary", evaluation.getSummary());
            result.put("perQuestion", parseJsonArray(evaluation.getPerQuestionJson()));
            result.put("strengths", parseJsonArray(evaluation.getStrengthsJson()));
            result.put("improvements", parseJsonArray(evaluation.getImprovementsJson()));
            result.put("evaluatedAt", evaluation.getEvaluatedAt());
        }

        result.put("evaluateStatus", session.getStatus());
        if ("FAILED".equals(session.getStatus())) {
            result.put("error", session.getEvaluateError());
        }

        return result;
    }

    // ============================================================
    // 私有方法 — 新增
    // ============================================================

    /** 解析 JSON 字符串为 List */
    private List<Object> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("JSON解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ============================================================
    // 私有方法
    // ============================================================

    /** 从 Redis 缓存或 DB 加载题目列表 */
    private List<InterviewQuestionDTO> loadQuestionsFromCache(InterviewSession session) {
        String cacheKey = RedisKeys.interviewQuestions(session.getId());
        String json = redisTemplate.opsForValue().get(cacheKey);

        if (json == null) {
            // 缓存未命中，从 DB 读取并回写缓存
            json = session.getQuestionsJson();
            if (json != null) {
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(RedisKeys.INTERVIEW_QUESTIONS_TTL));
            }
        }

        if (json == null) {
            return Collections.emptyList();
        }
        return parseQuestionList(json);
    }

    /** 解析题目 JSON 数组 */
    private List<InterviewQuestionDTO> parseQuestionList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<InterviewQuestionDTO>>() {});
        } catch (Exception e) {
            log.error("解析题目JSON失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 题号归一化：LLM 偶发跳号/乱序/非 1 起始，按数组顺序重写 questionNumber=1..N */
    private void renumberQuestions(List<InterviewQuestionDTO> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        for (int i = 0; i < questions.size(); i++) {
            int expected = i + 1;
            if (questions.get(i).getQuestionNumber() != expected) {
                if (i == 0) {
                    log.info("AI 出题题号非连续/乱序，归一化为 1..{}", questions.size());
                }
                questions.get(i).setQuestionNumber(expected);
            }
        }
    }

    /**
     * 增量评估触发（事务提交后执行）：重新读取已提交的会话进度，
     * 每攒够 batchSize 题或最后一题则发送异步评估批次到 Stream
     */
    private void triggerIncrementalEvaluationAfterCommit(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        String status = session.getStatus();
        if (!"IN_PROGRESS".equals(status) && !"COMPLETED".equals(status)) {
            return; // 会话已结束或异常，不再触发增量批次
        }
        int currentQ = session.getCurrentQuestion();
        List<InterviewQuestionDTO> questions = loadQuestionsFromCache(session);
        int totalQuestions = questions.size();
        int batchSize = this.evaluationBatchSize;

        // 条件1：每攒够 batchSize 题，或这是最后一题
        boolean shouldTrigger = (currentQ % batchSize == 0) || (currentQ == totalQuestions);

        if (!shouldTrigger) return;

        // 确定批次号和题目范围
        int batchNumber = (currentQ - 1) / batchSize + 1;
        int qStart = (batchNumber - 1) * batchSize + 1;
        int qEnd = Math.min(batchNumber * batchSize, totalQuestions);

        // 条件2：用 Redis SETNX 防重（锁 key 含批次号，防快速连答时跨批次互斥，模块05 坑#5）
        String lockKey = RedisKeys.interviewBatchLock(session.getId(), batchNumber);
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, String.valueOf(batchNumber), Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("增量批次已锁定: sessionId={}, batch={}", session.getId(), batchNumber);
            return;
        }

        // 条件3：检查批次是否已完成
        String doneKey = RedisKeys.interviewBatchDone(session.getId());
        Boolean alreadyDone = redisTemplate.opsForSet().isMember(doneKey, String.valueOf(batchNumber));
        if (Boolean.TRUE.equals(alreadyDone)) {
            redisTemplate.delete(lockKey);
            log.debug("增量批次已完成: sessionId={}, batch={}", session.getId(), batchNumber);
            return;
        }

        // 发送增量批次评估任务到 Stream
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sessionId", String.valueOf(session.getId()));
        body.put("batchNumber", String.valueOf(batchNumber));
        body.put("qStart", String.valueOf(qStart));
        body.put("qEnd", String.valueOf(qEnd));
        body.put("retryCount", "0");

        var record = org.springframework.data.redis.connection.stream.StreamRecords
                .mapBacked(body)
                .withStreamKey(com.aiinterview.common.StreamKeys.INTERVIEW_EVALUATE_STREAM);
        redisTemplate.opsForStream().add(record);

        log.info("增量评估批次已发送: sessionId={}, batch={}/{}, range={}-{}",
                session.getId(), batchNumber,
                (totalQuestions + batchSize - 1) / batchSize, qStart, qEnd);
    }

    /** 加载 Prompt 模板文件 */
    private String loadTemplate(String path) {
        try {
            String cleanPath = path.replace("classpath:", "");
            return new ClassPathResource(cleanPath).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("加载 Prompt 模板失败: {}", path, e);
            return "";
        }
    }
}
