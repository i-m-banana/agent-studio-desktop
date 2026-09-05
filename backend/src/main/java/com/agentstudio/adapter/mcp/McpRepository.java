package com.agentstudio.adapter.mcp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class McpRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public McpRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc,
                         ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<McpServer> findServers() {
        return jdbc.query("SELECT * FROM mcp_server ORDER BY updated_at DESC", Map.of(), this::mapServer)
                .stream().map(this::withTools).toList();
    }

    public Optional<McpServer> findServer(String id) {
        return jdbc.query("SELECT * FROM mcp_server WHERE id=:id", Map.of("id", id), this::mapServer)
                .stream().findFirst().map(this::withTools);
    }

    public boolean serverNameExists(String name) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_server WHERE name=:name", Map.of("name", name), Integer.class);
        return count != null && count > 0;
    }

    public boolean serverNameExistsExcept(String name, String id) {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_server WHERE name=:name AND id<>:id",
                Map.of("name", name, "id", id), Integer.class);
        return count != null && count > 0;
    }

    public void insert(McpServer server) {
        jdbc.update("""
                INSERT INTO mcp_server
                    (id,name,transport,endpoint_url,api_key_env,command_path,arguments_json,
                     working_directory,environment_json,enabled,status,created_at,updated_at)
                VALUES (:id,:name,:transport,:endpoint,:apiKeyEnv,:command,:arguments,
                        :workingDirectory,:environment,:enabled,:status,:createdAt,:updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", server.id()).addValue("name", server.name())
                .addValue("transport", server.transport())
                .addValue("endpoint", server.endpointUrl()).addValue("apiKeyEnv", server.apiKeyEnv())
                .addValue("command", server.command()).addValue("arguments", json(server.arguments()))
                .addValue("workingDirectory", server.workingDirectory()).addValue("environment", json(server.environment()))
                .addValue("enabled", server.enabled()).addValue("status", server.status())
                .addValue("createdAt", Timestamp.from(server.createdAt()))
                .addValue("updatedAt", Timestamp.from(server.updatedAt())));
    }

    public void update(McpServer server) {
        jdbc.update("""
                UPDATE mcp_server SET name=:name,transport=:transport,endpoint_url=:endpoint,
                    api_key_env=:apiKeyEnv,command_path=:command,arguments_json=:arguments,
                    working_directory=:workingDirectory,environment_json=:environment,
                    status='NOT_SYNCED',protocol_version=NULL,remote_server_name=NULL,
                    remote_server_version=NULL,last_error=NULL,last_synced_at=NULL,updated_at=:updatedAt
                WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", server.id()).addValue("name", server.name())
                .addValue("transport", server.transport()).addValue("endpoint", server.endpointUrl())
                .addValue("apiKeyEnv", server.apiKeyEnv()).addValue("command", server.command())
                .addValue("arguments", json(server.arguments())).addValue("workingDirectory", server.workingDirectory())
                .addValue("environment", json(server.environment())).addValue("updatedAt", Timestamp.from(server.updatedAt())));
    }

    public void setEnabled(String id, boolean enabled, Instant now) {
        jdbc.update("UPDATE mcp_server SET enabled=:enabled,updated_at=:now WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("enabled", enabled)
                        .addValue("now", Timestamp.from(now)));
    }

    public int referenceCount(String serverId) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT avt.tool_name FROM agent_version_tool avt JOIN mcp_tool_catalog t ON t.public_name=avt.tool_name
                    WHERE t.server_id=:id
                    UNION ALL
                    SELECT atb.tool_name FROM agent_tool_binding atb JOIN mcp_tool_catalog t ON t.public_name=atb.tool_name
                    WHERE t.server_id=:id
                ) refs
                """, Map.of("id", serverId), Integer.class);
        return count == null ? 0 : count;
    }

    public void delete(String serverId) {
        jdbc.update("DELETE FROM mcp_sync_event WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("DELETE FROM mcp_prompt_catalog WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("DELETE FROM mcp_resource_catalog WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("DELETE FROM mcp_tool_catalog WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("DELETE FROM mcp_server WHERE id=:id", Map.of("id", serverId));
    }

    public void markSyncSuccess(String id, McpDiscovery discovery, Instant now) {
        jdbc.update("""
                UPDATE mcp_server SET status='READY', protocol_version=:protocol,
                    remote_server_name=:remoteName, remote_server_version=:remoteVersion,
                    last_error=NULL, last_synced_at=:now, updated_at=:now WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("protocol", discovery.protocolVersion())
                .addValue("remoteName", discovery.serverName()).addValue("remoteVersion", discovery.serverVersion())
                .addValue("now", Timestamp.from(now)));
    }

    public void markSyncFailure(String id, String message, Instant now) {
        jdbc.update("""
                UPDATE mcp_server SET status='FAILED', last_error=:error, updated_at=:now WHERE id=:id
                """, new MapSqlParameterSource().addValue("id", id).addValue("error", message)
                .addValue("now", Timestamp.from(now)));
    }

    public void insertSyncEvent(McpSyncEvent event) {
        jdbc.update("""
                INSERT INTO mcp_sync_event
                    (id,server_id,status,protocol_version,tool_count,resource_count,prompt_count,
                     added_count,removed_count,unchanged_count,error_message,created_at)
                VALUES (:id,:serverId,:status,:protocol,:tools,:resources,:prompts,
                        :added,:removed,:unchanged,:error,:createdAt)
                """, new MapSqlParameterSource().addValue("id", event.id())
                .addValue("serverId", event.serverId()).addValue("status", event.status())
                .addValue("protocol", event.protocolVersion()).addValue("tools", event.toolCount())
                .addValue("resources", event.resourceCount()).addValue("prompts", event.promptCount())
                .addValue("added", event.added()).addValue("removed", event.removed())
                .addValue("unchanged", event.unchanged()).addValue("error", event.errorMessage())
                .addValue("createdAt", Timestamp.from(event.createdAt())));
    }

    public List<McpSyncEvent> findSyncEvents(String serverId, int limit) {
        return jdbc.query("""
                SELECT * FROM mcp_sync_event WHERE server_id=:id
                ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("id", serverId).addValue("limit", limit),
                (rs, row) -> new McpSyncEvent(rs.getString("id"), rs.getString("server_id"),
                        rs.getString("status"), rs.getString("protocol_version"), rs.getInt("tool_count"),
                        rs.getInt("resource_count"), rs.getInt("prompt_count"), rs.getInt("added_count"),
                        rs.getInt("removed_count"), rs.getInt("unchanged_count"), rs.getString("error_message"),
                        instant(rs, "created_at")));
    }

    public void deactivateTools(String serverId) {
        jdbc.update("UPDATE mcp_tool_catalog SET active=FALSE WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("UPDATE mcp_resource_catalog SET active=FALSE WHERE server_id=:id", Map.of("id", serverId));
        jdbc.update("UPDATE mcp_prompt_catalog SET active=FALSE WHERE server_id=:id", Map.of("id", serverId));
    }

    public void upsertResource(McpCatalogResource resource, String uriSha256) {
        var parameters = new MapSqlParameterSource().addValue("publicId", resource.publicId())
                .addValue("serverId", resource.serverId()).addValue("uri", resource.uri())
                .addValue("uriHash", uriSha256).addValue("name", resource.name())
                .addValue("displayName", resource.displayName()).addValue("description", resource.description())
                .addValue("mimeType", resource.mimeType()).addValue("size", resource.size())
                .addValue("active", resource.active()).addValue("discoveredAt", Timestamp.from(resource.discoveredAt()));
        var updated = jdbc.update("""
                UPDATE mcp_resource_catalog SET resource_name=:name,display_name=:displayName,
                    description=:description,mime_type=:mimeType,resource_size=:size,active=:active,
                    discovered_at=:discoveredAt WHERE public_id=:publicId
                """, parameters);
        if (updated == 0) jdbc.update("""
                INSERT INTO mcp_resource_catalog
                    (public_id,server_id,uri_value,uri_sha256,resource_name,display_name,description,
                     mime_type,resource_size,active,discovered_at)
                VALUES (:publicId,:serverId,:uri,:uriHash,:name,:displayName,:description,
                        :mimeType,:size,:active,:discoveredAt)
                """, parameters);
    }

    public void upsertPrompt(McpCatalogPrompt prompt) {
        var parameters = new MapSqlParameterSource().addValue("publicName", prompt.publicName())
                .addValue("serverId", prompt.serverId()).addValue("remoteName", prompt.remoteName())
                .addValue("displayName", prompt.displayName()).addValue("description", prompt.description())
                .addValue("arguments", json(prompt.arguments())).addValue("hash", prompt.fingerprintSha256())
                .addValue("active", prompt.active()).addValue("discoveredAt", Timestamp.from(prompt.discoveredAt()));
        var updated = jdbc.update("""
                UPDATE mcp_prompt_catalog SET display_name=:displayName,description=:description,
                    active=:active,discovered_at=:discoveredAt WHERE public_name=:publicName
                """, parameters);
        if (updated == 0) jdbc.update("""
                INSERT INTO mcp_prompt_catalog
                    (public_name,server_id,remote_name,display_name,description,arguments_json,
                     fingerprint_sha256,active,discovered_at)
                VALUES (:publicName,:serverId,:remoteName,:displayName,:description,:arguments,
                        :hash,:active,:discoveredAt)
                """, parameters);
    }

    public List<McpCatalogResource> findResources(String serverId) {
        return jdbc.query("SELECT * FROM mcp_resource_catalog WHERE server_id=:id ORDER BY active DESC,display_name",
                Map.of("id", serverId), this::mapResource);
    }

    public Optional<McpCatalogResource> findResource(String publicId) {
        return jdbc.query("SELECT * FROM mcp_resource_catalog WHERE public_id=:id", Map.of("id", publicId),
                this::mapResource).stream().findFirst();
    }

    public List<McpCatalogPrompt> findPrompts(String serverId) {
        return jdbc.query("SELECT * FROM mcp_prompt_catalog WHERE server_id=:id ORDER BY active DESC,display_name",
                Map.of("id", serverId), this::mapPrompt);
    }

    public Optional<McpCatalogPrompt> findPrompt(String publicName) {
        return jdbc.query("SELECT * FROM mcp_prompt_catalog WHERE public_name=:name", Map.of("name", publicName),
                this::mapPrompt).stream().findFirst();
    }

    public void upsertTool(McpCatalogTool tool) {
        var parameters = new MapSqlParameterSource()
                .addValue("publicName", tool.publicName()).addValue("serverId", tool.serverId())
                .addValue("remoteName", tool.remoteName()).addValue("displayName", tool.displayName())
                .addValue("description", tool.description()).addValue("schema", json(tool.inputSchema()))
                .addValue("capability", tool.capability()).addValue("risk", tool.riskLevel())
                .addValue("timeout", tool.timeoutSeconds()).addValue("active", tool.active())
                .addValue("enabled", tool.enabled())
                .addValue("hash", tool.schemaSha256()).addValue("discoveredAt", Timestamp.from(tool.discoveredAt()));
        var updated = jdbc.update("""
                UPDATE mcp_tool_catalog SET display_name=:displayName, description=:description,
                    active=:active, discovered_at=:discoveredAt WHERE public_name=:publicName
                """, parameters);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO mcp_tool_catalog
                        (public_name,server_id,remote_name,display_name,description,input_schema,
                         capability,risk_level,timeout_seconds,active,enabled,schema_sha256,discovered_at)
                    VALUES (:publicName,:serverId,:remoteName,:displayName,:description,:schema,
                            :capability,:risk,:timeout,:active,:enabled,:hash,:discoveredAt)
                    """, parameters);
        }
    }

    public List<McpCatalogTool> findActiveTools() {
        return jdbc.query("""
                SELECT t.* FROM mcp_tool_catalog t JOIN mcp_server s ON s.id=t.server_id
                WHERE t.active=TRUE AND t.enabled=TRUE AND s.enabled=TRUE AND s.status='READY'
                ORDER BY s.name,t.display_name
                """, Map.of(), this::mapTool);
    }

    public List<McpCatalogTool> findTools(String serverId) {
        return jdbc.query("SELECT * FROM mcp_tool_catalog WHERE server_id=:id ORDER BY active DESC,display_name",
                Map.of("id", serverId), this::mapTool);
    }

    public Optional<McpCatalogTool> findTool(String publicName) {
        return jdbc.query("SELECT * FROM mcp_tool_catalog WHERE public_name=:name",
                Map.of("name", publicName), this::mapTool).stream().findFirst();
    }

    public void updateToolPolicy(String publicName, boolean enabled, String capability, String riskLevel,
                                 int timeoutSeconds) {
        jdbc.update("""
                UPDATE mcp_tool_catalog SET enabled=:enabled,capability=:capability,
                    risk_level=:risk,timeout_seconds=:timeout WHERE public_name=:name
                """, new MapSqlParameterSource().addValue("name", publicName).addValue("enabled", enabled)
                .addValue("capability", capability).addValue("risk", riskLevel).addValue("timeout", timeoutSeconds));
    }

    private McpServer withTools(McpServer server) {
        return new McpServer(server.id(), server.name(), server.transport(), server.endpointUrl(), server.apiKeyEnv(),
                server.command(), server.arguments(), server.workingDirectory(), server.environment(), server.enabled(),
                server.status(), server.protocolVersion(), server.remoteServerName(), server.remoteServerVersion(),
                server.lastError(), server.lastSyncedAt(), server.createdAt(), server.updatedAt(), findTools(server.id()));
    }

    private McpCatalogTool mapTool(ResultSet rs, int row) throws SQLException {
        try {
            var schema = objectMapper.readValue(rs.getString("input_schema"), new TypeReference<Map<String, Object>>() {});
            return new McpCatalogTool(rs.getString("public_name"), rs.getString("server_id"),
                    rs.getString("remote_name"), rs.getString("display_name"), rs.getString("description"), schema,
                    rs.getString("capability"), rs.getString("risk_level"), rs.getInt("timeout_seconds"),
                    rs.getBoolean("active"), rs.getBoolean("enabled"), rs.getString("schema_sha256"), instant(rs, "discovered_at"));
        } catch (Exception exception) {
            throw new SQLException("MCP 工具 Schema 无法读取", exception);
        }
    }

    private McpCatalogResource mapResource(ResultSet rs, int row) throws SQLException {
        var size = rs.getObject("resource_size", Long.class);
        return new McpCatalogResource(rs.getString("public_id"), rs.getString("server_id"),
                rs.getString("uri_value"), rs.getString("resource_name"), rs.getString("display_name"),
                rs.getString("description"), rs.getString("mime_type"), size, rs.getBoolean("active"),
                instant(rs, "discovered_at"));
    }

    private McpCatalogPrompt mapPrompt(ResultSet rs, int row) throws SQLException {
        try {
            var arguments = read(rs.getString("arguments_json"), new TypeReference<List<McpPromptArgument>>() {}, List.of());
            return new McpCatalogPrompt(rs.getString("public_name"), rs.getString("server_id"),
                    rs.getString("remote_name"), rs.getString("display_name"), rs.getString("description"),
                    arguments, rs.getBoolean("active"), rs.getString("fingerprint_sha256"),
                    instant(rs, "discovered_at"));
        } catch (Exception exception) { throw new SQLException("MCP Prompt 参数无法读取", exception); }
    }


    private McpServer mapServer(ResultSet rs, int row) throws SQLException {
        try {
            var arguments = read(rs.getString("arguments_json"), new TypeReference<List<String>>() {}, List.of());
            var environment = read(rs.getString("environment_json"), new TypeReference<Map<String, String>>() {}, Map.of());
            return new McpServer(rs.getString("id"), rs.getString("name"), rs.getString("transport"),
                    rs.getString("endpoint_url"), rs.getString("api_key_env"), rs.getString("command_path"),
                    arguments, rs.getString("working_directory"), environment, rs.getBoolean("enabled"),
                    rs.getString("status"), rs.getString("protocol_version"), rs.getString("remote_server_name"),
                    rs.getString("remote_server_version"), rs.getString("last_error"), instant(rs, "last_synced_at"),
                    instant(rs, "created_at"), instant(rs, "updated_at"), List.of());
        } catch (Exception exception) {
            throw new SQLException("MCP Server 配置无法读取", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) throws Exception {
        return value == null || value.isBlank() ? fallback : objectMapper.readValue(value, type);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("MCP 工具 Schema 无法保存", exception); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
