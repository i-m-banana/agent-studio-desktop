package com.agentstudio.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRepository {

    private static final RowMapper<AgentDefinition> DEFINITION_MAPPER = AgentRepository::mapDefinition;
    private static final RowMapper<AgentVersion> VERSION_MAPPER = AgentRepository::mapVersion;
    private final NamedParameterJdbcTemplate jdbc;

    public AgentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgentDefinition> findAll() {
        return jdbc.query("SELECT * FROM agent_definition ORDER BY updated_at DESC", Map.of(), DEFINITION_MAPPER);
    }

    public Optional<AgentDefinition> findById(String id) {
        return queryDefinition("SELECT * FROM agent_definition WHERE id = :id", id);
    }

    public Optional<AgentDefinition> findByIdForUpdate(String id) {
        return queryDefinition("SELECT * FROM agent_definition WHERE id = :id FOR UPDATE", id);
    }

    private Optional<AgentDefinition> queryDefinition(String sql, String id) {
        return jdbc.query(sql, Map.of("id", id), DEFINITION_MAPPER).stream().findFirst();
    }

    public void insert(AgentDefinition definition) {
        jdbc.update("""
                INSERT INTO agent_definition
                    (id, name, description, draft_model_profile_id, draft_system_prompt,
                     latest_version_number, created_at, updated_at)
                VALUES
                    (:id, :name, :description, :modelProfileId, :systemPrompt,
                     :latestVersionNumber, :createdAt, :updatedAt)
                """, definitionParameters(definition));
    }

    public void updateDraft(AgentDefinition definition) {
        jdbc.update("""
                UPDATE agent_definition SET
                    name = :name, description = :description,
                    draft_model_profile_id = :modelProfileId,
                    draft_system_prompt = :systemPrompt, updated_at = :updatedAt
                WHERE id = :id
                """, definitionParameters(definition));
    }

    public void insertVersionAndAdvance(AgentVersion version, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO agent_version
                    (id, agent_definition_id, version_number, model_profile_id, model_profile_name,
                     provider, base_url, model_name, api_key_env, temperature, system_prompt, published_at)
                VALUES
                    (:id, :agentDefinitionId, :versionNumber, :modelProfileId, :modelProfileName,
                     :provider, :baseUrl, :modelName, :apiKeyEnv, :temperature, :systemPrompt, :publishedAt)
                """, new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("agentDefinitionId", version.agentDefinitionId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("modelProfileId", version.modelProfileId())
                .addValue("modelProfileName", version.modelProfileName())
                .addValue("provider", version.provider())
                .addValue("baseUrl", version.baseUrl())
                .addValue("modelName", version.modelName())
                .addValue("apiKeyEnv", version.apiKeyEnv())
                .addValue("temperature", version.temperature())
                .addValue("systemPrompt", version.systemPrompt())
                .addValue("publishedAt", Timestamp.from(version.publishedAt())));

        jdbc.update("""
                UPDATE agent_definition
                SET latest_version_number = :versionNumber, updated_at = :updatedAt
                WHERE id = :id
                """, Map.of(
                "versionNumber", version.versionNumber(),
                "updatedAt", Timestamp.from(updatedAt),
                "id", version.agentDefinitionId()));
    }

    public List<AgentVersion> findVersions(String definitionId) {
        return jdbc.query("""
                SELECT * FROM agent_version
                WHERE agent_definition_id = :definitionId
                ORDER BY version_number DESC
                """, Map.of("definitionId", definitionId), VERSION_MAPPER);
    }

    public Optional<AgentVersion> findVersion(String versionId) {
        return jdbc.query("SELECT * FROM agent_version WHERE id = :id", Map.of("id", versionId), VERSION_MAPPER)
                .stream().findFirst();
    }

    private static MapSqlParameterSource definitionParameters(AgentDefinition definition) {
        return new MapSqlParameterSource()
                .addValue("id", definition.id())
                .addValue("name", definition.name())
                .addValue("description", definition.description())
                .addValue("modelProfileId", definition.draftModelProfileId())
                .addValue("systemPrompt", definition.draftSystemPrompt())
                .addValue("latestVersionNumber", definition.latestVersionNumber())
                .addValue("createdAt", Timestamp.from(definition.createdAt()))
                .addValue("updatedAt", Timestamp.from(definition.updatedAt()));
    }

    private static AgentDefinition mapDefinition(ResultSet rs, int rowNumber) throws SQLException {
        return new AgentDefinition(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("draft_model_profile_id"), rs.getString("draft_system_prompt"),
                rs.getInt("latest_version_number"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static AgentVersion mapVersion(ResultSet rs, int rowNumber) throws SQLException {
        return new AgentVersion(
                rs.getString("id"), rs.getString("agent_definition_id"), rs.getInt("version_number"),
                rs.getString("model_profile_id"), rs.getString("model_profile_name"),
                rs.getString("provider"), rs.getString("base_url"), rs.getString("model_name"),
                rs.getString("api_key_env"), rs.getBigDecimal("temperature"),
                rs.getString("system_prompt"), instant(rs, "published_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}

