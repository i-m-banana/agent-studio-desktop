package com.agentstudio.knowledge;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetriever {

    private final ObjectProvider<VectorChunkRepository> vectors;
    private final int topK;
    private final double scoreThreshold;

    public KnowledgeRetriever(ObjectProvider<VectorChunkRepository> vectors,
                              @Value("${agent-studio.retrieval.top-k:5}") int topK,
                              @Value("${agent-studio.retrieval.score-threshold:0.05}") double scoreThreshold) {
        this.vectors = vectors;
        this.topK = topK;
        this.scoreThreshold = scoreThreshold;
    }

    public List<RagSource> retrieve(String knowledgeBaseId, String query) throws Exception {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) return List.of();
        var store = vectors.getIfAvailable();
        if (store == null) return List.of();
        return store.search(knowledgeBaseId, query, topK).stream()
                .filter(source -> source.score() > scoreThreshold)
                .toList();
    }
}
