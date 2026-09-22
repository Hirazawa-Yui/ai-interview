package com.aiinterview.knowledge.controller;

import com.aiinterview.knowledge.dto.KbQueryRequest;
import com.aiinterview.knowledge.dto.RagRenameSessionRequest;
import com.aiinterview.knowledge.dto.RagSessionDTO;
import com.aiinterview.common.Result;
import com.aiinterview.knowledge.service.IKbQueryService;
import com.aiinterview.knowledge.service.IRagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG问答", description = "SSE流式问答 + 多轮对话会话管理")
public class RagChatController {

    private final IKbQueryService queryService;
    private final IRagChatService chatService;

    @Operation(summary = "SSE流式问答", description = "单次RAG问答，返回text/event-stream流")
    @PostMapping(value = "/api/knowledge/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(@RequestBody KbQueryRequest req) {
        log.info("SSE单次问答: kbIds={}, question={}", req.getKnowledgeBaseIds(), req.getQuestion());
        return queryService.answerQuestionStream(req.getKnowledgeBaseIds(), req.getQuestion(), List.of());
    }

    @Operation(summary = "创建聊天会话")
    @PostMapping("/api/rag-chat/sessions")
    public Result<RagSessionDTO> createSession(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> kbIds = ((List<Integer>) body.get("kbIds")).stream().map(Long::valueOf).toList();
        String title = (String) body.getOrDefault("title", null);
        log.info("创建RAG会话: kbIds={}, title={}", kbIds, title);
        return Result.ok(chatService.createSession(kbIds, title));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<RagSessionDTO>> listSessions() {
        log.info("RAG会话列表");
        return Result.ok(chatService.listSessions());
    }

    @Operation(summary = "会话详情")
    @GetMapping("/api/rag-chat/sessions/{id}")
    public Result<Map<String, Object>> getSession(@PathVariable Long id) {
        log.info("RAG会话详情: id={}", id);
        return Result.ok(chatService.getSessionDetail(id));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/api/rag-chat/sessions/{id}")
    public Result<String> deleteSession(@PathVariable Long id) {
        log.info("RAG会话删除: id={}", id);
        chatService.deleteSession(id);
        return Result.ok("删除成功");
    }

    @Operation(summary = "会话重命名", description = "仅修改标题；不改变 updatedAt，列表排序与「更新时间」列不受影响")
    @PutMapping("/api/rag-chat/sessions/{id}/title")
    public Result<String> renameSession(@PathVariable Long id,
                                        @Valid @RequestBody RagRenameSessionRequest req) {
        log.info("RAG会话重命名: id={}, title={}", id, req.getTitle());
        chatService.renameSession(id, req.getTitle());
        return Result.ok("标题已更新");
    }

    @Operation(summary = "SSE多轮对话", description = "在已有会话中发送消息，返回流式回答")
    @PostMapping(value = "/api/rag-chat/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> sendMessage(@PathVariable Long id, @RequestBody Map<String, String> body) {
        log.info("SSE多轮对话: sessionId={}, question={}", id, body.get("question"));
        return chatService.sendMessage(id, body.get("question"));
    }
}
