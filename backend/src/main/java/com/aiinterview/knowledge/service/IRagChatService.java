package com.aiinterview.knowledge.service;

import com.aiinterview.knowledge.dto.RagMessageDTO;
import com.aiinterview.knowledge.dto.RagSessionDTO;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

public interface IRagChatService {
    RagSessionDTO createSession(List<Long> kbIds, String title);
    List<RagSessionDTO> listSessions();
    Map<String, Object> getSessionDetail(Long sessionId);
    void deleteSession(Long sessionId);

    /**
     * 重命名会话（T21）。
     * <p>只改标题，**不动 updatedAt**——该字段在本项目表示"最后一次对话时间"，同时是列表排序键，
     * 重命名不该让旧会话跳到列表顶部，也不该把「更新时间」列改成"刚刚"。
     *
     * @throws com.aiinterview.common.BusinessException 标题为空/超长，或会话不存在
     */
    void renameSession(Long sessionId, String title);

    Flux<String> sendMessage(Long sessionId, String question);
}
