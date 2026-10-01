package com.agentstudio.adapter.ssh;

import java.util.Map;

final class ReleaseImageCommands {
    static final int BUILD_BUDGET_SECONDS = 900;
    static final String BUILDKIT_IMAGE = "moby/buildkit:buildx-stable-1";
    // Owner-approved server mirrors; never accept registry hosts from model arguments.
    static final java.util.List<String> REGISTRY_MIRRORS = java.util.List.of(
            "https://docker.1ms.run/", "https://docker.1panel.live/", "https://docker.ketches.cn/");
    static final String BUILDKIT_CONFIG = """
            [registry."docker.io"]
              mirrors = ["docker.1ms.run", "docker.1panel.live", "docker.ketches.cn"]
            [registry."docker.1ms.run"]
              http = false
              insecure = false
            [registry."docker.1panel.live"]
              http = false
              insecure = false
            [registry."docker.ketches.cn"]
              http = false
              insecure = false
            """;

    static String configSha256() {
        try { return RemoteReleaseCandidateStager.sha256(BUILDKIT_CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception failure) { throw new IllegalStateException("计算固定 BuildKit 配置摘要失败", failure); }
    }

    String build(String candidate, String attempt, String builder, String image, String releaseId,
                 String manifestSha, Map<String, String> hashes) {
        return """
                set -eu
                umask 077
                candidate=%s
                attempt=%s
                builder=%s
                smoke="${builder}-smoke"
                image=%s
                stage=PRECHECK
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                unset DOCKER_CONTEXT BUILDX_BUILDER BUILDKIT_HOST
                export DOCKER_HOST=unix:///var/run/docker.sock
                export DOCKER_CONFIG="$attempt/docker-config"
                mkdir "$DOCKER_CONFIG"
                test ! -L "$candidate" && test -d "$candidate"
                test ! -L "$attempt" && test -d "$attempt"
                cd "$candidate"
                for file in app.jar Dockerfile compose.yml nginx.conf manifest.properties SHA256SUMS; do
                  test ! -L "$file" && test -f "$file"
                done
                printf '%%s  %%s\n' %s manifest.properties %s app.jar %s Dockerfile %s compose.yml %s nginx.conf | sha256sum -c - || exit 31
                command -v timeout >/dev/null
                command -v flock >/dev/null
                docker buildx version
                docker info >/dev/null
                docker_root="$(docker info --format '{{.DockerRootDir}}')"
                disk_kb="$(df -Pk -- "$docker_root" | awk 'NR==2 {print $4}')"
                memory_kb="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
                test "$disk_kb" -ge 2097152 || { echo '预检查失败：Docker 磁盘可用空间不足 2 GiB'; exit 20; }
                test "$memory_kb" -ge 786432 || { echo '预检查失败：可用内存不足 768 MiB'; exit 21; }
                lock="$candidate/../.image-build.lock"
                test ! -L "$lock"
                exec 9> "$lock"
                flock -n 9 || { echo '已有镜像构建正在执行'; exit 22; }
                if docker image inspect "$image" >/dev/null 2>&1; then echo '拒绝覆盖已有镜像标签'; exit 23; fi
                mkdir "$attempt/context"
                cp -- app.jar Dockerfile "$attempt/context/"
                # The protected candidate is 0600 under umask 077. Only the verified,
                # isolated build-context copy may be world-readable for the non-root image user.
                chmod 644 "$attempt/context/app.jar"
                cd "$attempt/context"
                printf '%%s  %%s\n' %s app.jar %s Dockerfile | sha256sum -c - || exit 31
                stage=REGISTRY_CONFIG
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                (set -C; printf '%%s\n' %s > "$attempt/buildkitd.toml")
                printf '%%s  %%s\n' %s "$attempt/buildkitd.toml" | sha256sum -c - || exit 31
                printf 'REGISTRY_MIRRORS=%%s\nBUILDKIT_CONFIG_SHA256=%%s\n' %s %s
                # No .env, uploads, Compose files or local Docker config enters the context.
                cleanup() {
                  if docker container inspect "$smoke" >/dev/null 2>&1; then
                    test "$(docker inspect --format '{{index .Config.Labels "agentstudio.imageSmokeOwner"}}' "$smoke")" = "$smoke" || return 33
                    timeout -k 5s 20s docker container rm -f "$smoke" || return "$?"
                  fi
                  test ! -L "$attempt/builder.cleaned" && test ! -L "$attempt/builder.created" || return 32
                  if test -f "$attempt/builder.cleaned"; then return 0; fi
                  if test -f "$attempt/builder.created"; then
                    timeout -k 5s 20s docker buildx rm --force "$builder" || return "$?"
                    : > "$attempt/builder.cleaned"
                  fi
                }
                finish() {
                  original_exit="$?"
                  trap - EXIT
                  set +e
                  cleanup
                  cleanup_exit="$?"
                  printf '\nAGENTSTUDIO_BUILD_DIAGNOSTIC\nFAILED_STAGE=%%s\nBUILD_EXIT_CODE=%%s\nCLEANUP_EXIT_CODE=%%s\n' "$stage" "$original_exit" "$cleanup_exit"
                  exit "$original_exit"
                }
                trap finish EXIT
                trap 'exit 130' HUP INT TERM
                stage=BUILDER_CREATE
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                timeout -k 5s 20s docker buildx create --name "$builder" --driver docker-container \
                  --buildkitd-config "$attempt/buildkitd.toml" \
                  --driver-opt image=%s,memory=512m,memory-swap=512m,cpu-period=100000,cpu-quota=50000,restart-policy=no unix:///var/run/docker.sock
                : > "$attempt/builder.created"
                stage=BUILDER_BOOTSTRAP
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                timeout -k 10s 120s docker buildx inspect --bootstrap "$builder"
                stage=RESOURCE_LIMIT_VERIFY
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                build_container="buildx_buildkit_${builder}0"
                test "$(docker inspect --format '{{.HostConfig.Memory}}' "$build_container")" = 536870912
                test "$(docker inspect --format '{{.HostConfig.MemorySwap}}' "$build_container")" = 536870912
                test "$(docker inspect --format '{{.HostConfig.CpuPeriod}}' "$build_container")" = 100000
                test "$(docker inspect --format '{{.HostConfig.CpuQuota}}' "$build_container")" = 50000
                stage=IMAGE_BUILD
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                build_deadline=$(($(date +%%s) + %s))
                build_round=0
                while :; do
                  build_round=$((build_round + 1))
                  remaining=$((build_deadline - $(date +%%s)))
                  test "$remaining" -gt 0 || exit 124
                  log="$attempt/build-$build_round.log"
                  printf 'BUILD_ROUND=%%s/3 REMAINING_SECONDS=%%s\n' "$build_round" "$remaining"
                  # Limit each diagnostic file; retries share one budget and one isolated builder.
                  build_exit=0
                  (ulimit -f 2048; timeout -k 10s "${remaining}s" docker buildx build --builder "$builder" --load --network none --progress plain \
                    --tag "$image" --label %s --label %s --file Dockerfile . > "$log" 2>&1) || build_exit=$?
                  cat "$log"
                  test "$build_exit" -ne 0 || break
                  test "$build_exit" -ne 124 && test "$build_exit" -ne 137 || exit "$build_exit"
                  test "$build_round" -lt 3 || exit "$build_exit"
                  # Only registry/download transport failures qualify, never RUN/compiler/permission errors.
                  grep -Eiq 'load metadata|failed to resolve source metadata|failed to do request|failed to fetch|failed to copy' "$log" || exit "$build_exit"
                  grep -Eiq 'unexpected EOF|i/o timeout|TLS handshake timeout|connection reset by peer|temporary failure in name resolution' "$log" || exit "$build_exit"
                  remaining=$((build_deadline - $(date +%%s)))
                  test "$remaining" -gt 5 || exit "$build_exit"
                  echo 'REGISTRY_TRANSPORT_RETRY=true BACKOFF_SECONDS=5'
                  sleep 5
                done
                stage=IMAGE_VERIFY
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                image_id="$(docker image inspect --format '{{.Id}}' "$image")"
                label_release="$(docker image inspect --format '{{index .Config.Labels "agentstudio.releaseId"}}' "$image")"
                label_manifest="$(docker image inspect --format '{{index .Config.Labels "agentstudio.manifestSha256"}}' "$image")"
                test "$label_release" = %s && test "$label_manifest" = %s
                stage=RUNTIME_SMOKE
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                test ! -L "$attempt/runtime-smoke.log"
                # Invalid fixed SHA must be rejected before stdin/database access. This proves
                # the real launcher/JAR are readable as a non-root user without touching production.
                smoke_exit=0
                (ulimit -f 256; timeout -k 5s 20s docker run --rm --name "$smoke" --label "agentstudio.imageSmokeOwner=$smoke" \\
                  --pull never --network none --user 65534:65534 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \\
                  --cap-drop ALL --security-opt no-new-privileges \\
                  --memory 512m --memory-swap 512m --cpus 0.5 --entrypoint java "$image" \\
                  -Xmx128m -XX:MaxMetaspaceSize=96m \\
                  -Dloader.main=com.mylove.database.DatabaseBaselineMain -cp /app/app.jar \\
                  org.springframework.boot.loader.launch.PropertiesLauncher invalid-sha \
                  > "$attempt/runtime-smoke.log" 2>&1) || smoke_exit=$?
                cat "$attempt/runtime-smoke.log"
                test "$smoke_exit" -eq 1 && grep -Fxq 'BASELINE_NOT_CONFIRMED=IllegalArgumentException' "$attempt/runtime-smoke.log" || exit 35
                stage=BUILDER_CLEANUP
                printf 'AGENTSTUDIO_STAGE=%%s\n' "$stage"
                cleanup
                trap - EXIT
                printf '\nAGENTSTUDIO_IMAGE_RECEIPT\nRELEASE_ID=%%s\nMANIFEST_SHA256=%%s\nIMAGE_TAG=%%s\nIMAGE_ID=%%s\nBUILDKIT_CONFIG_SHA256=%%s\nBUILDER_CLEANED=true\n' \
                  %s %s "$image" "$image_id" %s
                """.formatted(q(candidate), q(attempt), q(builder), q(image), q(manifestSha),
                q(hashes.get("artifactSha256")), q(hashes.get("dockerfileSha256")),
                q(hashes.get("composeSha256")), q(hashes.get("nginxSha256")),
                q(hashes.get("artifactSha256")), q(hashes.get("dockerfileSha256")),
                BUILDKIT_CONFIG.lines().map(ReleaseImageCommands::q).collect(java.util.stream.Collectors.joining(" ")),
                q(configSha256()), q(String.join(",", REGISTRY_MIRRORS)), q(configSha256()),
                q(BUILDKIT_IMAGE), BUILD_BUDGET_SECONDS, q("agentstudio.releaseId=" + releaseId),
                q("agentstudio.manifestSha256=" + manifestSha), q(releaseId), q(manifestSha),
                q(releaseId), q(manifestSha), q(configSha256()));
    }

    String cleanup(String attempt, String builder) {
        return "set -eu; test ! -L " + q(attempt)
                + "; unset DOCKER_CONTEXT BUILDX_BUILDER BUILDKIT_HOST; export DOCKER_HOST=unix:///var/run/docker.sock; export DOCKER_CONFIG="
                + q(attempt + "/docker-config")
                + "; if docker container inspect " + q(builder + "-smoke") + " >/dev/null 2>&1; then test \"$(docker inspect --format '{{index .Config.Labels \"agentstudio.imageSmokeOwner\"}}' "
                + q(builder + "-smoke") + ")\" = " + q(builder + "-smoke")
                + "; timeout -k 5s 20s docker container rm -f " + q(builder + "-smoke") + "; fi"
                + "; test ! -L " + q(attempt + "/builder.cleaned")
                + "; test ! -L " + q(attempt + "/builder.created")
                + "; if test -f " + q(attempt + "/builder.cleaned") + "; then echo 'BUILDER_CLEANED=true'; exit 0; fi"
                + "; if test -f " + q(attempt + "/builder.created")
                + "; then timeout -k 5s 20s docker buildx rm --force " + q(builder)
                + "; : > " + q(attempt + "/builder.cleaned") + "; fi";
    }

    private static String q(String value) {
        if (value == null || value.contains("'") || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("镜像构建配置包含不安全字符");
        return "'" + value + "'";
    }
}
