package com.agentstudio.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
        String id, String knowledgeBaseId, String fileName, String mediaType,
        long fileSize, String sha256, String storedPath, String status,
        int chunkCount, String errorMessage, Instant createdAt, Instant updatedAt) {
}

