package com.agentstudio.adapter.ssh;

import java.time.Instant;

public record RemoteDeploymentProfile(
        String localSourceRoot, String remoteDeployRoot, String remoteBackupRoot,
        String localComposeFile, String composeFile, String composeProject, String nginxConfig, String healthUrl,
        boolean configured, String status, String lastError, Instant lastTestedAt, Instant updatedAt) {

    static RemoteDeploymentProfile empty() {
        return new RemoteDeploymentProfile("", "", "", "docker-compose.yml", "compose.yml", "old-things",
                "nginx.conf", "http://127.0.0.1/", false,
                "NOT_CONFIGURED", null, null, null);
    }
}
