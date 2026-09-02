package com.agentstudio.model;

import java.util.List;
import java.util.function.Consumer;

import com.agentstudio.agent.AgentVersion;

public interface StreamingModelGateway {

    void stream(AgentVersion version, List<ModelMessage> messages, Consumer<String> onDelta) throws Exception;
}

