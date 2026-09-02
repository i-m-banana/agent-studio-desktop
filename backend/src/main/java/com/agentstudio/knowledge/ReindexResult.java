package com.agentstudio.knowledge;

import java.util.List;

public record ReindexResult(
        String knowledgeBaseId,
        String embedding,
        int documentCount,
        int chunkCount,
        List<KnowledgeDocument> documents) {
}
