package com.agentstudio.adapter.ssh;

import com.agentstudio.system.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SshWorkspaceBrowserService {
    private final ListRemoteDirectoryTool directories;
    private final ReadRemoteTextFileTool files;
    private final ObjectMapper objectMapper;

    public SshWorkspaceBrowserService(ListRemoteDirectoryTool directories,
                                      ReadRemoteTextFileTool files,
                                      ObjectMapper objectMapper) {
        this.directories = directories;
        this.files = files;
        this.objectMapper = objectMapper;
    }

    public JsonNode directory(String path, int maxEntries) {
        var arguments = objectMapper.createObjectNode()
                .put("path", path == null || path.isBlank() ? "." : path.trim())
                .put("maxEntries", maxEntries);
        return invoke(() -> directories.execute(arguments), "读取远程目录失败");
    }

    public JsonNode file(String path, int startLine, int maxLines) {
        var arguments = objectMapper.createObjectNode()
                .put("path", path == null ? "" : path.trim())
                .put("startLine", startLine)
                .put("maxLines", maxLines);
        return invoke(() -> files.execute(arguments), "读取远程文本失败");
    }

    private JsonNode invoke(CheckedSupplier operation, String prefix) {
        try {
            return objectMapper.readTree(operation.get());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, safe(exception));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, prefix + "：" + safe(exception));
        }
    }

    private String safe(Exception exception) {
        var message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface CheckedSupplier { String get() throws Exception; }
}
