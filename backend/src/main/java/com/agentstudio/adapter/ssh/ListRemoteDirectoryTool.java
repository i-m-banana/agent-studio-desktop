package com.agentstudio.adapter.ssh;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ListRemoteDirectoryTool implements AgentTool {
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "list_remote_workspace_directory", "浏览 SSH 远程工作区目录",
            "通过固定主机指纹的 SFTP 连接列出授权远程根目录内的直接子项；不跟随符号链接或返回密钥路径。",
            "SSH", "READ", "LOW", 20,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "远程工作区相对目录；默认根目录"),
                    "maxEntries", Map.of("type", "integer", "description", "最多返回 1 到 200 项；默认 100")),
                    "required", List.of(), "additionalProperties", false));
    private final RemoteSftpWorkspace workspace;
    private final ObjectMapper objectMapper;
    public ListRemoteDirectoryTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace; this.objectMapper = objectMapper;
    }
    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + workspace.target(); }
    @Override public String execute(JsonNode arguments) throws Exception {
        var limit = arguments.has("maxEntries") ? arguments.path("maxEntries").asInt() : 100;
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("maxEntries 必须在 1 到 200 之间");
        return workspace.execute(access -> {
            var directory = access.requireDirectory(arguments.path("path").asText("."));
            var all = access.list(directory);
            var response = new LinkedHashMap<String, Object>();
            response.put("target", workspace.target());
            response.put("path", access.relative(directory));
            response.put("entries", all.stream().limit(limit).map(entry -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("name", entry.name()); item.put("path", entry.path()); item.put("type", entry.type());
                if (entry.type().equals("FILE")) item.put("sizeBytes", entry.sizeBytes());
                return item;
            }).toList());
            response.put("truncated", all.size() > limit);
            return objectMapper.writeValueAsString(response);
        });
    }
}
