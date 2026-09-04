package com.agentstudio.tool;

import java.util.Map;

public record ToolDescriptor(
        String name,
        String displayName,
        String description,
        String source,
        String capability,
        String riskLevel,
        int timeoutSeconds,
        Map<String, Object> inputSchema) {
}
