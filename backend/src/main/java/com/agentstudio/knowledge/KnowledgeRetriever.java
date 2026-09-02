package com.agentstudio.knowledge;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetriever {

    private final ObjectProvider<VectorChunkRepository> vectors;

    public KnowledgeRetriever(ObjectProvider<VectorChunkRepository> vectors) {
        this.vectors = vectors;
    }

    public List<RagSource> retrieve(String knowledgeBaseId, String query) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) return List.of();
        var store = vectors.getIfAvailable();
        if (store == null) return List.of();
        return store.search(knowledgeBaseId, query, 5).stream()
                .filter(source -> source.score() > 0.05)
                .toList();
    }
}
