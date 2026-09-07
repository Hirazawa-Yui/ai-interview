package com.aiinterview.controller;

import com.aiinterview.dto.InterviewAnswerRequest;
import com.aiinterview.dto.InterviewCreateRequest;
import com.aiinterview.dto.Result;
import com.aiinterview.service.IInterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模拟面试控制器
 * <p>
 * Phase 6a：创建面试 + 逐题作答 + 会话管理（5端点）
 * Phase 6b：异步评估（追加2端点）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、逐题作答、异步评估")
public class InterviewController {

    private final IInterviewService interviewService;

    // ==================== Phase 6a 端点 ====================

    @Operation(summary = "创建面试会话", description = "AI根据简历+JD+方向一次性生成所有题目，缓存到Redis")
    @PostMapping("/api/interviews/sessions")
    public Result<Map<String, Object>> createSession(@Valid @RequestBody InterviewCreateRequest req) {
        log.info("创建面试: resumeId={}, direction={}, questionCount={}",
                req.getResumeId(), req.getDirection(), req.getQuestionCount());
        return Result.ok(interviewService.createSession(
                req.getResumeId(), req.getJdText(), req.getDirection(), req.getQuestionCount()));
    }

    @Operation(summary = "面试历史列表")
    @GetMapping("/api/interviews/sessions")
    public Result<List<Map<String, Object>>> listSessions() {
        log.info("面试历史列表");
        return Result.ok(interviewService.listSessions());
    }

    @Operation(summary = "会话详情", description = "含题目列表和已答记录")
    @GetMapping("/api/interviews/sessions/{id}")
    public Result<Map<String, Object>> getSessionDetail(
            @Parameter(description = "会话ID") @PathVariable Long id) {
        log.info("面试会话详情: id={}", id);
        return Result.ok(interviewService.getSessionDetail(id));
    }

    @Operation(summary = "删除面试会话", description = "级联删除关联的答案记录")
    @DeleteMapping("/api/interviews/sessions/{id}")
    public Result<String> deleteSession(
            @Parameter(description = "会话ID") @PathVariable Long id) {
        log.info("删除面试会话: id={}", id);
        interviewService.deleteSession(id);
        return Result.ok("删除成功");
    }

    @Operation(summary = "提交回答", description = "提交当前题的回答，返回下一题或完成信号")
    @PostMapping("/api/interviews/sessions/{id}/answers")
    public Result<Map<String, Object>> submitAnswer(
            @Parameter(description = "会话ID") @PathVariable Long id,
            @Valid @RequestBody InterviewAnswerRequest req) {
        log.info("提交回答: sessionId={}, question={}", id, req.getQuestionNumber());
        return Result.ok(interviewService.submitAnswer(id, req.getQuestionNumber(), req.getAnswerText()));
    }

    // ==================== Phase 6b 端点（待实现） ====================

    @Operation(summary = "提交评估", description = "触发异步评估（Phase 6b实现）")
    @PostMapping("/api/interviews/sessions/{id}/evaluate")
    public Result<Map<String, Object>> evaluate(
            @Parameter(description = "会话ID") @PathVariable Long id) {
        log.info("触发评估: sessionId={}", id);
        return Result.ok(interviewService.triggerEvaluation(id));
    }

    @Operation(summary = "获取评估结果", description = "轮询评估结果（Phase 6b实现）")
    @GetMapping("/api/interviews/sessions/{id}/evaluation")
    public Result<Map<String, Object>> getEvaluation(
            @Parameter(description = "会话ID") @PathVariable Long id) {
        log.info("查询评估结果: sessionId={}", id);
        return Result.ok(interviewService.getEvaluation(id));
    }
}
