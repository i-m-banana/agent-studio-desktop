package com.agentstudio.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {

    ToolDescriptor descriptor();

    default String targetEnvironment() { return "LOCAL"; }

    String execute(JsonNode arguments) throws Exception;
}
