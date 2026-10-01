package com.agentstudio.adapter.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Execute the actual generated POSIX transaction, mocking only Docker/curl/timeout/flock boundaries. */
class PublishReleaseShellTests {
    @TempDir Path root;
    @Test void successfulReleaseSynchronizesArtifactsAndNeverLeaksCredentials() throws Exception { execute("ok",0,"DEPLOYED",true); }
    @Test void healthFailureRestoresPreviousApplicationAndFiles() throws Exception { execute("healthfail",47,"ROLLED_BACK",false); }
    @Test void recoveryFailureIsExplicitAndNotTreatedAsSuccessfulRollback() throws Exception { execute("rollbackfail",47,"RECOVERY_REQUIRED",false); }
    @Test void migrationFailureNeverSwitchesButReportsDatabaseUncertainty() throws Exception { execute("migrationfail",46,"DATABASE_COMPATIBILITY",false); }
    @Test void checksumMismatchNeverMutatesProduction() throws Exception { execute("tamper",42,"PRECHECK",false); }
    @Test void expiredBackupNeverMutatesProduction() throws Exception { execute("expired",43,"PRECHECK",false); }
    @Test void oldCandidateWithoutReleaseEntryPointRefusesBeforeDatabaseAccess() throws Exception { execute("oldcandidate",48,"CANDIDATE_RUNTIME",false); }

