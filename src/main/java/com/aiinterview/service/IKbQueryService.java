package com.aiinterview.service;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import java.util.List;

public interface IKbQueryService {
    /**
     * SSE流式RAG问答
     * @param kbIds   知识库ID列表
     * @param question 用户问题
     * @param history  历史消息（多轮对话场景，单次问答传空列表）
     */
    Flux<String> answerQuestionStream(List<Long> kbIds, String question, List<Message> history);
}
