package com.agentstudio.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ModelProfile(
        String id,
        String name,
        String provider,
        String baseUrl,
        String modelName,
        String apiKeyEnv,
        BigDecimal temperature,
        Instant createdAt,
        Instant updatedAt) {
}

