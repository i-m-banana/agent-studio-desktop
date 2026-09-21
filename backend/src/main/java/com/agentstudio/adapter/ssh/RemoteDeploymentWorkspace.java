package com.agentstudio.adapter.ssh;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

@Component
class RemoteDeploymentWorkspace {
    private final SftpSessionFactory sessions;
    private final SshWorkspaceService ssh;

    RemoteDeploymentWorkspace(SftpSessionFactory sessions, SshWorkspaceService ssh) {
        this.sessions = sessions; this.ssh = ssh;
    }

    void validateTarget(RemoteDeploymentProfile profile) throws Exception {
        execute(profile, (session, access, properties) -> null);
    }

    <T> T execute(RemoteDeploymentProfile profile, Operation<T> operation) throws Exception {
        RemoteDeploymentService.validate(profile);
        var properties = ssh.current(); properties.validate();
        return sessions.executeSession(properties, session -> {
            try (var sftp = SftpClientFactory.instance().createSftpClient(session)) {
                var access = new Access(sftp, profile);
                access.validate();
                return operation.apply(session, access, properties);
            }
        });
    }

    static final class Access {
        private final SftpClient sftp;
        private final RemoteDeploymentProfile profile;
        private final RemotePathPolicy deployPolicy;

        Access(SftpClient sftp, RemoteDeploymentProfile profile) {
            this.sftp = sftp; this.profile = profile;
            this.deployPolicy = new RemotePathPolicy(profile.remoteDeployRoot());
        }

        void validate() throws Exception {
            requireDirectory(profile.remoteDeployRoot(), "部署根目录");
            requireDirectory(profile.remoteBackupRoot(), "备份根目录");
            requireFile(resolve(profile.composeFile()), "Compose 文件");
            requireFile(resolve("Dockerfile"), "Dockerfile");
            requireFile(resolve("app.jar"), "app.jar");
            requireFile(resolve(profile.nginxConfig()), "Nginx 配置");
            requireProtectedEnvironmentFile();
            requireDirectory(resolve("data/uploads"), "上传目录 data/uploads");
        }

        String deployRoot() { return profile.remoteDeployRoot(); }
        String resolve(String relative) { return deployPolicy.resolve(relative, false); }

        private void requireDirectory(String path, String label) throws Exception {
            try {
                var attributes = safeAttributes(path, path.equals(profile.remoteBackupRoot())
                        ? new RemotePathPolicy(profile.remoteBackupRoot()) : deployPolicy);
                if (!attributes.isDirectory()) throw new IllegalArgumentException(label + "不存在或不是目录：" + path);
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException(label + "不存在或无法访问：" + path, exception);
            }
        }

        private void requireFile(String path, String label) throws Exception {
            try {
                var attributes = safeAttributes(path, deployPolicy);
                if (!attributes.isRegularFile()) throw new IllegalArgumentException(label + "不存在或不是普通文件：" + path);
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException(label + "不存在或无法访问：" + path, exception);
            }
        }

        private void requireProtectedEnvironmentFile() throws Exception {
            // .env is deliberately blocked by the general browser policy. Deployment validation may
            // only lstat this exact fixed child; it never opens or returns the file contents.
            try {
                var rootAttributes = sftp.lstat(profile.remoteDeployRoot());
                if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory()) {
                    throw new IllegalArgumentException("部署根目录不存在、不是目录或是符号链接：" + profile.remoteDeployRoot());
                }
                var environmentPath = profile.remoteDeployRoot() + "/.env";
                var attributes = sftp.lstat(environmentPath);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new IllegalArgumentException(".env 不存在、不是普通文件或是符号链接：" + environmentPath);
                }
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException(".env 不存在或无法访问：" + profile.remoteDeployRoot() + "/.env", exception);
            }
        }

        private SftpClient.Attributes safeAttributes(String path, RemotePathPolicy policy) throws Exception {
            for (var prefix : policy.prefixes(path)) {
                var attributes = sftp.lstat(prefix);
                if (attributes.isSymbolicLink()) throw new IllegalArgumentException("不允许部署目标包含符号链接：" + prefix);
            }
            return sftp.lstat(path);
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T apply(org.apache.sshd.client.session.ClientSession session, Access access,
                SshWorkspaceProperties properties) throws Exception;
    }
}
