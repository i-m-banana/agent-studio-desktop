package com.agentstudio.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTests {

    @Test
    void stopsWaitingWhenToolExceedsTimeout() {
        AgentTool slowTool = new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("slow", "slow", "test", "BUILTIN", "READ", "LOW", 0,
                        Map.of("type", "object"));
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) throws Exception {
                Thread.sleep(10_000);
                return "late";
            }
        };
        var registry = new ToolRegistry(List.of(slowTool), new ObjectMapper());

        assertThatThrownBy(() -> registry.execute("slow", "{}"))
                .hasMessageContaining("执行超时");
    }
}
