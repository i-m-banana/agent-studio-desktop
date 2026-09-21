package com.agentstudio.adapter.ssh;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RemoteDeploymentRequest(
        @NotBlank @Size(max = 1000) String localSourceRoot,
        @NotBlank @Size(max = 1000) String remoteDeployRoot,
        @NotBlank @Size(max = 1000) String remoteBackupRoot,
        @NotBlank @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9._-]+") String composeFile,
        @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*") String composeProject,
        @NotBlank @Size(max = 500) String nginxConfig,
        @NotBlank @Size(max = 500) String healthUrl) {}
