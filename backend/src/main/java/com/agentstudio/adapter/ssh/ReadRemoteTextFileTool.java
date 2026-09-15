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
public class ReadRemoteTextFileTool implements AgentTool {
    private static final int MAX_LINES = 500;
    private static final int MAX_OUTPUT_CHARS = 16_000;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "read_remote_workspace_text_file", "读取 SSH 远程工作区文本",
            "通过固定主机指纹的 SFTP 连接分段读取授权远程工作区内不超过 1 MiB 的 UTF-8 文本；返回完整文件 SHA-256。",
            "SSH", "READ", "LOW", 20,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "远程工作区内文件相对路径"),
                    "startLine", Map.of("type", "integer", "description", "起始行，默认 1"),
                    "maxLines", Map.of("type", "integer", "description", "最多读取 1 到 500 行，默认 200")),
                    "required", List.of("path"), "additionalProperties", false));
    private final RemoteSftpWorkspace workspace;
    private final ObjectMapper objectMapper;
    public ReadRemoteTextFileTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace; this.objectMapper = objectMapper;
    }
    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + workspace.target(); }
    @Override public String execute(JsonNode arguments) throws Exception {
        var requested = arguments.path("path").asText("").trim();
        if (requested.isBlank()) throw new IllegalArgumentException("path 不能为空");
        var startLine = arguments.has("startLine") ? arguments.path("startLine").asInt() : 1;
        var maxLines = arguments.has("maxLines") ? arguments.path("maxLines").asInt() : 200;
        if (startLine < 1) throw new IllegalArgumentException("startLine 必须大于等于 1");
        if (maxLines < 1 || maxLines > MAX_LINES) throw new IllegalArgumentException("maxLines 必须在 1 到 500 之间");
        return workspace.execute(access -> {
            var file = access.requireFile(requested); var bytes = access.read(file);
            var decoded = RemoteSftpWorkspace.decodeUtf8(bytes);
            var text = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
            var lines = text.split("\\R", -1); var from = Math.min(startLine - 1, lines.length);
            var requestedEnd = Math.min(lines.length, from + maxLines); var content = new StringBuilder();
            var emitted = 0; var outputLimited = false;
            for (int index = from; index < requestedEnd; index++) {
                var separator = emitted == 0 ? "" : "\n";
                if (content.length() + separator.length() + lines[index].length() > MAX_OUTPUT_CHARS) {
                    outputLimited = true; break;
                }
                content.append(separator).append(lines[index]); emitted++;
            }
            var response = new LinkedHashMap<String, Object>(); response.put("target", workspace.target());
            response.put("path", access.relative(file)); response.put("sha256", RemoteSftpWorkspace.sha256(bytes));
            response.put("sizeBytes", bytes.length); response.put("startLine", startLine);
            response.put("endLine", emitted == 0 ? startLine - 1 : startLine + emitted - 1);
            response.put("totalLines", lines.length); response.put("content", content.toString());
            response.put("truncated", outputLimited || from + emitted < lines.length);
            return objectMapper.writeValueAsString(response);
        });
    }
}
