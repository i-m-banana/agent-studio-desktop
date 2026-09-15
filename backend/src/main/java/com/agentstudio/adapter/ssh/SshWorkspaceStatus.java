package com.agentstudio.adapter.ssh;

import java.time.Instant;

public record SshWorkspaceStatus(String host, int port, String username, String remoteRoot,
                                 String hostKeySha256, String passwordSecret, boolean configured,
                                 boolean passwordConfigured, String status, String lastError,
                                 Instant lastTestedAt, Instant updatedAt) {}
