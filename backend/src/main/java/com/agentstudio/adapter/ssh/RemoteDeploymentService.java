package com.agentstudio.adapter.ssh;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;

import com.agentstudio.system.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RemoteDeploymentService {
    private final RemoteDeploymentRepository repository;
    private final SshWorkspaceService ssh;
    private final RemoteDeploymentWorkspace workspace;

    public RemoteDeploymentService(RemoteDeploymentRepository repository, SshWorkspaceService ssh,
                                   RemoteDeploymentWorkspace workspace) {
        this.repository = repository; this.ssh = ssh; this.workspace = workspace;
    }

    public RemoteDeploymentProfile status() { return repository.find().orElse(RemoteDeploymentProfile.empty()); }

    public RemoteDeploymentProfile current() {
        return repository.find().orElseThrow(() -> new IllegalStateException("远程部署 Profile 尚未配置"));
    }

    public RemoteDeploymentProfile save(RemoteDeploymentRequest request) {
        var profile = normalized(request);
        validate(profile);
        var now = Instant.now();
        var created = repository.find().map(RemoteDeploymentProfile::updatedAt).orElse(now);
        repository.save(new RemoteDeploymentProfile(profile.localSourceRoot(), profile.remoteDeployRoot(),
                profile.remoteBackupRoot(), profile.composeFile(), profile.composeProject(), profile.nginxConfig(),
                profile.healthUrl(), true, "NOT_TESTED", null, null, now), created);
        return status();
    }

    public SshConnectionTestResult test() {
        var profile = current(); var now = Instant.now();
        try {
            ssh.current().validate();
            workspace.validateTarget(profile);
            repository.updateTest("READY", null, now);
            return new SshConnectionTestResult(true, target(profile),
                    "部署根目录、固定清单和 SSH 主机指纹检查通过；未读取 .env 内容");
        } catch (Exception exception) {
            var message = safe(exception); repository.updateTest("FAILED", message, now);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "远程部署目标测试失败：" + message);
        }
    }

    String approvalTarget() {
        var profile = current();
        return ssh.current().approvalTarget() + "|DEPLOY:" + profile.remoteDeployRoot()
                + "|COMPOSE:" + profile.composeProject() + "/" + profile.composeFile()
                + "|HEALTH:" + profile.healthUrl();
    }

    String target(RemoteDeploymentProfile profile) {
        var connection = ssh.current();
        return connection.username() + "@" + connection.host() + ":" + connection.port()
                + profile.remoteDeployRoot();
    }

    private RemoteDeploymentProfile normalized(RemoteDeploymentRequest request) {
        return new RemoteDeploymentProfile(trim(request.localSourceRoot()), trim(request.remoteDeployRoot()),
                trim(request.remoteBackupRoot()), trim(request.composeFile()), trim(request.composeProject()),
                trim(request.nginxConfig()).replace('\\', '/'), trim(request.healthUrl()), true,
                "NOT_TESTED", null, null, Instant.now());
    }

    static void validate(RemoteDeploymentProfile profile) {
        try {
            if (!Path.of(profile.localSourceRoot()).isAbsolute()) {
                throw new IllegalArgumentException("本地源码根目录必须是绝对路径");
            }
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "本地源码根目录必须是有效绝对路径");
        }
        try {
            new RemotePathPolicy(profile.remoteDeployRoot());
            new RemotePathPolicy(profile.remoteBackupRoot());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        validateRemoteRootCharacters(profile.remoteDeployRoot(), "部署根目录");
        validateRemoteRootCharacters(profile.remoteBackupRoot(), "备份根目录");
        var deployPrefix = profile.remoteDeployRoot().replaceAll("/+$", "") + "/";
        var backupPrefix = profile.remoteBackupRoot().replaceAll("/+$", "") + "/";
        if (profile.remoteDeployRoot().equals(profile.remoteBackupRoot())
                || profile.remoteDeployRoot().startsWith(backupPrefix)
                || profile.remoteBackupRoot().startsWith(deployPrefix)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "部署根目录和备份根目录必须彼此独立，不能相同或互相嵌套");
        }
        if (!profile.composeFile().matches("[A-Za-z0-9._-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Compose 文件只能是安全文件名");
        }
        if (!profile.composeProject().matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Compose 项目名包含不安全字符");
        }
        validateRelative(profile.nginxConfig(), "Nginx 配置");
        try {
            var uri = URI.create(profile.healthUrl());
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "健康地址必须是固定的 http://127.0.0.1[:端口]/路径");
        }
    }

    static void validateRelative(String value, String label) {
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.split("/").length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "必须是安全相对路径");
        }
        for (var part : value.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9._-]+")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + "包含不安全路径分量");
            }
        }
    }

    private static void validateRemoteRootCharacters(String value, String label) {
        for (var part : value.substring(1).split("/")) {
            if (!part.matches("[A-Za-z0-9._-]+")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, label + "包含不安全字符");
            }
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String safe(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
