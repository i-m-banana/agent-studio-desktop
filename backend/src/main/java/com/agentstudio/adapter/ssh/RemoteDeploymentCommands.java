package com.agentstudio.adapter.ssh;

import java.util.List;

final class RemoteDeploymentCommands {
    static final List<String> TASKS = List.of(
            "COMPOSE_VALIDATE", "COMPOSE_STATUS", "NGINX_VALIDATE", "SITE_HEALTH", "RELEASE_FINGERPRINT", "DATABASE_SCHEMA", "DATABASE_BASELINE_STATUS", "RELEASE_STATUS");
    private static final List<String> SERVICES = List.of("nginx", "app", "mysql", "phpmyadmin");

    String command(String task, RemoteDeploymentProfile profile) {
        if (!TASKS.contains(task)) throw new IllegalArgumentException("不支持的部署诊断任务：" + task);
        var cd = "cd " + quote(profile.remoteDeployRoot()) + " && ";
        var compose = "docker compose --project-name " + quote(profile.composeProject())
                + " --file " + quote(profile.composeFile());
        return switch (task) {
            case "RELEASE_STATUS" -> PublishReleaseCommands.status(profile);
            case "DATABASE_SCHEMA" -> new RemoteDatabaseSchemaCommands().command(profile);
            case "DATABASE_BASELINE_STATUS" -> cd + "printf '%s' " + quote("SELECT JSON_ARRAY(version,type,success) FROM flyway_schema_history ORDER BY installed_rank;")
                    + " | timeout -k 2s 15s " + compose + " exec -T mysql sh -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=socket --batch --raw --skip-column-names -uroot --database=\"$MYSQL_DATABASE\"'";
            case "COMPOSE_VALIDATE" -> cd + compose + " config --quiet";
            case "COMPOSE_STATUS" -> cd + compose + " ps --format json "
                    + String.join(" ", SERVICES.stream().map(RemoteDeploymentCommands::quote).toList());
            case "NGINX_VALIDATE" -> cd + compose + " exec -T nginx nginx -t";
            case "SITE_HEALTH" -> "curl --fail --silent --show-error --max-time 10 --output /dev/null "
                    + "--write-out 'HTTP %{http_code}\\n' " + quote(profile.healthUrl());
            case "RELEASE_FINGERPRINT" -> cd
                    + "sha256sum -- " + fingerprintFiles(profile)
                    + " && stat --printf='%n|%s|%Y\\n' -- " + fingerprintFiles(profile);
            default -> throw new IllegalArgumentException("不支持的部署诊断任务：" + task);
        };
    }

    private static String fingerprintFiles(RemoteDeploymentProfile profile) {
        return String.join(" ", List.of("app.jar", "Dockerfile", profile.composeFile(), profile.nginxConfig())
                .stream().map(RemoteDeploymentCommands::quote).toList());
    }

    private static String quote(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("部署配置包含不安全字符");
        }
        return "'" + value + "'";
    }
}
