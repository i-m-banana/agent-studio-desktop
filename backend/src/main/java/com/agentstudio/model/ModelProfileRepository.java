package com.agentstudio.model;

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
public class ModelProfileRepository {

    private static final RowMapper<ModelProfile> ROW_MAPPER = ModelProfileRepository::map;
    private final NamedParameterJdbcTemplate jdbc;

    public ModelProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelProfile> findAll() {
        return jdbc.query("SELECT * FROM model_profile ORDER BY created_at DESC", Map.of(), ROW_MAPPER);
    }

    public Optional<ModelProfile> findById(String id) {
        return jdbc.query("SELECT * FROM model_profile WHERE id = :id", Map.of("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public void insert(ModelProfile profile) {
        jdbc.update("""
                INSERT INTO model_profile
                    (id, name, provider, base_url, model_name, api_key_env, temperature, created_at, updated_at)
                VALUES
                    (:id, :name, :provider, :baseUrl, :modelName, :apiKeyEnv, :temperature, :createdAt, :updatedAt)
                """, parameters(profile));
    }

    public void update(ModelProfile profile) {
        jdbc.update("""
                UPDATE model_profile SET
                    name = :name, provider = :provider, base_url = :baseUrl,
                    model_name = :modelName, api_key_env = :apiKeyEnv,
                    temperature = :temperature, updated_at = :updatedAt
                WHERE id = :id
                """, parameters(profile));
    }

    private static MapSqlParameterSource parameters(ModelProfile profile) {
        return new MapSqlParameterSource()
                .addValue("id", profile.id())
                .addValue("name", profile.name())
                .addValue("provider", profile.provider())
                .addValue("baseUrl", profile.baseUrl())
                .addValue("modelName", profile.modelName())
                .addValue("apiKeyEnv", profile.apiKeyEnv())
                .addValue("temperature", profile.temperature())
                .addValue("createdAt", Timestamp.from(profile.createdAt()))
                .addValue("updatedAt", Timestamp.from(profile.updatedAt()));
    }

    private static ModelProfile map(ResultSet rs, int rowNumber) throws SQLException {
        return new ModelProfile(
                rs.getString("id"), rs.getString("name"), rs.getString("provider"),
                rs.getString("base_url"), rs.getString("model_name"), rs.getString("api_key_env"),
                rs.getBigDecimal("temperature"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}

