package com.agentstudio.adapter.ssh;

public record SshFingerprint(String host, int port, String algorithm, String sha256,
                             String warning) {}
