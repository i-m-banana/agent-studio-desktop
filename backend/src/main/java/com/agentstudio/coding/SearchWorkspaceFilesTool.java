package com.agentstudio.coding;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SearchWorkspaceFilesTool implements AgentTool {
    private static final int MAX_RESULTS = 100;
    private static final int MAX_SCANNED_ENTRIES = 10_000;
    private static final int MAX_DEPTH = 30;
    private static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES = Set.of(
            "node_modules", "target", "dist", "build", "out", "coverage", ".idea", ".run");
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "search_workspace_files", "搜索代码工作区文件",
            "在授权代码工作区内按文件名或相对路径做不区分大小写的文本匹配；默认跳过常见依赖和构建目录，不搜索文件内容或跟随链接。",
            "BUILTIN", "READ", "LOW", 10,
            Map.of("type", "object", "properties", Map.of(
                    "query", Map.of("type", "string", "description", "文件名或相对路径中的文本，1 到 100 字符"),
                    "path", Map.of("type", "string", "description", "搜索起点相对目录；省略时为根目录"),
                    "maxResults", Map.of("type", "integer", "description", "最多返回 1 到 100 项；默认 50")),
                    "required", List.of("query"), "additionalProperties", false));

    private final CodingWorkspace workspace;
    private final ObjectMapper objectMapper;

    public SearchWorkspaceFilesTool(CodingWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        var query = arguments.path("query").asText("").trim();
        if (query.isBlank() || query.length() > 100) throw new IllegalArgumentException("query 长度必须在 1 到 100 字符之间");
        var maximum = arguments.has("maxResults") ? arguments.path("maxResults").asInt() : 50;
        if (maximum < 1 || maximum > MAX_RESULTS) throw new IllegalArgumentException("maxResults 必须在 1 到 100 之间");
        var start = workspace.requireDirectory(arguments.path("path").asText("."));
        var root = workspace.root();
        var state = new SearchState(query.toLowerCase(Locale.ROOT), maximum);
        Files.walkFileTree(start, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                        if (!directory.equals(start) && (isDefaultExcluded(directory)
                                || !workspace.isSafeEntry(directory, root))) {
                            state.skippedDirectories++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return state.scanned++ >= MAX_SCANNED_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (state.scanned++ >= MAX_SCANNED_ENTRIES) return FileVisitResult.TERMINATE;
                        if (attributes.isDirectory()) {
                            state.depthLimited = true;
                            return FileVisitResult.CONTINUE;
                        }
                        if (!attributes.isRegularFile() || workspace.isProtected(file, root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        var relative = workspace.relative(file, root);
                        if (relative.toLowerCase(Locale.ROOT).contains(state.query)
                                && workspace.isSafeEntry(file, root)) {
                            state.matches.add(Map.of("path", relative, "sizeBytes", attributes.size()));
                            if (state.matches.size() > state.maximum) return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        state.matches.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("path"))));
        var response = new LinkedHashMap<String, Object>();
        response.put("path", workspace.relative(start, root));
        response.put("query", query);
        response.put("files", state.matches.stream().limit(maximum).toList());
        response.put("scannedEntries", Math.min(state.scanned, MAX_SCANNED_ENTRIES));
        response.put("skippedDirectories", state.skippedDirectories);
        response.put("truncated", state.depthLimited || state.scanned >= MAX_SCANNED_ENTRIES
                || state.matches.size() > maximum);
        return objectMapper.writeValueAsString(response);
    }

    private boolean isDefaultExcluded(Path directory) {
        return DEFAULT_EXCLUDED_DIRECTORIES.contains(
                directory.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private static final class SearchState {
        private final String query;
        private final int maximum;
        private final List<Map<String, Object>> matches = new ArrayList<>();
        private int scanned;
        private int skippedDirectories;
        private boolean depthLimited;

        private SearchState(String query, int maximum) {
            this.query = query;
            this.maximum = maximum;
        }
    }
}
