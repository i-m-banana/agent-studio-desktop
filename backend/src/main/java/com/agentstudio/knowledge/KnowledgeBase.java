package com.agentstudio.knowledge;

import java.time.Instant;

public record KnowledgeBase(String id, String name, String description,
                            Instant createdAt, Instant updatedAt) {
}

