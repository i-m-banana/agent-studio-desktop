package com.agentstudio.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {

    ToolDescriptor descriptor();

    String execute(JsonNode arguments) throws Exception;
}
