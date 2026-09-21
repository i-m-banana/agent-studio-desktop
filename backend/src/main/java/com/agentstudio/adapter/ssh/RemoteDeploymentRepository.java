package com.agentstudio.adapter.ssh;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RemoteDeploymentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    RemoteDeploymentRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RemoteDeploymentProfile> find() {
        return jdbc.query("SELECT * FROM remote_deployment_profile WHERE id=1", Map.of(), this::map)
                .stream().findFirst();
    }

    void save(RemoteDeploymentProfile value, Instant createdAt) {
        var parameters = new MapSqlParameterSource()
                .addValue("localRoot", value.localSourceRoot()).addValue("deployRoot", value.remoteDeployRoot())
                .addValue("backupRoot", value.remoteBackupRoot()).addValue("composeFile", value.composeFile())
                .addValue("composeProject", value.composeProject()).addValue("nginxConfig", value.nginxConfig())
                .addValue("healthUrl", value.healthUrl()).addValue("status", value.status())
                .addValue("error", value.lastError()).addValue("tested", timestamp(value.lastTestedAt()))
                .addValue("created", Timestamp.from(createdAt)).addValue("updated", Timestamp.from(value.updatedAt()));
        var updated = jdbc.update("""
                UPDATE remote_deployment_profile SET local_source_root=:localRoot,remote_deploy_root=:deployRoot,
                    remote_backup_root=:backupRoot,compose_file=:composeFile,compose_project=:composeProject,
                    nginx_config=:nginxConfig,health_url=:healthUrl,status=:status,last_error=:error,
                    last_tested_at=:tested,updated_at=:updated WHERE id=1
                """, parameters);
        if (updated == 0) jdbc.update("""
                INSERT INTO remote_deployment_profile
                    (id,local_source_root,remote_deploy_root,remote_backup_root,compose_file,compose_project,
                     nginx_config,health_url,status,last_error,last_tested_at,created_at,updated_at)
                VALUES (1,:localRoot,:deployRoot,:backupRoot,:composeFile,:composeProject,:nginxConfig,
                        :healthUrl,:status,:error,:tested,:created,:updated)
                """, parameters);
    }

    void updateTest(String status, String error, Instant now) {
        jdbc.update("""
                UPDATE remote_deployment_profile SET status=:status,last_error=:error,last_tested_at=:now,
                    updated_at=:now WHERE id=1
                """, new MapSqlParameterSource().addValue("status", status).addValue("error", error)
                .addValue("now", Timestamp.from(now)));
    }

    private RemoteDeploymentProfile map(ResultSet rs, int row) throws SQLException {
        return new RemoteDeploymentProfile(rs.getString("local_source_root"), rs.getString("remote_deploy_root"),
                rs.getString("remote_backup_root"), rs.getString("compose_file"),
                rs.getString("compose_project"), rs.getString("nginx_config"), rs.getString("health_url"),
                true, rs.getString("status"), rs.getString("last_error"), instant(rs, "last_tested_at"),
                instant(rs, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
}
