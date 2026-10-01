package com.agentstudio.adapter.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real POSIX script with a fake Docker boundary; never reaches a daemon. */
class DatabaseBaselineShellTests {
    @TempDir Path root;
    @Test void shellBindsMaterialsAndDeletesCredentialFileOnSuccessAndFailure() throws Exception {
        execute(0, "ok", false); execute(1, "failed", false); execute(0, "cleanup-failed", true);
    }
    private void execute(int exit, String folder, boolean cleanupFail) throws Exception {
        var base = root.resolve(folder); var deploy = base.resolve("deploy"); var candidate = base.resolve("candidate");
        var backup = base.resolve("backups/20260929T120000Z-1234abcd"); var attempt = candidate.resolve("attempt");
        Files.createDirectories(deploy); Files.createDirectories(candidate); Files.createDirectories(backup); Files.createDirectories(attempt);
        for (var name : new String[]{"app.jar", "Dockerfile", "compose.yml", "nginx.conf", ".env", "images.json", "services.json", "database.sql"}) {
            Files.writeString(backup.resolve(name), "fixture-" + name);
            if (java.util.Set.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf", ".env").contains(name)) Files.copy(backup.resolve(name), deploy.resolve(name));
            if (java.util.Set.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf").contains(name)) Files.copy(backup.resolve(name), candidate.resolve(name));
        }
        try (var zip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(backup.resolve("uploads.tar.gz")))) { zip.write("fixture".getBytes(StandardCharsets.UTF_8)); }
        Files.writeString(candidate.resolve("manifest.properties"), "fixture-manifest"); Files.writeString(candidate.resolve("SHA256SUMS"), "fixture");
        Files.writeString(backup.resolve("manifest.properties"), "createdAt=" + Instant.now() + "\n"); Files.writeString(backup.resolve("manifest.sha256"), "fixture");
        var sums = new StringBuilder();
        for (var name : new String[]{"database.sql", "uploads.tar.gz", "app.jar", "Dockerfile", "compose.yml", "nginx.conf", ".env", "images.json", "services.json"}) sums.append(hash(backup.resolve(name))).append("  ").append(name).append('\n');
        Files.writeString(backup.resolve("SHA256SUMS"), sums);
        var args = Map.of("releaseId", "20260929T130000Z-1234abcd", "manifestSha256", hash(candidate.resolve("manifest.properties")), "imageId", "sha256:"+"b".repeat(64), "schemaSha256", "c".repeat(64), "backupId", "20260929T120000Z-1234abcd", "backupManifestSha256", hash(backup.resolve("manifest.properties")));
        var hashes = Map.of("artifactSha256", hash(candidate.resolve("app.jar")), "dockerfileSha256", hash(candidate.resolve("Dockerfile")), "composeSha256", hash(candidate.resolve("compose.yml")), "nginxSha256", hash(candidate.resolve("nginx.conf")));
        var profile = new RemoteDeploymentProfile("D:/source", posix(deploy), posix(base.resolve("backups")), "compose.yml", "compose.yml", "old-things", "nginx.conf", "http://127.0.0.1/", true, "READY", null, null, Instant.now());
        var fake = """
                flock() { return 0; }
                docker() {
                  if test "$1" = container; then
                    test -f "$attempt/simulated-running" || return 1
                    if test "$3" = --format; then echo "$container"; fi
                    return 0
                  fi
                  if test "$1" = image; then
                    case "$4" in
                      *releaseId*) echo '%s';;
                      *manifestSha256*) echo '%s';;
                      *) echo '%s';;
                    esac
                    return 0
                  fi
                  if test "$1" = compose; then printf 'fixture_db\\0fixture_user\\0never-return-fixture-secret\\0'; return 0; fi
                  if test "$1" = run; then
                    cat >/dev/null
                    touch "$attempt/simulated-running"
                    if test %s -eq 0; then echo 'AGENTSTUDIO_BASELINE_REGISTERED=%s'; fi
                    return %s
                  fi
                  if test "$1" = rm; then
                    if test %s = true; then return 1; fi
                    rm -f "$attempt/simulated-running"
                    touch "$attempt/simulated-cleaned"
                    return 0
                  fi
                  return 99
                }
                timeout() { while test "$1" != docker; do shift; done; "$@"; }
                """.formatted(args.get("releaseId"), args.get("manifestSha256"), args.get("imageId"), exit, args.get("schemaSha256"), exit, cleanupFail);
        var script = base.resolve("run.sh"); Files.writeString(script, fake + new DatabaseBaselineCommands().command(profile, posix(candidate), posix(attempt), "agentstudio-baseline-fixture", args, hashes));
        var bash = System.getProperty("agentstudio.test.bash", "bash");
        if (System.getProperty("os.name").startsWith("Windows") && bash.equals("bash")) {
            for (var path : java.util.List.of("D:/Git/bin/bash.exe", "C:/Program Files/Git/bin/bash.exe"))
                if (Files.isRegularFile(Path.of(path))) { bash = path; break; }
        }
        var process = new ProcessBuilder(bash, script.toString()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isEqualTo(cleanupFail && exit == 0 ? 45 : exit);
            assertThat(output).doesNotContain("never-return-fixture-secret");
            assertThat(Files.exists(attempt.resolve("credentials"))).isFalse();
            assertThat(Files.exists(attempt.resolve("simulated-cleaned"))).isEqualTo(!cleanupFail);
            if (cleanupFail) assertThat(output).contains("CLEANUP_NOT_CONFIRMED");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static String posix(Path path) { var value = path.toAbsolutePath().toString().replace('\\','/'); return value.matches("^[A-Za-z]:/.*") ? "/"+Character.toLowerCase(value.charAt(0))+value.substring(2) : value; }
    private static String hash(Path path) throws Exception { return RemoteReleaseCandidateStager.sha256(Files.readAllBytes(path)); }
}
