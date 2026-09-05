package com.agentstudio.adapter.mcp;

public record McpRemoteResource(String uri, String name, String title, String description,
                                String mimeType, Long size) {}
