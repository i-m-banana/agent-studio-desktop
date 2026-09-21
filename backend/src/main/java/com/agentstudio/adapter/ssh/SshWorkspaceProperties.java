package com.agentstudio.adapter.ssh;

import java.time.Duration;

public record SshWorkspaceProperties(
        String host,
        int port,
        String username,
        String remoteRoot,
        String hostKeySha256,
        String passwordSecret,
        Duration connectTimeout) {

    public SshWorkspaceProperties(
            String host, int port, String username, String remoteRoot, String hostKeySha256,
            String passwordSecret, Duration connectTimeout) {
        this.host = trim(host);
        this.port = port;
        this.username = trim(username);
        this.remoteRoot = trim(remoteRoot);
        this.hostKeySha256 = trim(hostKeySha256);
        this.passwordSecret = trim(passwordSecret);
        this.connectTimeout = connectTimeout;
    }

    public void validate() {
        if (host.isBlank() || username.isBlank() || remoteRoot.isBlank() || hostKeySha256.isBlank()) {
            throw new IllegalStateException("SSH 远程工作区未配置完整，请设置主机、用户、远程根目录和主机 SHA256 指纹");
        }
        if (port < 1 || port > 65535) throw new IllegalStateException("SSH 端口必须在 1 到 65535 之间");
        if (!remoteRoot.startsWith("/") || "/".equals(remoteRoot)) {
            throw new IllegalStateException("SSH 远程根目录必须是非根目录的绝对 POSIX 路径");
        }
        if (!hostKeySha256.startsWith("SHA256:") || hostKeySha256.length() < 20) {
            throw new IllegalStateException("SSH 主机指纹必须使用 SHA256:... 格式");
        }
        if (passwordSecret.isBlank()) throw new IllegalStateException("SSH 密码凭据名称不能为空");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("SSH 连接超时必须在 0 到 30 秒之间");
        }
    }

    public String target() { return username + "@" + host + ":" + port + remoteRoot; }

    public String approvalTarget() { return target() + "#" + hostKeySha256; }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
