package com.agentstudio.knowledge;

public record KnowledgeChunk(String id, String knowledgeBaseId, String documentId,
                             int chunkIndex, String content, String fileName) {
}

