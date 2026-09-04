package com.agentstudio.model;

import java.util.List;
import java.util.function.Consumer;

import com.agentstudio.agent.AgentVersion;
import com.agentstudio.tool.ToolDescriptor;

public interface StreamingModelGateway {

    void stream(AgentVersion version, List<ModelMessage> messages, Consumer<String> onDelta) throws Exception;

    ModelTurn complete(AgentVersion version, List<ReActMessage> messages,
                       List<ToolDescriptor> tools) throws Exception;
}
