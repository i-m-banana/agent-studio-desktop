package com.agentstudio.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CodingWorkspace {
    private static final Set<String> BLOCKED_DIRECTORIES = Set.of(".git", ".ssh", ".gnupg", ".aws");
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");

    private final Path configuredRoot;

    public CodingWorkspace(@Value("${agent-studio.coding.workspace-root:../coding-workspace}") String root) {
        if (root == null || root.isBlank()) throw new IllegalArgumentException("代码工作区根目录不能为空");
        this.configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public Path root() throws IOException {
        if (!Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("代码工作区不存在，请配置 AGENT_STUDIO_CODING_WORKSPACE：" + configuredRoot);
        }
        if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("代码工作区不是目录：" + configuredRoot);
        }
        return configuredRoot.toRealPath();
    }

    public Path requireDirectory(String requestedPath) throws IOException {
        var target = resolveExisting(requestedPath, true);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("路径不是目录：" + display(requestedPath));
        }
        return target;
    }

    public Path requireRegularFile(String requestedPath) throws IOException {
        var target = resolveExisting(requestedPath, false);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("路径不是普通文件：" + display(requestedPath));
        }
        return target;
    }

    public boolean isProtected(Path path) throws IOException {
        return isProtected(path, root());
    }

    boolean isProtected(Path path, Path resolvedRoot) {
        var relative = resolvedRoot.relativize(path.toAbsolutePath().normalize());
        var parts = new java.util.ArrayList<String>();
        relative.forEach(part -> parts.add(part.toString().toLowerCase(Locale.ROOT)));
        if (parts.stream().anyMatch(BLOCKED_DIRECTORIES::contains)) return true;
        for (int index = 0; index + 1 < parts.size(); index++) {
            if ("data".equals(parts.get(index)) && "secrets".equals(parts.get(index + 1))) return true;
        }
        if (parts.isEmpty()) return false;
        var fileName = parts.getLast();
        if ((fileName.equals(".env") || fileName.startsWith(".env.")) && !fileName.equals(".env.example")) {
            return true;
        }
        return BLOCKED_SUFFIXES.stream().anyMatch(fileName::endsWith)
                || fileName.equals("id_rsa") || fileName.equals("id_ed25519");
    }

    public boolean isSafeEntry(Path path) {
        try {
            return isSafeEntry(path, root());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    boolean isSafeEntry(Path path, Path resolvedRoot) {
        try {
            if (Files.isSymbolicLink(path) || isProtected(path, resolvedRoot)) return false;
            return path.toRealPath().startsWith(resolvedRoot);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    public String relative(Path path) throws IOException {
        return relative(path, root());
    }

    String relative(Path path, Path resolvedRoot) {
        var value = resolvedRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return value.isBlank() ? "." : value;
    }

    private Path resolveExisting(String requestedPath, boolean allowRoot) throws IOException {
        var raw = requestedPath == null || requestedPath.isBlank() ? "." : requestedPath.trim();
        if (raw.indexOf(':') >= 0) throw new IllegalArgumentException("相对路径不能包含冒号或 Windows 数据流语法");
        final Path relative;
        try {
            relative = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("路径格式无效", exception);
        }
        if (relative.isAbsolute()) throw new IllegalArgumentException("只允许代码工作区内的相对路径");
        var normalized = relative.normalize();
        if (normalized.startsWith("..")) throw new IllegalArgumentException("路径不能越出代码工作区");
        var root = root();
        var lexicalTarget = root.resolve(normalized).normalize();
        if (!lexicalTarget.startsWith(root)) throw new IllegalArgumentException("路径不能越出代码工作区");
        if (!Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("路径不存在：" + display(requestedPath));
        }
        var cursor = root;
        for (var part : normalized) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("不允许访问符号链接路径");
        }
        var realTarget = lexicalTarget.toRealPath();
        if (!realTarget.startsWith(root)) throw new IllegalArgumentException("路径不能越出代码工作区");
        if ((!allowRoot || !normalized.toString().isBlank()) && isProtected(realTarget)) {
            throw new IllegalArgumentException("该路径受保护，Coding 工具不能访问");
        }
        return realTarget;
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "." : value;
    }
}
