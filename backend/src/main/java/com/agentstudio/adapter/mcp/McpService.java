package com.agentstudio.adapter.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.agentstudio.system.ApiException;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpService {
    private final McpRepository repository;
    private final McpTransportClient client;
    private final ObjectMapper objectMapper;

    public McpService(McpRepository repository, McpTransportClient client, ObjectMapper objectMapper) {
        this.repository = repository;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public List<McpServer> listServers() { return repository.findServers(); }

    public List<ToolDescriptor> activeDescriptors() {
        return repository.findActiveTools().stream().map(McpCatalogTool::descriptor).toList();
    }

    public McpCatalogTool requireTool(String publicName) {
        return repository.findTool(publicName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "未知 MCP 工具：" + publicName));
    }

    public void validateAvailable(String publicName) {
        var tool = requireTool(publicName);
        var server = requireServer(tool.serverId());
        if (!tool.active() || !server.enabled() || !"READY".equals(server.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP 工具当前不可用，请先重新同步 Server：" + publicName);
        }
    }

    public String execute(String publicName, JsonNode arguments) throws Exception {
        var tool = requireTool(publicName);
        var server = requireServer(tool.serverId());
        if (!tool.active() || !server.enabled() || !"READY".equals(server.status())) {
            throw new IllegalStateException("MCP 工具已离线或不再由 Server 公开：" + publicName);
        }
        return client.call(server, tool, arguments);
    }

    public String targetEnvironment(String publicName) {
        var tool = requireTool(publicName);
        var server = requireServer(tool.serverId());
        var host = URI.create(server.endpointUrl()).getHost();
        return "MCP:" + server.name() + "@" + host;
    }

    @Transactional
    public McpServer create(McpServerRequest request) {
        var name = request.name().trim();
        if (repository.serverNameExists(name)) throw new ApiException(HttpStatus.CONFLICT, "MCP Server 名称已存在");
        var endpoint = validateEndpoint(request.endpointUrl());
        var apiKeyEnv = optional(request.apiKeyEnv());
        if (apiKeyEnv != null && !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,159}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "凭据环境变量名称格式无效");
        }
        var now = Instant.now();
        var server = new McpServer(UUID.randomUUID().toString(), name, endpoint, apiKeyEnv, true,
                "NOT_SYNCED", null, null, null, null, null, now, now, List.of());
        repository.insert(server);
        return requireServer(server.id());
    }

    public McpSyncResult sync(String serverId) {
        var server = requireServer(serverId);
        try {
            var discovery = client.discover(server);
            if (discovery.tools().isEmpty()) throw new IllegalStateException("MCP Server 未返回任何工具");
            var now = Instant.now();
            repository.deactivateTools(serverId);
            for (var remote : discovery.tools()) {
                var schemaJson = objectMapper.writeValueAsString(remote.inputSchema());
                if (schemaJson.length() > 65535) throw new IllegalStateException("工具 Schema 过大：" + remote.name());
                var fingerprint = sha256(remote.name() + "\n" + remote.title() + "\n" + remote.description() + "\n" + schemaJson);
                var publicName = publicName(serverId, remote.name(), fingerprint);
                var displayName = truncate(remote.title().isBlank() ? remote.name() : remote.title(), 240);
                var description = truncate(remote.description().isBlank()
                        ? "来自 MCP Server “" + server.name() + "” 的工具 " + remote.name()
                        : remote.description(), 2000);
                repository.upsertTool(new McpCatalogTool(publicName, serverId, remote.name(), displayName,
                        description, remote.inputSchema(), "EXECUTE", "HIGH", 30, true, fingerprint, now));
            }
            repository.markSyncSuccess(serverId, discovery, now);
            return new McpSyncResult(serverId, "READY", discovery.protocolVersion(), discovery.serverName(), discovery.tools().size());
        } catch (Exception exception) {
            var message = safeMessage(exception);
            repository.markSyncFailure(serverId, truncate(message, 1000), Instant.now());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP 连接或工具同步失败：" + message);
        }
    }

    private McpServer requireServer(String id) {
        return repository.findServer(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP Server 不存在"));
    }

    private String validateEndpoint(String value) {
        try {
            var uri = URI.create(value.trim());
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            var loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (!("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MCP 地址仅允许 HTTPS，或本机 localhost/127.0.0.1 的 HTTP");
            }
            return uri.toString();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP Endpoint URL 无效");
        }
    }

    private String publicName(String serverId, String remoteName, String fingerprint) {
        var safe = remoteName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) safe = "tool";
        safe = safe.substring(0, Math.min(32, safe.length()));
        return "mcp_" + serverId.replace("-", "").substring(0, 8) + "_" + safe + "_" + fingerprint.substring(0, 8);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    private String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
