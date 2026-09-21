package com.agentstudio.adapter.ssh;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ApplyRemoteTextPatchTool implements AgentTool {
    private static final int MAX_REPLACEMENTS = 10;
    private static final int MAX_PATCH_CHARS = 12_000;
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("path", "expectedSha256", "replacements");
    private static final Set<String> REPLACEMENT_FIELDS = Set.of("oldText", "newText");
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "apply_remote_workspace_text_patch", "应用受审 SSH 远程文本补丁",
            "在审批后通过 SFTP 对授权远程工作区内既有 UTF-8 文本文件执行精确替换；要求读取摘要一致和原子覆盖。",
            "SSH", "WRITE", "HIGH", 30,
            Map.of("type", "object", "properties", Map.of(
                    "path", Map.of("type", "string", "description", "远程工作区内既有文件的相对路径"),
                    "expectedSha256", Map.of("type", "string", "description", "远程读取工具返回的 SHA-256"),
                    "replacements", Map.of("type", "array", "description", "按顺序执行的精确文本替换",
                            "items", Map.of("type", "object", "properties", Map.of(
                                    "oldText", Map.of("type", "string", "description", "必须恰好出现一次的原文本"),
                                    "newText", Map.of("type", "string", "description", "替换后的文本，可为空")),
                                    "required", List.of("oldText", "newText"), "additionalProperties", false))),
                    "required", List.of("path", "expectedSha256", "replacements"),
                    "additionalProperties", false));

    private final RemoteSftpWorkspace workspace;
    private final ObjectMapper objectMapper;

    public ApplyRemoteTextPatchTool(RemoteSftpWorkspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + workspace.approvalTarget(); }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        rejectUnknownFields(arguments, TOP_LEVEL_FIELDS, "参数");
        var requestedPath = arguments.path("path").asText("").trim();
        if (requestedPath.isBlank()) throw new IllegalArgumentException("path 不能为空");
        var expectedSha256 = arguments.path("expectedSha256").asText("").trim();
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256 必须是 64 位十六进制摘要");
        }
        var replacements = arguments.path("replacements");
        if (!replacements.isArray() || replacements.isEmpty() || replacements.size() > MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("replacements 必须包含 1 到 10 项");
        }

        return workspace.execute(access -> {
            var file = access.requireFile(requestedPath);
            var originalBytes = access.read(file);
            var beforeSha256 = RemoteSftpWorkspace.sha256(originalBytes);
            if (!beforeSha256.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalArgumentException("远程文件内容已变化，expectedSha256 与当前文件不一致，请重新读取后再提交补丁");
            }
            var content = RemoteSftpWorkspace.decodeUtf8(originalBytes);
            var patchChars = 0;
            for (int index = 0; index < replacements.size(); index++) {
                var replacement = replacements.get(index);
                if (!replacement.isObject()) throw new IllegalArgumentException("replacements[" + index + "] 必须是对象");
                rejectUnknownFields(replacement, REPLACEMENT_FIELDS, "replacements[" + index + "]");
                if (!replacement.has("oldText") || !replacement.path("oldText").isTextual()
                        || !replacement.has("newText") || !replacement.path("newText").isTextual()) {
                    throw new IllegalArgumentException("replacements[" + index + "] 必须包含字符串 oldText 和 newText");
                }
                var oldText = replacement.path("oldText").asText();
                var newText = replacement.path("newText").asText();
                if (oldText.isEmpty()) throw new IllegalArgumentException("replacements[" + index + "].oldText 不能为空");
                if (oldText.equals(newText)) throw new IllegalArgumentException("replacements[" + index + "] 未产生变更");
                patchChars += oldText.length() + newText.length();
                if (patchChars > MAX_PATCH_CHARS) throw new IllegalArgumentException("补丁文本合计不能超过 12000 字符");
                var first = content.indexOf(oldText);
                if (first < 0) throw new IllegalArgumentException("replacements[" + index + "].oldText 在当前文件中不存在");
                if (content.indexOf(oldText, first + 1) >= 0) {
                    throw new IllegalArgumentException("replacements[" + index + "].oldText 在当前文件中出现多次，无法唯一定位");
                }
                content = content.substring(0, first) + newText + content.substring(first + oldText.length());
            }
            var updatedBytes = content.getBytes(StandardCharsets.UTF_8);
            if (updatedBytes.length > RemoteSftpWorkspace.MAX_FILE_BYTES) {
                throw new IllegalArgumentException("补丁结果不能超过 1 MiB");
            }
            access.replaceIfUnchanged(file, beforeSha256, updatedBytes);

            var response = new LinkedHashMap<String, Object>();
            response.put("updated", true);
            response.put("target", workspace.target());
            response.put("path", access.relative(file));
            response.put("beforeSha256", beforeSha256);
            response.put("afterSha256", RemoteSftpWorkspace.sha256(updatedBytes));
            response.put("replacementsApplied", replacements.size());
            response.put("sizeBytes", updatedBytes.length);
            return objectMapper.writeValueAsString(response);
        });
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed, String location) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!allowed.contains(field)) throw new IllegalArgumentException(location + "不支持字段：" + field);
        }
    }
}
