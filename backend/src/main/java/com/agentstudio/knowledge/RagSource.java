package com.agentstudio.knowledge;

public record RagSource(String documentId, String fileName, int chunkIndex,
                        String content, double score) {
}

