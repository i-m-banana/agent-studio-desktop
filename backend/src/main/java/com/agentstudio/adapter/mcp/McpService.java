package com.agentstudio.adapter.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final List<McpTransportClient> clients;
    private final ObjectMapper objectMapper;

    public McpService(McpRepository repository, List<McpTransportClient> clients, ObjectMapper objectMapper) {
        this.repository = repository;
        this.clients = clients;
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
        if (!tool.active() || !tool.enabled() || !server.enabled() || !"READY".equals(server.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP 工具当前不可用，请先重新同步 Server：" + publicName);
        }
    }

    public String execute(String publicName, JsonNode arguments) throws Exception {
        var tool = requireTool(publicName);
        var server = requireServer(tool.serverId());
        if (!tool.active() || !tool.enabled() || !server.enabled() || !"READY".equals(server.status())) {
            throw new IllegalStateException("MCP 工具已离线或不再由 Server 公开：" + publicName);
        }
        return client(server).call(server, tool, arguments);
    }

    public String targetEnvironment(String publicName) {
        var tool = requireTool(publicName);
        var server = requireServer(tool.serverId());
        if ("STDIO".equals(server.transport())) return "MCP:" + server.name() + "@本机进程";
        var host = URI.create(server.endpointUrl()).getHost();
        return "MCP:" + server.name() + "@" + host;
    }

    @Transactional
    public McpServer create(McpServerRequest request) {
        var name = request.name().trim();
        if (repository.serverNameExists(name)) throw new ApiException(HttpStatus.CONFLICT, "MCP Server 名称已存在");
        var config = validate(request);
        var now = Instant.now();
        var server = new McpServer(UUID.randomUUID().toString(), name, config.transport(), config.endpointUrl(),
                config.apiKeyEnv(), config.command(), config.arguments(), config.workingDirectory(), config.environment(), true,
                "NOT_SYNCED", null, null, null, null, null, now, now, List.of());
        repository.insert(server);
        return requireServer(server.id());
    }

    @Transactional
    public McpServer update(String id, McpServerRequest request) {
        var current = requireServer(id);
        var name = request.name().trim();
        if (repository.serverNameExistsExcept(name, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "MCP Server 名称已存在");
        }
        var config = validate(request);
        var updated = new McpServer(id, name, config.transport(), config.endpointUrl(), config.apiKeyEnv(),
                config.command(), config.arguments(), config.workingDirectory(), config.environment(), current.enabled(),
                "NOT_SYNCED", null, null, null, null, null, current.createdAt(), Instant.now(), List.of());
        repository.update(updated);
        repository.deactivateTools(id);
        return requireServer(id);
    }

    public McpServer setEnabled(String id, boolean enabled) {
        requireServer(id);
        repository.setEnabled(id, enabled, Instant.now());
        return requireServer(id);
    }

    @Transactional
    public void delete(String id) {
        requireServer(id);
        if (repository.referenceCount(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "该 MCP Server 的工具已被 Agent 草稿或历史版本引用；请停用 Server 保留可追溯记录");
        }
        repository.delete(id);
    }

    public McpCatalogTool updateToolPolicy(String publicName, McpToolPolicyRequest request) {
        requireTool(publicName);
        var capability = request.capability() == null ? "EXECUTE" : request.capability().toUpperCase(Locale.ROOT);
        var risk = request.riskLevel() == null ? "HIGH" : request.riskLevel().toUpperCase(Locale.ROOT);
        if (!Set.of("READ", "WRITE", "EXECUTE", "PRIVILEGED").contains(capability)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "工具能力类型无效");
        }
        if (!Set.of("LOW", "HIGH").contains(risk)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "工具风险级别无效");
        }
        if (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "工具超时必须在 1 到 300 秒之间");
        }
        repository.updateToolPolicy(publicName, request.enabled(), capability, risk, request.timeoutSeconds());
        return requireTool(publicName);
    }

    public McpSyncResult sync(String serverId) {
        var server = requireServer(serverId);
        try {
            var before = server.tools().stream().filter(McpCatalogTool::active)
                    .map(McpCatalogTool::publicName).collect(Collectors.toSet());
            var discovery = client(server).discover(server);
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
                        description, remote.inputSchema(), "EXECUTE", "HIGH", 30, true, true, fingerprint, now));
            }
            repository.markSyncSuccess(serverId, discovery, now);
            var after = repository.findTools(serverId).stream().filter(McpCatalogTool::active)
                    .map(McpCatalogTool::publicName).collect(Collectors.toSet());
            var added = after.stream().filter(name -> !before.contains(name)).count();
            var removed = before.stream().filter(name -> !after.contains(name)).count();
            return new McpSyncResult(serverId, "READY", discovery.protocolVersion(), discovery.serverName(),
                    discovery.tools().size(), Math.toIntExact(added), Math.toIntExact(removed),
                    after.size() - Math.toIntExact(added));
        } catch (Exception exception) {
            var message = safeMessage(exception);
            repository.markSyncFailure(serverId, truncate(message, 1000), Instant.now());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP 连接或工具同步失败：" + message);
        }
    }

    private McpTransportClient client(McpServer server) {
        return clients.stream().filter(candidate -> server.transport().equals(candidate.transport())).findFirst()
                .orElseGet(() -> {
                    if (clients.size() == 1) return clients.getFirst(); // keeps isolated tests simple
                    throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的 MCP 传输类型：" + server.transport());
                });
    }

    private ValidatedConfig validate(McpServerRequest request) {
        var transport = optional(request.transport());
        transport = transport == null ? "STREAMABLE_HTTP" : transport.toUpperCase(Locale.ROOT);
        if (!Set.of("STREAMABLE_HTTP", "STDIO").contains(transport)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP 传输类型仅支持 Streamable HTTP 或 stdio");
        }
        var apiKeyEnv = optional(request.apiKeyEnv());
        validateEnvName(apiKeyEnv, "凭据环境变量名称格式无效");
        if ("STREAMABLE_HTTP".equals(transport)) {
            return new ValidatedConfig(transport, validateEndpoint(request.endpointUrl()), apiKeyEnv,
                    null, List.of(), null, Map.of());
        }
        var command = optional(request.command());
        if (command == null || command.indexOf('\0') >= 0 || command.contains("\n") || command.contains("\r")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "stdio MCP 必须填写有效的启动程序");
        }
        final String executable;
        try {
            executable = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 启动程序路径无效");
        }
        if (Set.of("cmd", "powershell", "pwsh", "sh", "bash", "zsh").contains(executable)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "为避免命令注入，stdio MCP 不允许直接使用命令行解释器");
        }
        var arguments = request.arguments() == null ? List.<String>of() : request.arguments().stream()
                .map(value -> value == null ? "" : value).toList();
        if (arguments.size() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 启动参数不能超过 100 个");
        var workingDirectory = optional(request.workingDirectory());
        if (workingDirectory != null) {
            try {
                if (!Files.exists(Path.of(workingDirectory)) || !Files.isDirectory(Path.of(workingDirectory))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 工作目录不存在或不是目录");
                }
            } catch (ApiException exception) { throw exception; }
            catch (Exception exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 工作目录路径无效"); }
        }
        var environment = request.environment() == null ? Map.<String, String>of() : request.environment();
        if (environment.size() > 50) throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 环境变量映射不能超过 50 个");
        environment.forEach((childName, hostName) -> {
            if (childName == null || hostName == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "stdio 环境变量映射不能为空");
            }
            validateEnvName(childName, "子进程环境变量名称格式无效");
            validateEnvName(hostName, "宿主环境变量名称格式无效");
        });
        return new ValidatedConfig(transport, null, null, command, arguments, workingDirectory, Map.copyOf(environment));
    }

    private void validateEnvName(String value, String message) {
        if (value != null && !value.matches("[A-Za-z_][A-Za-z0-9_]{0,159}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
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

    private record ValidatedConfig(String transport, String endpointUrl, String apiKeyEnv, String command,
                                   List<String> arguments, String workingDirectory,
                                   Map<String, String> environment) {}
}
