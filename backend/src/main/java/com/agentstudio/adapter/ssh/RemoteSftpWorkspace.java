package com.agentstudio.adapter.ssh;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class RemoteSftpWorkspace {
    static final int MAX_FILE_BYTES = 1_048_576;
    private static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES = Set.of(
            "node_modules", "target", "dist", "build", "out", "coverage", ".idea", ".run");
    private final SftpSessionFactory sessions;
    private final Supplier<SshWorkspaceProperties> configurations;

    @Autowired
    public RemoteSftpWorkspace(SftpSessionFactory sessions, SshWorkspaceService service) {
        this.sessions = sessions; this.configurations = service::current;
    }

    RemoteSftpWorkspace(SftpSessionFactory sessions, SshWorkspaceProperties properties) {
        this.sessions = sessions; this.configurations = () -> properties;
    }

    <T> T execute(Operation<T> operation) throws Exception {
        var properties = configurations.get(); properties.validate();
        var policy = new RemotePathPolicy(properties.remoteRoot());
        return sessions.execute(properties, sftp -> operation.apply(new Access(sftp, policy)));
    }

    <T> T executeWithSession(SessionOperation<T> operation) throws Exception {
        var properties = configurations.get(); properties.validate();
        var policy = new RemotePathPolicy(properties.remoteRoot());
        return sessions.executeSession(properties, session -> {
            try (var sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return operation.apply(session, new Access(sftp, policy), properties);
            }
        });
    }

    String target() { var properties = configurations.get(); properties.validate(); return properties.target(); }

    final class Access {
        private final SftpClient sftp;
        private final RemotePathPolicy policy;

        Access(SftpClient sftp, RemotePathPolicy policy) { this.sftp = sftp; this.policy = policy; }

        String requireDirectory(String requested) throws Exception {
            var path = policy.resolve(requested, true);
            var attributes = safeAttributes(path);
            if (!attributes.isDirectory()) throw new IllegalArgumentException("远程路径不是目录：" + requested);
            return path;
        }

        String requireFile(String requested) throws Exception {
            var path = policy.resolve(requested, false);
            var attributes = safeAttributes(path);
            if (!attributes.isRegularFile()) throw new IllegalArgumentException("远程路径不是普通文件：" + requested);
            return path;
        }

        List<RemoteWorkspaceEntry> list(String directory) throws Exception {
            var directoryAttributes = safeAttributes(directory);
            if (!directoryAttributes.isDirectory()) throw new IllegalArgumentException("远程路径不是目录");
            var entries = new ArrayList<RemoteWorkspaceEntry>();
            for (var entry : sftp.readDir(directory)) {
                var name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                var path = directory + "/" + name;
                var relative = policy.relative(path);
                if (policy.isProtected(relative)) continue;
                var attributes = entry.getAttributes();
                var type = attributes.isSymbolicLink() ? "SYMLINK"
                        : attributes.isDirectory() ? "DIRECTORY"
                        : attributes.isRegularFile() ? "FILE" : "OTHER";
                entries.add(new RemoteWorkspaceEntry(name, relative, type,
                        attributes.isRegularFile() ? attributes.getSize() : 0));
            }
            entries.sort(Comparator.comparing((RemoteWorkspaceEntry entry) -> !entry.type().equals("DIRECTORY"))
                    .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
            return entries;
        }

        byte[] read(String file) throws Exception {
            var size = safeAttributes(file).getSize();
            if (size > MAX_FILE_BYTES) throw new IllegalArgumentException("远程文本文件不能超过 1 MiB");
            try (var input = sftp.read(file); var output = new ByteArrayOutputStream()) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > MAX_FILE_BYTES) {
                        throw new IllegalArgumentException("远程文本文件不能超过 1 MiB");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }

        void replaceIfUnchanged(String file, String expectedSha256, byte[] updatedBytes) throws Exception {
            var originalAttributes = safeAttributes(file);
            if (!originalAttributes.isRegularFile()) throw new IllegalArgumentException("远程路径不是普通文件");
            var slash = file.lastIndexOf('/');
            var parent = slash > 0 ? file.substring(0, slash) : "/";
            var temporary = parent + "/.agent-studio-patch-" + UUID.randomUUID() + ".tmp";
            var temporaryCreated = false;
            try {
                try (var output = sftp.write(temporary, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                        SftpClient.OpenMode.Exclusive)) {
                    temporaryCreated = true;
                    output.write(updatedBytes);
                }
                sftp.setStat(temporary, new SftpClient.Attributes().perms(originalAttributes.getPermissions()));

                var latestBytes = read(file);
                if (!sha256(latestBytes).equalsIgnoreCase(expectedSha256)) {
                    throw new IllegalStateException("远程文件在补丁执行期间发生变化，未应用补丁");
                }
                atomicReplace(temporary, file);
                temporaryCreated = false;
            } finally {
                if (temporaryCreated) {
                    try { sftp.remove(temporary); } catch (java.io.IOException ignored) { }
                }
            }
        }

        private void atomicReplace(String source, String target) {
            try {
                // OpenSSH intentionally stays on SFTP v3 and advertises POSIX atomic rename as an extension.
                var posixRename = sftp.getExtension(OpenSSHPosixRenameExtension.class);
                if (posixRename != null && posixRename.isSupported()) {
                    posixRename.posixRename(source, target);
                    return;
                }
                if (sftp.getVersion() >= 5) {
                    sftp.rename(source, target, SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
                    return;
                }
                throw new IllegalStateException("SFTP v" + sftp.getVersion()
                        + " 未提供 posix-rename@openssh.com，无法安全原子替换文件");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("远程服务器的原子文件替换失败，未应用补丁", exception);
            }
        }

        int permissions(String file) throws Exception { return safeAttributes(file).getPermissions(); }

        void requireProjectMarker(String directory, String markerName, boolean directoryMarker) throws Exception {
            var marker = directory + "/" + markerName;
            try {
                var attributes = safeAttributes(marker);
                var valid = directoryMarker ? attributes.isDirectory() : attributes.isRegularFile();
                if (!valid) throw new IllegalArgumentException("远程目录中缺少安全的 " + markerName);
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("远程目录中缺少或无法安全访问 " + markerName, exception);
            }
        }
        int sftpVersion() { return sftp.getVersion(); }
        boolean supportsPosixRename() {
            var extension = sftp.getExtension(OpenSSHPosixRenameExtension.class);
            return extension != null && extension.isSupported();
        }

        String relative(String path) { return policy.relative(path); }
        boolean excludedDirectory(String name) {
            return DEFAULT_EXCLUDED_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
        }

        private SftpClient.Attributes safeAttributes(String path) throws Exception {
            for (var prefix : policy.prefixes(path)) {
                var attributes = sftp.lstat(prefix);
                if (attributes.isSymbolicLink()) throw new IllegalArgumentException("不允许访问远程符号链接路径");
            }
            return sftp.lstat(path);
        }
    }

    static String decodeUtf8(byte[] bytes) {
        for (var value : bytes) if (value == 0) throw new IllegalArgumentException("远程文件包含二进制 NUL 字节");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("远程文件不是有效的 UTF-8 文本", exception);
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    @FunctionalInterface
    interface Operation<T> { T apply(Access access) throws Exception; }

    @FunctionalInterface
    interface SessionOperation<T> {
        T apply(org.apache.sshd.client.session.ClientSession session, Access access,
                SshWorkspaceProperties properties) throws Exception;
    }
}
