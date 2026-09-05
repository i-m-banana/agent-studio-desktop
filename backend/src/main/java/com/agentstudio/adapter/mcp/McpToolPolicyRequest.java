package com.agentstudio.adapter.mcp;

public record McpToolPolicyRequest(boolean enabled, String capability, String riskLevel, int timeoutSeconds) {}
