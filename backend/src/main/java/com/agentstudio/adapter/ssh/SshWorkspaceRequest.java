package com.agentstudio.adapter.ssh;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SshWorkspaceRequest(
        @NotBlank @Size(max = 255) String host,
        @Min(1) @Max(65535) int port,
        @NotBlank @Size(max = 160) String username,
        @NotBlank @Size(max = 1000) String remoteRoot,
        @NotBlank @Size(max = 160) @Pattern(regexp = "SHA256:.+", message = "必须使用 SHA256:... 格式")
        String hostKeySha256,
        @NotBlank @Size(max = 160) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String passwordSecret) {}
