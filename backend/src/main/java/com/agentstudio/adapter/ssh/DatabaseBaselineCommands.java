package com.agentstudio.adapter.ssh;

import java.util.Map;

final class DatabaseBaselineCommands {
    String command(RemoteDeploymentProfile profile, String candidate, String attempt, String container,
                   Map<String, String> args, Map<String, String> hashes) {
        var backup = profile.remoteBackupRoot().replaceAll("/+$", "") + "/" + args.get("backupId");
        var compose = "docker compose --project-name " + q(profile.composeProject()) + " --file " + q(profile.composeFile());
        return """
                set -eu
                umask 077
                unset DOCKER_CONTEXT BUILDX_BUILDER BUILDKIT_HOST
                export DOCKER_HOST=unix:///var/run/docker.sock
                candidate=%s
                attempt=%s
                backup=%s
                image=%s
                container=%s
                test ! -L "$attempt" && test -d "$attempt"
                chmod 700 "$attempt"
                export DOCKER_CONFIG="$attempt/docker-config"
                mkdir "$DOCKER_CONFIG"
                test ! -L "$candidate/../.database-baseline.lock"
                exec 9> "$candidate/../.database-baseline.lock"
                flock -n 9 || exit 41
                for file in app.jar Dockerfile compose.yml nginx.conf manifest.properties SHA256SUMS; do
                  test ! -L "$candidate/$file" && test -f "$candidate/$file"
                done
                cd "$candidate"
                printf '%%s  %%s\n' %s manifest.properties %s app.jar %s Dockerfile %s compose.yml %s nginx.conf | sha256sum -c - >/dev/null || exit 42
                cd "$backup"
                test ! -e FAILED
                for file in database.sql uploads.tar.gz app.jar Dockerfile compose.yml nginx.conf .env images.json services.json manifest.properties manifest.sha256 SHA256SUMS; do
                  test ! -L "$file" && test -s "$file"
                done
                printf '%%s  manifest.properties\n' %s | sha256sum -c - >/dev/null || exit 42
                awk 'NF!=2 || length($1)!=64 || $1 ~ /[^0-9a-f]/ {exit 1} {if ($2!="database.sql" && $2!="uploads.tar.gz" && $2!="app.jar" && $2!="Dockerfile" && $2!="compose.yml" && $2!="nginx.conf" && $2!=".env" && $2!="images.json" && $2!="services.json") exit 1; if (seen[$2]++) exit 1} END {if (NR!=9) exit 1}' SHA256SUMS || exit 42
                sha256sum -c SHA256SUMS >/dev/null || exit 42
                gzip -t uploads.tar.gz
                created=$(sed -n 's/^createdAt=//p' manifest.properties)
                age=$(( $(date -u +%%s) - $(date -u -d "$created" +%%s) ))
                test "$age" -ge 0 && test "$age" -le 1800 || exit 43
                deploy=%s
                test "$(sha256sum app.jar | cut -d' ' -f1)" = "$(sha256sum "$deploy/app.jar" | cut -d' ' -f1)" || exit 42
                test "$(sha256sum Dockerfile | cut -d' ' -f1)" = "$(sha256sum "$deploy/Dockerfile" | cut -d' ' -f1)" || exit 42
                test "$(sha256sum compose.yml | cut -d' ' -f1)" = "$(sha256sum "$deploy/"%s | cut -d' ' -f1)" || exit 42
                test "$(sha256sum nginx.conf | cut -d' ' -f1)" = "$(sha256sum "$deploy/"%s | cut -d' ' -f1)" || exit 42
                test "$(sha256sum .env | cut -d' ' -f1)" = "$(sha256sum "$deploy/.env" | cut -d' ' -f1)" || exit 42
                test "$(docker image inspect --format '{{.Id}}' "$image")" = "$image" || exit 42
                test "$(docker image inspect --format '{{index .Config.Labels "agentstudio.releaseId"}}' "$image")" = %s || exit 42
                test "$(docker image inspect --format '{{index .Config.Labels "agentstudio.manifestSha256"}}' "$image")" = %s || exit 42
                if docker container inspect "$container" >/dev/null 2>&1; then exit 44; fi
                cleanup() {
                  code=$?
                  trap - EXIT HUP INT TERM
                  cleanup_failed=0
                  rm -f -- "$attempt/credentials" || cleanup_failed=1
                  if test -f "$attempt/container.started"; then
                    if docker container inspect "$container" >/dev/null 2>&1; then
                      owner=$(docker container inspect --format '{{index .Config.Labels "agentstudio.baselineOwner"}}' "$container") || cleanup_failed=1
                      if test "${owner:-}" = "$container"; then
                        timeout -k 2s 10s docker rm -f "$container" >/dev/null 2>&1 || cleanup_failed=1
                      else cleanup_failed=1; fi
                    fi
                  fi
                  if test "$cleanup_failed" -ne 0; then echo 'CLEANUP_NOT_CONFIRMED'; if test "$code" -eq 0; then code=45; fi; fi
                  exit "$code"
                }
                trap cleanup EXIT
                trap 'exit 130' HUP INT TERM
                cd "$deploy"
                timeout -k 2s 10s %s exec -T mysql sh -c 'printf "%%s\\0" "$MYSQL_DATABASE" "$MYSQL_USER" "$MYSQL_PASSWORD"' > "$attempt/credentials"
                test -s "$attempt/credentials"
                : > "$attempt/container.started"
                timeout --signal=TERM --kill-after=5s 60s docker run --rm -i --pull=never --name "$container" \
                  --label "agentstudio.baselineOwner=$container" \
                  --network %s --user 65534:65534 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
                  --cap-drop ALL --security-opt no-new-privileges --memory 512m --memory-swap 512m --cpus 0.5 \
                  --entrypoint java "$image" -Xmx256m -XX:MaxMetaspaceSize=128m \
                  -Dloader.main=com.mylove.database.DatabaseBaselineMain -cp /app/app.jar \
                  org.springframework.boot.loader.launch.PropertiesLauncher %s < "$attempt/credentials"
                """.formatted(q(candidate), q(attempt), q(backup), q(args.get("imageId")), q(container),
                q(args.get("manifestSha256")), q(hashes.get("artifactSha256")), q(hashes.get("dockerfileSha256")),
                q(hashes.get("composeSha256")), q(hashes.get("nginxSha256")), q(args.get("backupManifestSha256")),
                q(profile.remoteDeployRoot()), q(profile.composeFile()), q(profile.nginxConfig()),
                q(args.get("releaseId")), q(args.get("manifestSha256")), compose,
                q(profile.composeProject() + "_internal"), q(args.get("schemaSha256")));
    }

    static String q(String value) {
        if (value == null || value.contains("'") || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("不安全的固定参数");
        return "'" + value + "'";
    }
}
