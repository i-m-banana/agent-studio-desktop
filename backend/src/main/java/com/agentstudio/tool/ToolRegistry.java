package com.agentstudio.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.agentstudio.adapter.mcp.McpService;
import com.agentstudio.system.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;
    private final McpService mcp;

    @Autowired
    public ToolRegistry(List<AgentTool> registeredTools, ObjectMapper objectMapper, McpService mcp) {
        var indexed = new LinkedHashMap<String, AgentTool>();
        for (var tool : registeredTools) {
            var previous = indexed.put(tool.descriptor().name(), tool);
            if (previous != null) throw new IllegalStateException("工具名称重复：" + tool.descriptor().name());
        }
        this.tools = Map.copyOf(indexed);
        this.objectMapper = objectMapper;
        this.mcp = mcp;
    }

    public ToolRegistry(List<AgentTool> registeredTools, ObjectMapper objectMapper) {
        this(registeredTools, objectMapper, null);
    }

    public List<ToolDescriptor> descriptors() {
        var descriptors = new java.util.ArrayList<ToolDescriptor>();
        descriptors.addAll(tools.values().stream().map(AgentTool::descriptor).toList());
        if (mcp != null) descriptors.addAll(mcp.activeDescriptors());
        return List.copyOf(descriptors);
    }

    public List<ToolDescriptor> descriptors(List<String> names) {
        return names.stream().map(this::require).map(AgentTool::descriptor).toList();
    }

    public ToolDescriptor descriptor(String name) {
        return require(name).descriptor();
    }

    public String targetEnvironment(String name) {
        var builtIn = tools.get(name);
        if (builtIn != null) return builtIn.targetEnvironment();
        return mcp != null && name.startsWith("mcp_") ? mcp.targetEnvironment(name) : "LOCAL";
    }

    public void validateNames(List<String> names) {
        for (var name : names) {
            if (tools.containsKey(name)) continue;
            if (mcp != null && name.startsWith("mcp_")) mcp.validateAvailable(name);
            else require(name);
        }
    }

    public ToolExecutionResult execute(String name, String argumentsJson) throws Exception {
        var arguments = objectMapper.readTree(
                argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        if (!arguments.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        var descriptor = descriptor(name);
        long started = System.nanoTime();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var project = com.agentstudio.project.ProjectExecutionContext.current();
        var sourceSha = com.agentstudio.project.ProjectExecutionContext.sourceSha256();
        try {
            var future = executor.submit(() -> {
                try (var context = com.agentstudio.project.ProjectExecutionContext.enter(project,sourceSha)) {
                var builtIn = tools.get(name);
                if (builtIn != null) return builtIn.execute(arguments);
                if (mcp != null && name.startsWith("mcp_")) return mcp.execute(name, arguments);
                throw new ApiException(HttpStatus.BAD_REQUEST, "未知工具：" + name);
                }
            });
            try {
                var output = future.get(descriptor.timeoutSeconds(), TimeUnit.SECONDS);
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
        if (tool == null && mcp != null && name.startsWith("mcp_")) {
            var remote = mcp.requireTool(name);
            return new AgentTool() {
                @Override public ToolDescriptor descriptor() { return remote.descriptor(); }
                @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) throws Exception {
                    return mcp.execute(name, arguments);
                }
            };
        }
        if (tool == null) throw new ApiException(HttpStatus.BAD_REQUEST, "未知工具：" + name);
        return tool;
    }
}
