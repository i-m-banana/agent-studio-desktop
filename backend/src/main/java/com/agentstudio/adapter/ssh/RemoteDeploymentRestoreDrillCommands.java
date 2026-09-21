package com.agentstudio.adapter.ssh;

final class RemoteDeploymentRestoreDrillCommands {

    String command(RemoteDeploymentProfile profile) {
        var backupRoot = quote(profile.remoteBackupRoot().replaceAll("/+$", ""));
        var deployRoot = quote(profile.remoteDeployRoot().replaceAll("/+$", ""));
        var composeProject = quote(profile.composeProject());
        return """
                set -eu
                umask 077
                export LC_ALL=C
                backup_root=%s
                expected_deploy_root=%s
                expected_compose_project=%s
                test -d "$backup_root"
                test ! -L "$backup_root"
                backup_id="$(find "$backup_root" -mindepth 1 -maxdepth 1 -type d -printf '%%f\\n' \
                  | grep -E '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$' | sort | tail -n 1)"
                test -n "$backup_id"
                backup_dir="$backup_root/$backup_id"
                backup_real="$(readlink -f -- "$backup_dir")"
                test "$backup_real" = "$backup_dir"
                test ! -e "$backup_dir/FAILED"
                for name in database.sql uploads.tar.gz app.jar Dockerfile compose.yml nginx.conf .env images.json services.json SHA256SUMS manifest.properties manifest.sha256; do
                  test -f "$backup_dir/$name"
                  test ! -L "$backup_dir/$name"
                done
                cd "$backup_dir"
                sha256sum -c SHA256SUMS > /dev/null
                sha256sum -c manifest.sha256 > /dev/null
                manifest_sha="$(sha256sum manifest.properties | cut -d' ' -f1)"
                grep -Fqx 'backupFormat=1' manifest.properties
                grep -Fqx "backupId=$backup_id" manifest.properties
                grep -Fqx "deploymentRoot=$expected_deploy_root" manifest.properties
                grep -Fqx "composeProject=$expected_compose_project" manifest.properties
                grep -Fqx 'files=database.sql,uploads.tar.gz,app.jar,Dockerfile,compose.yml,nginx.conf,.env,images.json,services.json' manifest.properties
                gzip -t uploads.tar.gz
                uploads_unpacked_bytes="$(tar -tvzf uploads.tar.gz | awk '{sum += $3} END {printf "%%.0f\\n", sum}')"
                fixed_bytes="$(stat -c %%s -- database.sql app.jar Dockerfile compose.yml nginx.conf .env | awk '{sum += $1} END {print sum}')"
                available_bytes="$(df -PB1 -- "$backup_root" | awk 'NR == 2 {print $4}')"
                required_bytes="$((uploads_unpacked_bytes + fixed_bytes + 268435456))"
                test "$available_bytes" -gt "$required_bytes"
                drill_root="$backup_root/restore-drills"
                if [ -e "$drill_root" ]; then test -d "$drill_root"; test ! -L "$drill_root"; else mkdir -- "$drill_root"; fi
                chmod 700 -- "$drill_root"
                drill_id="restore-$(date -u +%%Y%%m%%dT%%H%%M%%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d '[:space:]')"
                printf '%%s\\n' "$drill_id" | grep -Eq '^restore-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$'
                drill_dir="$drill_root/$drill_id"
                mkdir -- "$drill_dir"
                failed_marker="$drill_dir/FAILED"
                trap 'code=$?; if [ "$code" -ne 0 ]; then printf "%%s\\n" "$code" > "$failed_marker"; fi' EXIT
                mkdir -- "$drill_dir/deployment"
                cp -- database.sql "$drill_dir/database.sql"
                cp -- app.jar Dockerfile compose.yml nginx.conf "$drill_dir/deployment/"
                install -m 600 -- .env "$drill_dir/deployment/.env"
                tar --extract --gzip --file uploads.tar.gz --directory "$drill_dir/deployment" \
                  --no-same-owner --no-same-permissions --keep-old-files
                test -d "$drill_dir/deployment/data/uploads"
                test -z "$(find "$drill_dir/deployment/data/uploads" -type l -print -quit)"
                cmp -- database.sql "$drill_dir/database.sql"
                for name in app.jar Dockerfile compose.yml nginx.conf .env; do
                  cmp -- "$name" "$drill_dir/deployment/$name"
                done
                database_bytes="$(stat -c %%s -- "$drill_dir/database.sql")"
                uploads_bytes="$(du -sb -- "$drill_dir/deployment/data/uploads" | cut -f1)"
                restored_files="$(find "$drill_dir" -type f | wc -l)"
                test "$database_bytes" -gt 0
                test "$restored_files" -ge 7
                printf 'drillFormat=1\\ndrillId=%%s\\nbackupId=%%s\\nbackupManifestSha256=%%s\\nmode=isolated-materialization\\nproductionModified=false\\n' \
                  "$drill_id" "$backup_id" "$manifest_sha" > "$drill_dir/drill.properties"
                drill_sha="$(sha256sum "$drill_dir/drill.properties" | cut -d' ' -f1)"
                printf '%%s  drill.properties\\n' "$drill_sha" > "$drill_dir/drill.sha256"
                cd "$drill_dir"
                sha256sum -c drill.sha256 > /dev/null
                trap - EXIT
                printf 'BACKUP_ID=%%s\\nBACKUP_PATH=%%s\\nDRILL_ID=%%s\\nDRILL_PATH=%%s\\nMANIFEST_SHA256=%%s\\nDRILL_SHA256=%%s\\nDATABASE_BYTES=%%s\\nRESTORED_UPLOADS_BYTES=%%s\\nRESTORED_FILE_COUNT=%%s\\n' \
                  "$backup_id" "$backup_dir" "$drill_id" "$drill_dir" "$manifest_sha" "$drill_sha" \
                  "$database_bytes" "$uploads_bytes" "$restored_files"
                """.formatted(backupRoot, deployRoot, composeProject);
    }

    private static String quote(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("部署配置包含不安全字符");
        }
        return "'" + value + "'";
    }
}
