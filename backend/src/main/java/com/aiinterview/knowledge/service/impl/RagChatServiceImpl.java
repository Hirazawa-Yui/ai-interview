package com.aiinterview.knowledge.service.impl;

import com.aiinterview.common.StageWatch;
import com.aiinterview.knowledge.dto.RagSessionDTO;
import com.aiinterview.knowledge.entity.KnowledgeBase;
import com.aiinterview.knowledge.entity.RagChatMessage;
import com.aiinterview.knowledge.entity.RagChatSession;
import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.knowledge.mapper.KnowledgeBaseMapper;
import com.aiinterview.knowledge.mapper.RagChatMessageMapper;
import com.aiinterview.knowledge.mapper.RagChatSessionMapper;
import com.aiinterview.knowledge.service.IKbQueryService;
import com.aiinterview.knowledge.service.IRagChatService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    /** 标题产品上限（T21）；DB 列是 VARCHAR(200)，见 RagRenameSessionRequest 注释 */
    private static final int MAX_TITLE_LENGTH = 50;

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
    public void renameSession(Long sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能为空");
        }
        String newTitle = title.trim();
        if (newTitle.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        if (sessionMapper.selectById(sessionId) == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "会话不存在");
        }
        // 只 SET 标题一列：不能用 updateById —— 本仓库没有 MetaObjectHandler，
        // 它会把读到的整行快照（含 updatedAt）写回，与并发的 sendMessage 抢写，
        // 可能把"最后对话时间"改小导致列表排序倒退。
        sessionMapper.update(null, new LambdaUpdateWrapper<RagChatSession>()
                .set(RagChatSession::getSessionTitle, newTitle)
                .eq(RagChatSession::getId, sessionId));
        log.info("会话重命名: id={}, title={}", sessionId, newTitle);
    }

    @Override
    public void updateSessionKbs(Long sessionId, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个知识库");
        }
        if (sessionMapper.selectById(sessionId) == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "会话不存在");
        }
        for (Long kbId : kbIds) {
            if (kbMapper.selectById(kbId) == null) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库 " + kbId + " 不存在");
            }
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(kbIds);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库列表格式错误");
        }
        // 只 SET kb_ids 一列，理由同 renameSession：不碰 updatedAt
        sessionMapper.update(null, new LambdaUpdateWrapper<RagChatSession>()
                .set(RagChatSession::getKbIds, json)
                .eq(RagChatSession::getId, sessionId));
        log.info("会话更换知识库: id={}, kbIds={}", sessionId, kbIds);
    }

    @Override
    @Transactional
    public Flux<String> sendMessage(Long sessionId, String question) {
        // T17：多轮链路前置阶段的耗时（在 answerQuestionStream 的 [RAG耗时] 之前发生）
        StageWatch watch = new StageWatch();
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
        watch.mark("db");

        // 4. 加载历史消息
        List<Message> history = loadHistory(sessionId, aiOrder);
        log.info("[RAG耗时-多轮前置] {} | sessionId={} 历史消息={}",
                watch, sessionId, history.size());

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
