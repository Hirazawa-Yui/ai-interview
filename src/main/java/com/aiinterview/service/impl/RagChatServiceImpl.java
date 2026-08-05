package com.aiinterview.service.impl;

import com.aiinterview.dto.RagSessionDTO;
import com.aiinterview.entity.KnowledgeBase;
import com.aiinterview.entity.RagChatMessage;
import com.aiinterview.entity.RagChatSession;
import com.aiinterview.exception.BusinessException;
import com.aiinterview.exception.ErrorCode;
import com.aiinterview.mapper.KnowledgeBaseMapper;
import com.aiinterview.mapper.RagChatMessageMapper;
import com.aiinterview.mapper.RagChatSessionMapper;
import com.aiinterview.service.IKbQueryService;
import com.aiinterview.service.IRagChatService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatServiceImpl implements IRagChatService {

    private final RagChatSessionMapper sessionMapper;
    private final RagChatMessageMapper messageMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final IKbQueryService queryService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public RagSessionDTO createSession(List<Long> kbIds, String title) {
        // 验证KB存在
        for (Long kbId : kbIds) {
            if (kbMapper.selectById(kbId) == null)
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库 " + kbId + " 不存在");
        }
        try {
            RagChatSession session = RagChatSession.builder()
                    .sessionTitle(title != null ? title : (kbIds.size() + " 个知识库对话"))
                    .kbIds(objectMapper.writeValueAsString(kbIds))
                    .isPinned(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            sessionMapper.insert(session);
            return toDTO(session);
        } catch (Exception e) {
            throw new RuntimeException("创建会话失败", e);
        }
    }

    @Override
    public List<RagSessionDTO> listSessions() {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<RagChatSession>()
                        .orderByDesc(RagChatSession::getIsPinned)
                        .orderByDesc(RagChatSession::getUpdatedAt)
        ).stream().map(this::toDTO).toList();
    }

    @Override
    public Map<String, Object> getSessionDetail(Long sessionId) {
        RagChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "会话不存在");
        List<RagChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<RagChatMessage>()
                        .eq(RagChatMessage::getSessionId, sessionId)
                        .orderByAsc(RagChatMessage::getMessageOrder)
        );
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", session.getId());
        detail.put("sessionTitle", session.getSessionTitle());
        detail.put("kbIds", parseKbIds(session.getKbIds()));
        detail.put("isPinned", session.getIsPinned());
        detail.put("createdAt", session.getCreatedAt());
        detail.put("messages", messages);
        return detail;
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        messageMapper.delete(new LambdaQueryWrapper<RagChatMessage>()
                .eq(RagChatMessage::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
    }

    @Override
    @Transactional
    public Flux<String> sendMessage(Long sessionId, String question) {
        RagChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "会话不存在");
        List<Long> kbIds = parseKbIds(session.getKbIds());

        // 1. 存用户消息
        int nextOrder = getNextOrder(sessionId);
        RagChatMessage userMsg = RagChatMessage.builder()
                .sessionId(sessionId).role("user").content(question)
                .messageOrder(nextOrder).completed(true).createdAt(LocalDateTime.now()).build();
        messageMapper.insert(userMsg);

        // 2. AI 占位消息
        int aiOrder = nextOrder + 1;
        RagChatMessage aiMsg = RagChatMessage.builder()
                .sessionId(sessionId).role("assistant").content("")
                .messageOrder(aiOrder).completed(false).createdAt(LocalDateTime.now()).build();
        messageMapper.insert(aiMsg);

        // 3. 更新会话时间
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        // 4. 加载历史消息
        List<Message> history = loadHistory(sessionId, aiOrder);

        // 5. 流式调用 + 累积写入
        AtomicReference<String> accumulated = new AtomicReference<>("");
        return queryService.answerQuestionStream(kbIds, question, history)
                .doOnNext(chunk -> accumulated.updateAndGet(s -> s + chunk))
                .doOnComplete(() -> {
                    aiMsg.setContent(accumulated.get());
                    aiMsg.setCompleted(true);
                    messageMapper.updateById(aiMsg);
                })
                .doOnError(err -> {
                    aiMsg.setContent(accumulated.get() + "\n\n[回答中断: " + err.getMessage() + "]");
                    aiMsg.setCompleted(true);
                    messageMapper.updateById(aiMsg);
                });
    }

    private List<Message> loadHistory(Long sessionId, int currentAiOrder) {
        // 查最近消息（不含当前轮次的AI占位），倒序取前10条
        List<RagChatMessage> msgs = messageMapper.selectList(
                new LambdaQueryWrapper<RagChatMessage>()
                        .eq(RagChatMessage::getSessionId, sessionId)
                        .lt(RagChatMessage::getMessageOrder, currentAiOrder) // 当前轮的user + 之前的
                        .orderByDesc(RagChatMessage::getMessageOrder)
                        .last("LIMIT 11") // 多取一条（当前轮的user），后面过滤掉
        );
        // 去掉第一条（当前轮次的user消息，不在历史中）
        if (!msgs.isEmpty() && "user".equals(msgs.get(0).getRole())) {
            msgs = msgs.subList(1, msgs.size());
        }
        // 限制10条 + 翻回时间正序
        if (msgs.size() > 10) msgs = msgs.subList(0, 10);
        Collections.reverse(msgs);

        return msgs.stream().map(m -> {
            if ("assistant".equals(m.getRole())) return new AssistantMessage(m.getContent());
            return new UserMessage(m.getContent());
        }).map(m -> (Message) m).toList();
    }

    private int getNextOrder(Long sessionId) {
        var wrapper = new LambdaQueryWrapper<RagChatMessage>()
                .eq(RagChatMessage::getSessionId, sessionId)
                .orderByDesc(RagChatMessage::getMessageOrder)
                .last("LIMIT 1");
        RagChatMessage last = messageMapper.selectOne(wrapper);
        return last == null ? 0 : last.getMessageOrder() + 1;
    }

    private RagSessionDTO toDTO(RagChatSession s) {
        return RagSessionDTO.builder()
                .id(s.getId()).sessionTitle(s.getSessionTitle())
                .kbIds(parseKbIds(s.getKbIds())).isPinned(s.getIsPinned())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt()).build();
    }

    private List<Long> parseKbIds(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<Long>>() {}); }
        catch (Exception e) { return List.of(); }
    }
}
