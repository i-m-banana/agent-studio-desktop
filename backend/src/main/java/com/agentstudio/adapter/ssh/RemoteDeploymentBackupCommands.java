package com.agentstudio.adapter.ssh;

import java.util.List;

final class RemoteDeploymentBackupCommands {
    private static final List<String> SERVICES = List.of("nginx", "app", "mysql", "phpmyadmin");

    String command(RemoteDeploymentProfile profile) {
        var deployRoot = quote(profile.remoteDeployRoot());
        var backupRoot = quote(profile.remoteBackupRoot());
        var compose = "docker compose --project-name " + quote(profile.composeProject())
                + " --file " + quote(profile.composeFile());
        var services = String.join(" ", SERVICES.stream().map(RemoteDeploymentBackupCommands::quote).toList());
        return """
                set -eu
                umask 077
                deploy_root=%s
                backup_root=%s
                backup_id="$(date -u +%%Y%%m%%dT%%H%%M%%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d '[:space:]')"
                case "$backup_id" in (*[!A-Za-z0-9TtZz-]*|'') exit 91;; esac
                backup_dir="$backup_root/$backup_id"
                mkdir -- "$backup_dir"
                failed_marker="$backup_dir/FAILED"
                trap 'code=$?; if [ "$code" -ne 0 ]; then printf "%%s\\n" "$code" > "$failed_marker"; fi' EXIT
                cd "$deploy_root"
                %s exec -T mysql sh -c 'exec mysqldump --single-transaction --quick --lock-tables=false -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > "$backup_dir/database.sql"
                test -s "$backup_dir/database.sql"
                tar -czf "$backup_dir/uploads.tar.gz" -- data/uploads
                test -s "$backup_dir/uploads.tar.gz"
                cp -- app.jar Dockerfile "$backup_dir/"
                cp -- %s "$backup_dir/compose.yml"
                cp -- %s "$backup_dir/nginx.conf"
                install -m 600 -- .env "$backup_dir/.env"
                %s images --format json > "$backup_dir/images.json"
                %s ps --format json %s > "$backup_dir/services.json"
                test -s "$backup_dir/images.json"
                test -s "$backup_dir/services.json"
                cd "$backup_dir"
                sha256sum -- database.sql uploads.tar.gz app.jar Dockerfile compose.yml nginx.conf .env images.json services.json > SHA256SUMS
                test "$(wc -l < SHA256SUMS)" -eq 9
                created_at="$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)"
                printf 'backupFormat=1\\nbackupId=%%s\\ncreatedAt=%%s\\ndeploymentRoot=%%s\\ncomposeProject=%%s\\nfiles=database.sql,uploads.tar.gz,app.jar,Dockerfile,compose.yml,nginx.conf,.env,images.json,services.json\\n' \
                  "$backup_id" "$created_at" "$deploy_root" %s > manifest.properties
                manifest_sha="$(sha256sum manifest.properties | cut -d' ' -f1)"
                printf '%%s  manifest.properties\\n' "$manifest_sha" > manifest.sha256
                sha256sum -c SHA256SUMS > /dev/null
                sha256sum -c manifest.sha256 > /dev/null
                database_bytes="$(stat -c %%s -- database.sql)"
                uploads_bytes="$(stat -c %%s -- uploads.tar.gz)"
                test "$database_bytes" -gt 0
                test "$uploads_bytes" -gt 0
                trap - EXIT
                printf 'BACKUP_ID=%%s\\nBACKUP_PATH=%%s\\nMANIFEST_SHA256=%%s\\nDATABASE_BYTES=%%s\\nUPLOADS_BYTES=%%s\\nFILE_COUNT=9\\n' \
                  "$backup_id" "$backup_dir" "$manifest_sha" "$database_bytes" "$uploads_bytes"
                """.formatted(deployRoot, backupRoot, compose, quote(profile.composeFile()),
                quote(profile.nginxConfig()), compose, compose, services, quote(profile.composeProject()));
    }

    private static String quote(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("部署配置包含不安全字符");
        }
        return "'" + value + "'";
    }
}
