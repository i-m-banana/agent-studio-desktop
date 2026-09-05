package com.agentstudio.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WriteWorkspaceNoteTool implements AgentTool {
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "write_workspace_note", "写入受控工作区笔记", "在本地 data/tool-workspace 中新建 UTF-8 Markdown 或文本文件；不会覆盖已有文件。",
            "BUILTIN", "WRITE", "HIGH", 5,
            Map.of("type", "object", "properties", Map.of(
                    "fileName", Map.of("type", "string", "description", "文件名，必须以 .md 或 .txt 结尾"),
                    "content", Map.of("type", "string", "description", "写入内容，最多 10000 字符")),
                    "required", List.of("fileName", "content"), "additionalProperties", false));
    private final Path workspace;

    public WriteWorkspaceNoteTool(@Value("${agent-studio.data-dir:../data}") String dataDir) {
        this.workspace = Path.of(dataDir).toAbsolutePath().normalize().resolve("tool-workspace").normalize();
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!"fileName".equals(field) && !"content".equals(field))
                throw new IllegalArgumentException("不支持的参数：" + field);
        }
        var fileName = arguments.path("fileName").asText("").trim();
        var content = arguments.path("content").asText("");
        if (!fileName.matches("[\\p{L}\\p{N}._-]{1,120}\\.(?i:md|txt)"))
            throw new IllegalArgumentException("文件名只能包含文字、数字、点、下划线或短横线，并以 .md/.txt 结尾");
        if (content.isBlank() || content.length() > 10_000)
            throw new IllegalArgumentException("内容不能为空且不能超过 10000 字符");
        var target = workspace.resolve(fileName).normalize();
        if (!target.startsWith(workspace)) throw new IllegalArgumentException("文件路径越界");
        Files.createDirectories(workspace);
        Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return "{\"created\":true,\"path\":\"tool-workspace/" + fileName + "\"}";
    }
}
