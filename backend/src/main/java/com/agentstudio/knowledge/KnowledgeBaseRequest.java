package com.agentstudio.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description) {
}

