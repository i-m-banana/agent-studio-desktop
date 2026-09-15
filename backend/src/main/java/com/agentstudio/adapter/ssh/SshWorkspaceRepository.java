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
class SshWorkspaceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    SshWorkspaceRepository(@Qualifier("primaryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    Optional<SshWorkspaceStatus> find() {
        return jdbc.query("SELECT * FROM ssh_workspace_config WHERE id=1", Map.of(), this::map).stream().findFirst();
    }
    void save(SshWorkspaceStatus value, Instant createdAt) {
        var parameters = new MapSqlParameterSource().addValue("host", value.host()).addValue("port", value.port())
                .addValue("username", value.username()).addValue("root", value.remoteRoot())
                .addValue("fingerprint", value.hostKeySha256()).addValue("secret", value.passwordSecret())
                .addValue("status", value.status()).addValue("error", value.lastError())
                .addValue("tested", timestamp(value.lastTestedAt())).addValue("created", Timestamp.from(createdAt))
                .addValue("updated", Timestamp.from(value.updatedAt()));
        var updated = jdbc.update("""
                UPDATE ssh_workspace_config SET host_name=:host,port_number=:port,username_value=:username,
                    remote_root=:root,host_key_sha256=:fingerprint,password_secret=:secret,status=:status,
                    last_error=:error,last_tested_at=:tested,updated_at=:updated WHERE id=1
                """, parameters);
        if (updated == 0) jdbc.update("""
                INSERT INTO ssh_workspace_config
                    (id,host_name,port_number,username_value,remote_root,host_key_sha256,password_secret,
                     status,last_error,last_tested_at,created_at,updated_at)
                VALUES (1,:host,:port,:username,:root,:fingerprint,:secret,:status,:error,:tested,:created,:updated)
                """, parameters);
    }
    void updateTest(String status, String error, Instant now) {
        jdbc.update("UPDATE ssh_workspace_config SET status=:status,last_error=:error,last_tested_at=:now,updated_at=:now WHERE id=1",
                new MapSqlParameterSource().addValue("status", status).addValue("error", error)
                        .addValue("now", Timestamp.from(now)));
    }
    private SshWorkspaceStatus map(ResultSet rs, int row) throws SQLException {
        return new SshWorkspaceStatus(rs.getString("host_name"), rs.getInt("port_number"),
                rs.getString("username_value"), rs.getString("remote_root"), rs.getString("host_key_sha256"),
                rs.getString("password_secret"), true, false, rs.getString("status"), rs.getString("last_error"),
                instant(rs, "last_tested_at"), instant(rs, "updated_at"));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
}
