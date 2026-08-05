package com.aiinterview.service;

import com.aiinterview.dto.RagMessageDTO;
import com.aiinterview.dto.RagSessionDTO;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

public interface IRagChatService {
    RagSessionDTO createSession(List<Long> kbIds, String title);
    List<RagSessionDTO> listSessions();
    Map<String, Object> getSessionDetail(Long sessionId);
    void deleteSession(Long sessionId);
    Flux<String> sendMessage(Long sessionId, String question);
}
