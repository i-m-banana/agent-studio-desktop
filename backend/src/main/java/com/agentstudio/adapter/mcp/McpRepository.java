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

    public void insert(McpServer server) {
        jdbc.update("""
                INSERT INTO mcp_server
                    (id,name,endpoint_url,api_key_env,enabled,status,created_at,updated_at)
                VALUES (:id,:name,:endpoint,:apiKeyEnv,:enabled,:status,:createdAt,:updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", server.id()).addValue("name", server.name())
                .addValue("endpoint", server.endpointUrl()).addValue("apiKeyEnv", server.apiKeyEnv())
                .addValue("enabled", server.enabled()).addValue("status", server.status())
                .addValue("createdAt", Timestamp.from(server.createdAt()))
                .addValue("updatedAt", Timestamp.from(server.updatedAt())));
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

    public void deactivateTools(String serverId) {
        jdbc.update("UPDATE mcp_tool_catalog SET active=FALSE WHERE server_id=:id", Map.of("id", serverId));
    }

    public void upsertTool(McpCatalogTool tool) {
        var parameters = new MapSqlParameterSource()
                .addValue("publicName", tool.publicName()).addValue("serverId", tool.serverId())
                .addValue("remoteName", tool.remoteName()).addValue("displayName", tool.displayName())
                .addValue("description", tool.description()).addValue("schema", json(tool.inputSchema()))
                .addValue("capability", tool.capability()).addValue("risk", tool.riskLevel())
                .addValue("timeout", tool.timeoutSeconds()).addValue("active", tool.active())
                .addValue("hash", tool.schemaSha256()).addValue("discoveredAt", Timestamp.from(tool.discoveredAt()));
        var updated = jdbc.update("""
                UPDATE mcp_tool_catalog SET display_name=:displayName, description=:description,
                    active=:active, discovered_at=:discoveredAt WHERE public_name=:publicName
                """, parameters);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO mcp_tool_catalog
                        (public_name,server_id,remote_name,display_name,description,input_schema,
                         capability,risk_level,timeout_seconds,active,schema_sha256,discovered_at)
                    VALUES (:publicName,:serverId,:remoteName,:displayName,:description,:schema,
                            :capability,:risk,:timeout,:active,:hash,:discoveredAt)
                    """, parameters);
        }
    }

    public List<McpCatalogTool> findActiveTools() {
        return jdbc.query("""
                SELECT t.* FROM mcp_tool_catalog t JOIN mcp_server s ON s.id=t.server_id
                WHERE t.active=TRUE AND s.enabled=TRUE AND s.status='READY' ORDER BY s.name,t.display_name
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

    private McpServer withTools(McpServer server) {
        return new McpServer(server.id(), server.name(), server.endpointUrl(), server.apiKeyEnv(), server.enabled(),
                server.status(), server.protocolVersion(), server.remoteServerName(), server.remoteServerVersion(),
                server.lastError(), server.lastSyncedAt(), server.createdAt(), server.updatedAt(), findTools(server.id()));
    }

    private McpServer mapServer(ResultSet rs, int row) throws SQLException {
        return new McpServer(rs.getString("id"), rs.getString("name"), rs.getString("endpoint_url"),
                rs.getString("api_key_env"), rs.getBoolean("enabled"), rs.getString("status"),
                rs.getString("protocol_version"), rs.getString("remote_server_name"),
                rs.getString("remote_server_version"), rs.getString("last_error"),
                instant(rs, "last_synced_at"), instant(rs, "created_at"), instant(rs, "updated_at"), List.of());
    }

    private McpCatalogTool mapTool(ResultSet rs, int row) throws SQLException {
        try {
            var schema = objectMapper.readValue(rs.getString("input_schema"), new TypeReference<Map<String, Object>>() {});
            return new McpCatalogTool(rs.getString("public_name"), rs.getString("server_id"),
                    rs.getString("remote_name"), rs.getString("display_name"), rs.getString("description"), schema,
                    rs.getString("capability"), rs.getString("risk_level"), rs.getInt("timeout_seconds"),
                    rs.getBoolean("active"), rs.getString("schema_sha256"), instant(rs, "discovered_at"));
        } catch (Exception exception) {
            throw new SQLException("MCP 工具 Schema 无法读取", exception);
        }
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
