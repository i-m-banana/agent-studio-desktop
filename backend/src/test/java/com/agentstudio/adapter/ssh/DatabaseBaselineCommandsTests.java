package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

class DatabaseBaselineCommandsTests {
    @Test void commandHasFixedImageLabelsChecksumBackupFreshnessIsolationAndScopedCleanup() {
        var profile = new RemoteDeploymentProfile("D:/source", "/srv/old-things", "/srv/backups", "compose.yml", "compose.yml", "old-things", "nginx.conf", "http://127.0.0.1/", true, "READY", null, null, java.time.Instant.now());
        var args = Map.of("releaseId", "20260929T120000Z-1234abcd", "manifestSha256", "a".repeat(64), "imageId", "sha256:"+"b".repeat(64), "schemaSha256", "c".repeat(64), "backupId", "20260929T130000Z-1234abcd", "backupManifestSha256", "d".repeat(64));
        var hashes = Map.of("artifactSha256", "e".repeat(64), "dockerfileSha256", "f".repeat(64), "composeSha256", "a".repeat(64), "nginxSha256", "b".repeat(64));
        var command = new DatabaseBaselineCommands().command(profile, "/srv/candidate", "/srv/candidate/baseline-fixture", "agentstudio-baseline-fixture", args, hashes);
        assertThat(command).contains("sha256sum -c SHA256SUMS", "gzip -t uploads.tar.gz", "age\" -le 1800", "agentstudio.releaseId", "agentstudio.manifestSha256", "--pull=never", "--cap-drop ALL", "--read-only", "--memory 512m", "--network 'old-things_internal'", "trap cleanup EXIT", "rm -f -- \"$attempt/credentials\"");
        assertThat(command).doesNotContain("compose up", " restart", "system prune", "volume rm", "--privileged", "migrate()", "--env-file", "MYSQL_PASSWORD=");
    }
    @Test void modelCannotChooseSqlVersionCredentialsOrImageTag() throws Exception {
        var mapper = new ObjectMapper();
        for (var input : new String[]{"{}", "{\"sql\":\"DROP TABLE users\"}", "{\"version\":\"1\"}"})
            assertThatThrownBy(() -> AdoptRemoteDatabaseBaselineTool.validate(mapper.readTree(input))).isInstanceOf(IllegalArgumentException.class);
    }
}
