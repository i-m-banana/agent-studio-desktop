package com.agentstudio.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.agentstudio.system.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> registeredTools, ObjectMapper objectMapper) {
        var indexed = new LinkedHashMap<String, AgentTool>();
        for (var tool : registeredTools) {
            var previous = indexed.put(tool.descriptor().name(), tool);
            if (previous != null) throw new IllegalStateException("工具名称重复：" + tool.descriptor().name());
        }
        this.tools = Map.copyOf(indexed);
        this.objectMapper = objectMapper;
    }

    public List<ToolDescriptor> descriptors() {
        return tools.values().stream().map(AgentTool::descriptor).toList();
    }

    public List<ToolDescriptor> descriptors(List<String> names) {
        return names.stream().map(this::require).map(AgentTool::descriptor).toList();
    }

    public void validateNames(List<String> names) {
        names.forEach(this::require);
    }

    public ToolExecutionResult execute(String name, String argumentsJson) throws Exception {
        var tool = require(name);
        var arguments = objectMapper.readTree(
                argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        if (!arguments.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        long started = System.nanoTime();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = executor.submit(() -> tool.execute(arguments));
            try {
                var output = future.get(tool.descriptor().timeoutSeconds(), TimeUnit.SECONDS);
                return new ToolExecutionResult(output, Duration.ofNanos(System.nanoTime() - started).toMillis());
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new IllegalStateException("工具 " + name + " 执行超时", exception);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private AgentTool require(String name) {
        var tool = tools.get(name);
        if (tool == null) throw new ApiException(HttpStatus.BAD_REQUEST, "未知工具：" + name);
        return tool;
    }
}
