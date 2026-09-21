package com.agentstudio.adapter.ssh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SearchRemoteFilesTool implements AgentTool {
    private static final int MAX_RESULTS = 100;
    private static final int MAX_SCANNED_ENTRIES = 3_000;
    private static final int MAX_DEPTH = 16;
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "search_remote_workspace_files", "搜索 SSH 远程工作区文件",
            "通过 SFTP 在授权远程工作区按文件名或相对路径搜索；限制扫描规模并跳过依赖、构建、密钥和符号链接目录，不搜索文件内容。",
            "SSH", "READ", "LOW", 30,
            Map.of("type", "object", "properties", Map.of(
                    "query", Map.of("type", "string", "description", "文件名或相对路径文本，1 到 100 字符"),
                    "path", Map.of("type", "string", "description", "搜索起点相对目录；默认根目录"),
                    "maxResults", Map.of("type", "integer", "description", "最多返回 1 到 100 项；默认 50")),
                    "required", List.of("query"), "additionalProperties", false));
    private final RemoteSftpWorkspace workspace;
    private final ObjectMapper objectMapper;
    public SearchRemoteFilesTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace; this.objectMapper = objectMapper;
    }
    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + workspace.approvalTarget(); }
    @Override public String execute(JsonNode arguments) throws Exception {
        var query = arguments.path("query").asText("").trim();
        if (query.isBlank() || query.length() > 100) throw new IllegalArgumentException("query 长度必须在 1 到 100 字符之间");
        var maximum = arguments.has("maxResults") ? arguments.path("maxResults").asInt() : 50;
        if (maximum < 1 || maximum > MAX_RESULTS) throw new IllegalArgumentException("maxResults 必须在 1 到 100 之间");
        var lowered = query.toLowerCase(Locale.ROOT);
        return workspace.execute(access -> {
            var start = access.requireDirectory(arguments.path("path").asText("."));
            var queue = new ArrayDeque<Directory>(); queue.add(new Directory(start, 0));
            var matches = new ArrayList<Map<String, Object>>();
            var scanned = 0; var skipped = 0; var truncated = false;
            while (!queue.isEmpty() && scanned < MAX_SCANNED_ENTRIES && matches.size() <= maximum) {
                var current = queue.removeFirst();
                for (var entry : access.list(current.path())) {
                    if (++scanned > MAX_SCANNED_ENTRIES) { truncated = true; break; }
                    if (entry.type().equals("SYMLINK") || entry.type().equals("OTHER")) { skipped++; continue; }
                    if (entry.type().equals("DIRECTORY")) {
                        if (access.excludedDirectory(entry.name()) || current.depth() >= MAX_DEPTH) {
                            skipped++; truncated |= current.depth() >= MAX_DEPTH;
                        } else queue.addLast(new Directory(current.path() + "/" + entry.name(), current.depth() + 1));
                    } else if (entry.path().toLowerCase(Locale.ROOT).contains(lowered)) {
                        matches.add(Map.of("path", entry.path(), "sizeBytes", entry.sizeBytes()));
                        if (matches.size() > maximum) { truncated = true; break; }
                    }
                }
            }
            truncated |= !queue.isEmpty() || scanned >= MAX_SCANNED_ENTRIES || matches.size() > maximum;
            matches.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("path"))));
            var response = new LinkedHashMap<String, Object>();
            response.put("target", workspace.target()); response.put("path", access.relative(start));
            response.put("query", query); response.put("files", matches.stream().limit(maximum).toList());
            response.put("scannedEntries", Math.min(scanned, MAX_SCANNED_ENTRIES));
            response.put("skippedEntries", skipped); response.put("truncated", truncated);
            return objectMapper.writeValueAsString(response);
        });
    }
    private record Directory(String path, int depth) {}
}
