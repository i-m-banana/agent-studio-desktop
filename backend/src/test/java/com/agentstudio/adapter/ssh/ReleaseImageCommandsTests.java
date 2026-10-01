package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Execute the generated POSIX program against a fake Docker boundary, not a real daemon/site. */
class ReleaseImageCommandsTests {
    @TempDir Path root;

    @Test void shellBuildUsesOnlyTwoVerifiedFilesAndEmitsReceiptAfterCleanup() throws Exception {
        var result = execute(false, false, false);
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("AGENTSTUDIO_STAGE=RUNTIME_SMOKE", "AGENTSTUDIO_IMAGE_RECEIPT", "BUILDER_CLEANED=true");
        assertThat(Files.readString(root.resolve("candidate/attempt/cleaned"))).isEqualTo("cleaned");
        assertThat(Files.readString(root.resolve("candidate/attempt/buildkitd.toml")))
                .isEqualTo(ReleaseImageCommands.BUILDKIT_CONFIG);
        assertThat(result.output()).contains("REGISTRY_MIRRORS=https://docker.1ms.run/",
                "BUILDKIT_CONFIG_SHA256=" + ReleaseImageCommands.configSha256());
        try (var files = Files.list(root.resolve("candidate/attempt/context"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactlyInAnyOrder("app.jar", "Dockerfile");
        }
    }

    @Test void unreadableJarFailsRuntimeSmokeWithoutReadyReceipt() throws Exception {
        var result = execute(false, false, false, false, false, "smoke-missing");
        assertThat(result.exit()).isEqualTo(35);
        assertThat(result.output()).contains("FAILED_STAGE=RUNTIME_SMOKE", "ClassNotFoundException")
                .doesNotContain("AGENTSTUDIO_IMAGE_RECEIPT");
        assertThat(Files.exists(root.resolve("candidate/attempt/builder.cleaned"))).isTrue();
    }

    @Test void runtimeSmokeTimeoutFailsClosed() throws Exception {
        var result = execute(false, false, false, false, false, "smoke-timeout");
        assertThat(result.exit()).isEqualTo(35);
        assertThat(result.output()).contains("FAILED_STAGE=RUNTIME_SMOKE").doesNotContain("AGENTSTUDIO_IMAGE_RECEIPT");
    }

    @Test void shellBuildFailureKeepsExitAndRunsScopedCleanup() throws Exception {
        var result = execute(true, false, false);
        assertThat(result.exit()).isEqualTo(17);
        assertThat(result.output()).doesNotContain("AGENTSTUDIO_IMAGE_RECEIPT");
        assertThat(Files.exists(root.resolve("candidate/attempt/cleaned"))).isTrue();
        assertThat(result.output()).contains("FAILED_STAGE=IMAGE_BUILD", "BUILD_EXIT_CODE=17", "CLEANUP_EXIT_CODE=0");
        assertThat(Files.exists(root.resolve("candidate/attempt/builder.cleaned"))).isTrue();
    }

    @Test void transientRegistryEofRetriesTwiceThenVerifiesImage() throws Exception {
        var result = execute(false, false, false, false, false, "recover");
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("BUILD_ROUND=3/3", "REGISTRY_TRANSPORT_RETRY=true", "AGENTSTUDIO_IMAGE_RECEIPT");
        assertThat(Files.readString(root.resolve("candidate/attempt/rounds"))).isEqualTo("3\n");
    }

    @Test void persistentRegistryFailureStopsAtThreeAndPreservesOriginalExit() throws Exception {
        var result = execute(false, false, false, false, false, "persistent");
        assertThat(result.exit()).isEqualTo(17);
        assertThat(result.output()).contains("BUILD_ROUND=3/3", "BUILD_EXIT_CODE=17", "CLEANUP_EXIT_CODE=0")
                .doesNotContain("BUILD_ROUND=4", "AGENTSTUDIO_IMAGE_RECEIPT");
    }

    @Test void compilerEofDoesNotQualifyForRegistryRetry() throws Exception {
        var result = execute(false, false, false, false, false, "compiler");
        assertThat(result.exit()).isEqualTo(17);
        assertThat(result.output()).contains("compiler unexpected EOF").doesNotContain("REGISTRY_TRANSPORT_RETRY=true", "BUILD_ROUND=2");
    }

    @Test void buildTimeoutDoesNotRestartTheTimeBudget() throws Exception {
        var result = execute(false, false, false, false, false, "timeout");
        assertThat(result.exit()).isEqualTo(124);
        assertThat(result.output()).contains("BUILD_EXIT_CODE=124").doesNotContain("BUILD_ROUND=2", "REGISTRY_TRANSPORT_RETRY=true");
    }

    @Test void repeatedCleanupIsIdempotentAfterRemoteTrapRemovedBuilder() throws Exception {
        execute(true, false, false);
        var attempt = root.resolve("candidate/attempt").toString().replace('\\', '/');
        var cleanup = new ReleaseImageCommands().cleanup(attempt, "agentstudio-fixture");
        assertThat(cleanup).contains("agentstudio-fixture-smoke", "agentstudio.imageSmokeOwner", "docker container rm -f");
        // Any second Docker invocation would fail, exactly as a missing builder does.
        var result = runScript("docker() { echo 'builder not found'; return 1; }\n" + cleanup, "cleanup.sh");
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("BUILDER_CLEANED=true").doesNotContain("builder not found");
    }

    @Test void bootstrapTimeoutRetainsOriginalExitEvenWhenCleanupAlsoFails() throws Exception {
        var result = execute(false, false, false, true, true);
        assertThat(result.exit()).isEqualTo(124);
        assertThat(result.output()).contains("FAILED_STAGE=BUILDER_BOOTSTRAP", "BUILD_EXIT_CODE=124",
                "CLEANUP_EXIT_CODE=18", "registry download timeout", "cleanup failed");
        assertThat(Files.exists(root.resolve("candidate/attempt/builder.cleaned"))).isFalse();
    }

    @Test void shellRefusesTamperedMaterialsBeforeBuilderCreation() throws Exception {
        var result = execute(false, true, false);
        assertThat(result.exit()).isNotZero();
        assertThat(Files.exists(root.resolve("candidate/attempt/builder.created"))).isFalse();
    }

    @Test void shellDiskPrecheckPreventsBuilderCreation() throws Exception {
        var result = execute(false, false, true);
        assertThat(result.exit()).isEqualTo(20);
        assertThat(Files.exists(root.resolve("candidate/attempt/builder.created"))).isFalse();
    }

    @Test void mirrorConfigUsesOnlyOwnerApprovedHostsAndNeverWeakensTlsOrWritesGlobalConfig() {
        assertThat(ReleaseImageCommands.BUILDKIT_CONFIG)
                .contains("mirrors = [\"docker.1ms.run\", \"docker.1panel.live\", \"docker.ketches.cn\"]")
                .doesNotContain("http = true", "insecure = true", "http://", "ca=", "key=");
        assertThat(ReleaseImageCommands.configSha256()).matches("[0-9a-f]{64}");
        var script = new ReleaseImageCommands().build("/srv/releases/candidate", "/srv/releases/candidate/attempt",
                "builder", "image", "20260928T081518Z-deadbeef", "a".repeat(64),
                Map.of("artifactSha256", "b".repeat(64), "dockerfileSha256", "c".repeat(64),
                        "composeSha256", "d".repeat(64), "nginxSha256", "e".repeat(64)));
        assertThat(script).contains("--buildkitd-config \"$attempt/buildkitd.toml\"", "(set -C; printf")
                .contains("build_deadline=$(($(date +%s) + 900))")
                .contains("build_deadline=", "build_round\" -lt 3", "ulimit -f 2048")
                .contains("chmod 644 \"$attempt/context/app.jar\"", "--network none --user 65534:65534 --read-only")
                .doesNotContain("/etc/docker/daemon.json", "systemctl", "--allow", "--use ", "--network host");
    }

    private Result execute(boolean fail, boolean tamper, boolean lowDisk) throws Exception {
        return execute(fail, tamper, lowDisk, false, false);
    }

    private Result execute(boolean fail, boolean tamper, boolean lowDisk, boolean bootstrapTimeout, boolean cleanupFailure) throws Exception {
        return execute(fail, tamper, lowDisk, bootstrapTimeout, cleanupFailure, "none");
    }

    private Result execute(boolean fail, boolean tamper, boolean lowDisk, boolean bootstrapTimeout, boolean cleanupFailure, String mode) throws Exception {
        var candidate = Files.createDirectories(root.resolve("candidate"));
        var attempt = Files.createDirectories(candidate.resolve("attempt"));
        for (var file : java.util.List.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf", "SHA256SUMS"))
            Files.writeString(candidate.resolve(file), "fixture");
        Files.writeString(candidate.resolve("manifest.properties"), "manifest");
        Files.writeString(candidate.resolve(".env"), "must-not-enter-context");
        var hash = RemoteReleaseCandidateStager.sha256(candidate.resolve("app.jar"));
        var manifestSha = RemoteReleaseCandidateStager.sha256(candidate.resolve("manifest.properties"));
        if (tamper) Files.writeString(candidate.resolve("Dockerfile"), "changed");
        var program = """
                # Test substitutes only external operations. Filesystem, hash, copy, trap and receipt are real.
                timeout() { while test "$#" -gt 0; do case "$1" in -k) shift 2;; *s) shift; break;; *) break;; esac; done; "$@"; }
                flock() { return 0; }
                sleep() { return 0; }
                df() { printf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 9999999 1 %s 1%% /\\n'; }
                awk() { case "$*" in */proc/meminfo*) echo 900000;; *) command awk "$@";; esac; }
                docker() {
                  case "$*" in
                    'buildx version'|'info') return 0;;
                    'buildx create '*) case "$*" in *'--buildkitd-config '*) test -f "$attempt/buildkitd.toml";; *) return 99;; esac;;
                    'buildx inspect --bootstrap '*) %s;;
                    "inspect --format {{.HostConfig.Memory}} "*|"inspect --format {{.HostConfig.MemorySwap}} "*) echo 536870912;;
                    "inspect --format {{.HostConfig.CpuPeriod}} "*) echo 100000;;
                    "inspect --format {{.HostConfig.CpuQuota}} "*) echo 50000;;
                    "info --format {{.DockerRootDir}}") echo /docker-root;;
                    "image inspect --format {{.Id}} "*) printf 'sha256:%%s\\n' '%s';;
                    "image inspect --format {{index .Config.Labels \\"agentstudio.releaseId\\"}} "*) echo 20260928T081518Z-deadbeef;;
                    "image inspect --format {{index .Config.Labels \\"agentstudio.manifestSha256\\"}} "*) echo %s;;
                    'image inspect '*) return 1;;
                    'container inspect '*) return 1;;
                    'run --rm '*) case '$mode' in
                      smoke-missing) echo 'java.lang.ClassNotFoundException: org.springframework.boot.loader.launch.PropertiesLauncher'; return 1;;
                      smoke-timeout) echo 'runtime smoke timed out'; return 124;;
                      *) case "$*" in *'--network none --user 65534:65534 --read-only '*)
                        echo 'BASELINE_NOT_CONFIRMED=IllegalArgumentException'; return 1;;
                        *) echo 'unsafe smoke invocation'; return 99;; esac;;
                    esac;;
                    'buildx build '*) test ! -e .env; test "$(find . -maxdepth 1 -type f | wc -l)" -eq 2;
                      %s
                      return %s;;
                    'buildx rm --force '*) %s;;
                    *) echo "unexpected fake Docker call: $*"; return 99;;
                  esac
                }
                """.replace("$mode", mode).formatted(lowDisk ? "100" : "9000000",
                        bootstrapTimeout ? "echo 'registry download timeout'; return 124" : "return 0",
                        "c".repeat(64), manifestSha,
                        mode.equals("timeout") ? "echo 'load metadata: i/o timeout'; return 124" : mode.equals("compiler") ? "echo 'compiler unexpected EOF'; return 17" : mode.equals("none") ? ":" : """
                        rounds=0; test ! -f "$attempt/rounds" || rounds=$(cat "$attempt/rounds")
                        rounds=$((rounds + 1)); echo "$rounds" > "$attempt/rounds"
                        if test '%s' = persistent || test "$rounds" -lt 3; then
                          echo 'load metadata: short read: expected 3843 bytes but got 0: unexpected EOF'; return 17
                        fi
                        """.formatted(mode), fail ? "17" : "0",
                        cleanupFailure ? "echo 'cleanup failed'; return 18" : "printf cleaned > \"$attempt/cleaned\"")
                + new ReleaseImageCommands().build(candidate.toString().replace('\\', '/'), attempt.toString().replace('\\', '/'),
                        "agentstudio-fixture", "agentstudio-candidate:fixture", "20260928T081518Z-deadbeef", manifestSha,
                        Map.of("artifactSha256", hash, "dockerfileSha256", hash, "composeSha256", hash, "nginxSha256", hash));
        return runScript(program, "fixture.sh");
    }

    private Result runScript(String program, String name) throws Exception {
        var script = root.resolve(name); Files.writeString(script, program, StandardCharsets.UTF_8);
        var bash = System.getProperty("agentstudio.test.bash", "bash");
        if (System.getProperty("os.name").startsWith("Windows") && bash.equals("bash")) {
            for (var path : java.util.List.of("D:/Git/bin/bash.exe", "C:/Program Files/Git/bin/bash.exe"))
                if (Files.isRegularFile(Path.of(path))) { bash = path; break; }
        }
        var outputFile = root.resolve("output.txt").toFile();
        var process = new ProcessBuilder(bash, script.toString().replace('\\', '/')).redirectErrorStream(true)
                .redirectOutput(outputFile).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("fixture shell timed out"); }
        return new Result(process.exitValue(), Files.readString(outputFile.toPath()));
    }
    record Result(int exit, String output) {}
}
