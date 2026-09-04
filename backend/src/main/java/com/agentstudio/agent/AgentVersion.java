package com.agentstudio.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AgentVersion(
        String id,
        String agentDefinitionId,
        int versionNumber,
        String knowledgeBaseId,
        String modelProfileId,
        String modelProfileName,
        String provider,
        String baseUrl,
        String modelName,
        String apiKeyEnv,
        BigDecimal temperature,
        String systemPrompt,
        List<String> toolNames,
        Instant publishedAt) {
}
