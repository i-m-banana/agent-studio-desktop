package com.agentstudio.adapter.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        String releaseRoot() { return profile.remoteDeployRoot().replaceAll("/+$", "") + "-releases"; }

        String validateCandidate(String releaseId) throws Exception {
            if (!releaseId.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}"))
                throw new IllegalArgumentException("候选 ID 格式无效");
            var candidate = releaseRoot() + "/" + releaseId;
            // Include every ancestor, not only the configured root or final child.
            var policy = new RemotePathPolicy("/" + releaseRoot().substring(1).split("/")[0]);
            if (!safeAttributes(candidate, policy).isDirectory())
                throw new IllegalArgumentException("候选目录不存在或不安全");
            for (var name : java.util.List.of("app.jar", "Dockerfile", "compose.yml", "nginx.conf", "manifest.properties", "SHA256SUMS")) {
                if (!safeAttributes(candidate + "/" + name, policy).isRegularFile())
                    throw new IllegalArgumentException("候选文件不存在或不安全：" + name);
            }
            return candidate;
        }

        byte[] readCandidateManifest(String candidate) throws Exception {
            try (var input = sftp.read(candidate + "/manifest.properties")) {
                var bytes = input.readNBytes(16_385);
                if (bytes.length > 16_384) throw new IllegalArgumentException("候选清单超过大小上限");
                return bytes;
            }
        }

        String validateBackup(String backupId) throws Exception {
            if (!backupId.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")) throw new IllegalArgumentException("备份 ID 格式无效");
            var path = profile.remoteBackupRoot().replaceAll("/+$", "") + "/" + backupId;
            var policy = new RemotePathPolicy("/" + path.substring(1).split("/")[0]);
            if (!safeAttributes(path, policy).isDirectory()) throw new SecurityException("备份目录不安全");
            for (var name : java.util.List.of("database.sql", "uploads.tar.gz", "app.jar", "Dockerfile", "compose.yml", "nginx.conf", ".env", "images.json", "services.json", "SHA256SUMS", "manifest.properties", "manifest.sha256"))
                if (!safeAttributes(path + "/" + name, policy).isRegularFile()) throw new SecurityException("备份文件不安全：" + name);
            return path;
        }

        String createBaselineAttempt(String candidate, String id) throws Exception {
            if (!id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("基线任务标识无效");
            var path = candidate + "/baseline-" + id;
            sftp.mkdir(path);
            if (sftp.lstat(path).isSymbolicLink() || !sftp.lstat(path).isDirectory()) throw new SecurityException("基线目录不安全");
            return path;
        }

        String createPublishAttempt(String candidate, String id) throws Exception {
            if (!id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("发布任务标识无效");
            var path = candidate + "/publish-" + id;
            sftp.mkdir(path);
            if (sftp.lstat(path).isSymbolicLink() || !sftp.lstat(path).isDirectory()) throw new SecurityException("发布目录不安全");
            return path;
        }

        String createImageAttempt(String candidate, String attemptId) throws Exception {
            if (!attemptId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("镜像构建标识无效");
            var attempt = candidate + "/image-build-" + attemptId;
            sftp.mkdir(attempt); // exclusive: do not reuse or overwrite any prior attempt
            if (sftp.lstat(attempt).isSymbolicLink() || !sftp.lstat(attempt).isDirectory())
                throw new IllegalArgumentException("镜像构建目录不安全");
            return attempt;
        }

        String createCandidateDirectory(String releaseId) throws Exception {
            if (!releaseId.matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")) {
                throw new IllegalArgumentException("平台生成的 releaseId 格式无效");
            }
            var releaseRoot = releaseRoot();
            var parent = releaseRoot.substring(0, releaseRoot.lastIndexOf('/'));
            var parentAttributes = safeAttributes(parent, new RemotePathPolicy(parent));
            if (!parentAttributes.isDirectory()) throw new IllegalArgumentException("候选版本父目录不是普通目录：" + parent);
            try {
                var attributes = sftp.lstat(releaseRoot);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IllegalArgumentException("候选版本根不是安全目录：" + releaseRoot);
                }
            } catch (IOException missing) {
                sftp.mkdir(releaseRoot);
            }
            var candidate = releaseRoot + "/" + releaseId;
            sftp.mkdir(candidate);
            var created = sftp.lstat(candidate);
            if (created.isSymbolicLink() || !created.isDirectory()) {
                throw new IllegalStateException("候选版本目录创建后校验失败：" + candidate);
            }
            return candidate;
        }

        void uploadExclusive(String candidatePath, String name, Path source) throws Exception {
            var log = org.slf4j.LoggerFactory.getLogger(RemoteDeploymentWorkspace.class);
            var started = System.nanoTime();
            var size = Files.size(source);
            log.info("Release candidate upload file={} bytes={} started", name, size);
            try (var input = Files.newInputStream(source);
                 var output = sftp.write(candidatePath + "/" + safeCandidateName(name),
                         SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)) {
                var buffer = new byte[32768];
                long transferred = 0;
                long nextLog = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("候选上传已中断：" + name);
                    output.write(buffer, 0, count);
                    transferred += count;
                    if (System.nanoTime() >= nextLog) {
                        log.info("Release candidate upload file={} transferred={} total={}", name, transferred, size);
                        nextLog = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                    }
                }
            }
            log.info("Release candidate upload file={} completed durationMs={}", name,
                    java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
        }

        void uploadExclusive(String candidatePath, String name, byte[] contents) throws Exception {
            try (var output = sftp.write(candidatePath + "/" + safeCandidateName(name),
                    SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)) {
                output.write(contents);
            }
        }

        private String safeCandidateName(String name) {
            if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("候选文件名不安全：" + name);
            return name;
        }

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
