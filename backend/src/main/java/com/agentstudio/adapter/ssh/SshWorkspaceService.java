package com.agentstudio.adapter.ssh;

import java.time.Duration;
import java.time.Instant;

import com.agentstudio.secret.SecretResolver;
import com.agentstudio.system.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SshWorkspaceService {
    private final SshWorkspaceRepository repository;
    private final SecretResolver secrets;
    private final SftpSessionFactory sessions;
    private final SshWorkspaceProperties fallback;

    public SshWorkspaceService(SshWorkspaceRepository repository, SecretResolver secrets,
                               SftpSessionFactory sessions,
                               @Value("${agent-studio.ssh.host:}") String host,
                               @Value("${agent-studio.ssh.port:22}") int port,
                               @Value("${agent-studio.ssh.username:}") String username,
                               @Value("${agent-studio.ssh.remote-root:}") String remoteRoot,
                               @Value("${agent-studio.ssh.host-key-sha256:}") String fingerprint,
                               @Value("${agent-studio.ssh.password-secret:AGENT_STUDIO_SSH_PASSWORD}") String secret,
                               @Value("${agent-studio.ssh.connect-timeout:10s}") Duration timeout) {
        this.repository = repository; this.secrets = secrets; this.sessions = sessions;
        this.fallback = new SshWorkspaceProperties(host, port, username, remoteRoot, fingerprint, secret, timeout);
    }

    public SshWorkspaceProperties current() {
        return repository.find().map(value -> new SshWorkspaceProperties(value.host(), value.port(), value.username(),
                value.remoteRoot(), value.hostKeySha256(), value.passwordSecret(), fallback.connectTimeout()))
                .orElse(fallback);
    }

    public SshWorkspaceStatus status() {
        var stored = repository.find(); var config = current();
        var base = stored.orElse(new SshWorkspaceStatus(config.host(), config.port(), config.username(),
                config.remoteRoot(), config.hostKeySha256(), config.passwordSecret(), !config.host().isBlank(),
                false, config.host().isBlank() ? "NOT_CONFIGURED" : "NOT_TESTED", null, null, null));
        return new SshWorkspaceStatus(base.host(), base.port(), base.username(), base.remoteRoot(),
                base.hostKeySha256(), base.passwordSecret(), base.configured(),
                secrets.resolve(base.passwordSecret()).isPresent(), base.status(), base.lastError(),
                base.lastTestedAt(), base.updatedAt());
    }

    public SshWorkspaceStatus save(SshWorkspaceRequest request) {
        var config = new SshWorkspaceProperties(request.host(), request.port(), request.username(),
                request.remoteRoot(), request.hostKeySha256(), request.passwordSecret(), fallback.connectTimeout());
        try { config.validate(); new RemotePathPolicy(config.remoteRoot()); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        var now = Instant.now(); var created = repository.find().map(SshWorkspaceStatus::updatedAt).orElse(now);
        repository.save(new SshWorkspaceStatus(config.host(), config.port(), config.username(), config.remoteRoot(),
                config.hostKeySha256(), config.passwordSecret(), true, false, "NOT_TESTED", null, null, now), created);
        return status();
    }

    public SshFingerprint inspect(SshFingerprintRequest request) {
        try { return sessions.inspectFingerprint(request.host(), request.port(), fallback.connectTimeout()); }
        catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "无法读取 SSH 主机指纹：" + safe(exception)); }
    }

    public SshConnectionTestResult test() {
        var config = current(); var now = Instant.now();
        try {
            config.validate();
            sessions.execute(config, sftp -> {
                var attributes = sftp.lstat(config.remoteRoot());
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IllegalArgumentException("远程根目录不存在、不是目录或是符号链接");
                }
                return null;
            });
            repository.updateTest("READY", null, now);
            return new SshConnectionTestResult(true, config.target(), "SSH 认证、主机指纹与 SFTP 远程根目录检查通过");
        } catch (Exception exception) {
            var message = safe(exception); repository.updateTest("FAILED", message, now);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SSH/SFTP 测试失败：" + message);
        }
    }

    private String safe(Exception exception) {
        var message = exception.getMessage(); return message == null ? exception.getClass().getSimpleName() : message;
    }
}
