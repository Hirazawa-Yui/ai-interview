package com.aiinterview.knowledge.service;

import com.aiinterview.knowledge.dto.KbListItemDTO;
import java.util.List;
import java.util.Map;

public interface IKnowledgeBaseService {
    Map<String, Object> upload(String fileKey, String kbName, String category);
    List<KbListItemDTO> list(String vectorStatus, String category, String sortBy);
    Map<String, Object> detail(Long id);
    void delete(Long id);
    void updateCategory(Long id, String category);
    void revectorize(Long id);
}