    private void execute(String mode,int expectedExit,String stage,boolean deployed) throws Exception {
        var deploy=root.resolve("deploy"); var candidate=root.resolve("candidate"); var backup=root.resolve("backups/20261001T120000Z-1234abcd"); var attempt=candidate.resolve("publish-fixture");
        Files.createDirectories(deploy); Files.createDirectories(attempt); Files.createDirectories(backup);
        for (var name : List.of("app.jar","Dockerfile","compose.yml","nginx.conf",".env","images.json","services.json","database.sql")) {
            Files.writeString(backup.resolve(name),"old-"+name);
            if (Set.of("app.jar","Dockerfile","compose.yml","nginx.conf",".env").contains(name)) Files.copy(backup.resolve(name),deploy.resolve(name));
            if (Set.of("app.jar","Dockerfile","compose.yml","nginx.conf").contains(name)) Files.writeString(candidate.resolve(name),name.equals("app.jar") || name.equals("Dockerfile") ? "new-"+name : "old-"+name);
        }
        try (var gzip=new java.util.zip.GZIPOutputStream(Files.newOutputStream(backup.resolve("uploads.tar.gz")))) { gzip.write("fixture".getBytes(StandardCharsets.UTF_8)); }
        Files.writeString(candidate.resolve("manifest.properties"),"fixture"); Files.writeString(candidate.resolve("SHA256SUMS"),"fixture");
        Files.writeString(backup.resolve("manifest.properties"),"createdAt="+(mode.equals("expired") ? Instant.now().minusSeconds(1801) : Instant.now())+"\n");
        Files.writeString(backup.resolve("manifest.sha256"),"fixture");
        var sums=new StringBuilder();
        for (var name : List.of("database.sql","uploads.tar.gz","app.jar","Dockerfile","compose.yml","nginx.conf",".env","images.json","services.json")) sums.append(hash(backup.resolve(name))).append("  ").append(name).append('\n');
        Files.writeString(backup.resolve("SHA256SUMS"),sums);
        var fp=new StringBuilder(); for(var name : List.of("app.jar","Dockerfile","compose.yml","nginx.conf",".env")) fp.append(hash(deploy.resolve(name))).append('\n');
        var args=new LinkedHashMap<String,String>();
        args.put("releaseId","20261001T120000Z-1234abcd"); args.put("manifestSha256",hash(candidate.resolve("manifest.properties")));
        args.put("imageId","sha256:"+"a".repeat(64)); args.put("previousImageId","sha256:"+"b".repeat(64));
        args.put("schemaSha256","c".repeat(64)); args.put("backupId","20261001T120000Z-1234abcd"); args.put("backupManifestSha256",hash(backup.resolve("manifest.properties")));
        args.put("productionSha256",RemoteReleaseCandidateStager.sha256(fp.toString().getBytes(StandardCharsets.UTF_8)));
        var hashes=Map.of("artifactSha256",hash(candidate.resolve("app.jar")),"dockerfileSha256",hash(candidate.resolve("Dockerfile")),"composeSha256",hash(candidate.resolve("compose.yml")),"nginxSha256",hash(candidate.resolve("nginx.conf")));
        if (mode.equals("tamper")) Files.writeString(candidate.resolve("app.jar"),"tampered");
        var profile=new RemoteDeploymentProfile("D:/source",posix(deploy),posix(root.resolve("backups")),"compose.yml","compose.yml","old-things","nginx.conf","http://127.0.0.1/",true,"READY",null,null,Instant.now());
        var fake="""
                mode='%s'
                flock() { return 0; }
                curl() { return 0; }
                timeout() { while test "$1" != docker; do shift; done; "$@"; }
                docker() {
                  if test "$1" = container; then return 1; fi
                  if test "$1" = compose; then
                    case "$*" in
                      *'ps -q app'*) echo fixture-app;;
                      *'exec -T mysql'*) printf 'fixture_db\\0fixture_user\\0never-leak-fixture-password\\0';;
                      *'up -d'*) echo switch >> "$attempt/switches";;
                    esac
                    return 0
                  fi
                  if test "$1" = inspect; then
                    case "$3" in
                      '{{.Image}}') if test -f "$attempt/current"; then cat "$attempt/current"; else echo "$previous"; fi;;
                      '{{.Config.Image}}') echo "$tag";;
                      *State.Health*)
                        if test -f "$attempt/current" && { test "$mode" = rollbackfail || { test "$mode" = healthfail && test "$(cat "$attempt/current")" = "$image"; }; }; then echo unhealthy
                        else echo healthy; fi;;
                    esac
                    return 0
                  fi
                  if test "$1" = image; then
                    if test "$2" = tag; then printf '%%s\n' "$3" > "$attempt/current"; return 0; fi
                    case "$4" in
                      *releaseId*) echo '%s';;
                      *manifestSha256*) echo '%s';;
                      *) if test "$5" = "$tag"; then echo "$previous"; else echo "$image"; fi;;
                    esac
                    return 0
                  fi
                  if test "$1" = run; then
                    case "$*" in *invalid-sha*) if test "$mode" = oldcandidate; then echo ClassNotFoundException; else echo MIGRATION_NOT_CONFIRMED=IllegalArgumentException; fi; return 1;; esac
                    cat >/dev/null
                    if test "$mode" = migrationfail; then return 1; fi
                    echo AGENTSTUDIO_MIGRATION_VERIFIED=0; return 0
                  fi
                  return 99
                }
                """.formatted(mode,args.get("releaseId"),args.get("manifestSha256"));
        var script=new PublishReleaseCommands().script(profile,posix(candidate),posix(attempt),"f".repeat(32),args,hashes);
        script=script.replace("docker() { command timeout -k 2s 10s docker \"$@\"; }",fake);
        assertThat(script).doesNotContain("compose down","system prune","volume rm","--privileged");
        var path=root.resolve("run.sh"); Files.writeString(path,script);
        var bash="bash"; if (System.getProperty("os.name").startsWith("Windows")) for(var bin : List.of("D:/Git/bin/bash.exe","C:/Program Files/Git/bin/bash.exe")) if(Files.isRegularFile(Path.of(bin))) { bash=bin; break; }
        var process=new ProcessBuilder(bash,path.toString()).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();
            var output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isEqualTo(expectedExit);
            assertThat(output).contains("AGENTSTUDIO_PUBLISH_STAGE="+stage,"AGENTSTUDIO_DEPLOYED="+deployed).doesNotContain("never-leak-fixture-password");
            assertThat(Files.exists(attempt.resolve("credentials"))).isFalse();
            assertThat(Files.readString(deploy.resolve("app.jar"))).isEqualTo(deployed ? "new-app.jar" : "old-app.jar");
            assertThat(Files.readString(deploy.resolve(".env"))).isEqualTo("old-.env");
            assertThat(Files.exists(backup.resolve("database.sql"))).isTrue();
            if(mode.equals("healthfail")) assertThat(output).contains("AGENTSTUDIO_ROLLED_BACK=true");
            if(mode.equals("rollbackfail") || mode.equals("migrationfail")) assertThat(output).contains("AGENTSTUDIO_MANUAL_REQUIRED=true");
            if(Set.of("tamper","expired","migrationfail","oldcandidate").contains(mode)) assertThat(Files.exists(attempt.resolve("switches"))).isFalse();
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private static String hash(Path p) throws Exception { return RemoteReleaseCandidateStager.sha256(Files.readAllBytes(p)); }
    private static String posix(Path p) { var s=p.toAbsolutePath().toString().replace('\\','/'); return s.matches("^[A-Za-z]:/.*") ? "/"+Character.toLowerCase(s.charAt(0))+s.substring(2) : s; }
}
