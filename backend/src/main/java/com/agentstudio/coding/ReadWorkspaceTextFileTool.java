package com.agentstudio.coding;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ReadWorkspaceTextFileTool implements AgentTool {
    private static final int MAX_LINES = 500;
    private static final int MAX_OUTPUT_CHARS = 16_000;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "read_workspace_text_file", "读取代码工作区文本",
            "按行读取授权工作区内不超过 1 MiB 的 UTF-8 文本文件。拒绝二进制、密钥路径和符号链接。",
            "BUILTIN", "READ", "LOW", 5,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "工作区内文件的相对路径"),
                    "startLine", Map.of("type", "integer", "description", "从第几行开始，默认 1"),
                    "maxLines", Map.of("type", "integer", "description", "最多读取 1 到 500 行，默认 200")),
                    "required", List.of("path"), "additionalProperties", false));

    private final CodingWorkspace workspace;
    private final ObjectMapper objectMapper;

    public ReadWorkspaceTextFileTool(CodingWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var requestedPath = arguments.path("path").asText("").trim();
        if (requestedPath.isBlank()) throw new IllegalArgumentException("path 不能为空");
        var file = workspace.requireRegularFile(requestedPath);
        var size = Files.size(file);
        if (size > WorkspaceTextFiles.MAX_FILE_BYTES) throw new IllegalArgumentException("文本文件不能超过 1 MiB");
        var startLine = arguments.has("startLine") ? arguments.path("startLine").asInt() : 1;
        var maxLines = arguments.has("maxLines") ? arguments.path("maxLines").asInt() : 200;
        if (startLine < 1) throw new IllegalArgumentException("startLine 必须大于等于 1");
        if (maxLines < 1 || maxLines > MAX_LINES) throw new IllegalArgumentException("maxLines 必须在 1 到 500 之间");
        var bytes = Files.readAllBytes(file);
        var decoded = WorkspaceTextFiles.decodeUtf8(bytes);
        var text = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        var lines = text.split("\\R", -1);
        var from = Math.min(startLine - 1, lines.length);
        var requestedEnd = Math.min(lines.length, from + maxLines);
        var content = new StringBuilder();
        var emitted = 0;
        var outputLimited = false;
        for (int index = from; index < requestedEnd; index++) {
            var separator = emitted == 0 ? "" : "\n";
            if (content.length() + separator.length() + lines[index].length() > MAX_OUTPUT_CHARS) {
                outputLimited = true;
                break;
            }
            content.append(separator).append(lines[index]);
            emitted++;
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("path", workspace.relative(file));
        response.put("sha256", WorkspaceTextFiles.sha256(bytes));
        response.put("sizeBytes", bytes.length);
        response.put("startLine", startLine);
        response.put("endLine", emitted == 0 ? startLine - 1 : startLine + emitted - 1);
        response.put("totalLines", lines.length);
        response.put("content", content.toString());
        response.put("truncated", outputLimited || from + emitted < lines.length);
        return objectMapper.writeValueAsString(response);
    }
}
