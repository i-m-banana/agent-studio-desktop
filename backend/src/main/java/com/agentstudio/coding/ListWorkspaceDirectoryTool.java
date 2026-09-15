package com.agentstudio.coding;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ListWorkspaceDirectoryTool implements AgentTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "list_workspace_directory", "浏览代码工作区目录",
            "列出授权代码工作区内一个相对目录的直接子项。不会跟随符号链接，也不会返回受保护的密钥路径。",
            "BUILTIN", "READ", "LOW", 5,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "工作区相对目录；省略时为根目录"),
                    "maxEntries", Map.of("type", "integer", "description", "最多返回 1 到 200 项；默认 100")),
                    "required", List.of(), "additionalProperties", false));

    private final CodingWorkspace workspace;
    private final ObjectMapper objectMapper;

    public ListWorkspaceDirectoryTool(CodingWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var directory = workspace.requireDirectory(arguments.path("path").asText("."));
        var limit = bounded(arguments, "maxEntries", DEFAULT_LIMIT, MAX_LIMIT);
        List<Map<String, Object>> all;
        try (var children = Files.list(directory)) {
            all = children.filter(path -> {
                        try { return !workspace.isProtected(path); }
                        catch (Exception exception) { return false; }
                    })
                    .sorted(Comparator.comparing((java.nio.file.Path path) ->
                                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .map(path -> entry(path))
                    .toList();
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("path", workspace.relative(directory));
        response.put("entries", all.stream().limit(limit).toList());
        response.put("truncated", all.size() > limit);
        return objectMapper.writeValueAsString(response);
    }

    private Map<String, Object> entry(java.nio.file.Path path) {
        var result = new LinkedHashMap<String, Object>();
        try {
            result.put("name", path.getFileName().toString());
            result.put("path", workspace.relative(path));
            result.put("type", Files.isSymbolicLink(path) ? "SYMLINK"
                    : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "DIRECTORY"
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? "FILE" : "OTHER");
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.put("sizeBytes", Files.size(path));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取目录项", exception);
        }
    }

    private int bounded(JsonNode arguments, String name, int defaultValue, int maximum) {
        var value = arguments.has(name) ? arguments.path(name).asInt() : defaultValue;
        if (value < 1 || value > maximum) throw new IllegalArgumentException(name + " 必须在 1 到 " + maximum + " 之间");
        return value;
    }
}
