package com.agentstudio.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores user secrets as current-Windows-user-bound DPAPI ciphertext. */
@Component
public class SecretStore {
    private final Path root;

    public SecretStore(@Value("${agent-studio.data-dir:../data}") String dataDir) {
        this.root = Path.of(dataDir).toAbsolutePath().normalize().resolve("secrets");
    }

    public boolean supported() { return Platform.isWindows(); }

    public Optional<String> read(String name) {
        if (!supported()) return Optional.empty();
        var path = path(name);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            var clear = Crypt32Util.cryptUnprotectData(Files.readAllBytes(path));
            return Optional.of(new String(clear, StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("无法读取本机安全凭据 " + name, exception);
        }
    }

    public void write(String name, String value) {
        if (!supported()) throw new IllegalStateException("当前系统不支持 Windows DPAPI 安全存储，请使用环境变量");
        try {
            Files.createDirectories(root);
            var encrypted = Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8));
            var target = path(name);
            var temporary = Files.createTempFile(root, ".secret-", ".tmp");
            try {
                Files.write(temporary, encrypted);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException("无法保存本机安全凭据 " + name, exception);
        }
    }

    public boolean delete(String name) {
        try { return Files.deleteIfExists(path(name)); }
        catch (IOException exception) { throw new IllegalStateException("无法删除本机安全凭据 " + name, exception); }
    }

    private Path path(String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".dpapi");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
