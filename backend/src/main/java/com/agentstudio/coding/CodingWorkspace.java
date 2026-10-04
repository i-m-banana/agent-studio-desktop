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
        var project = com.agentstudio.project.ProjectExecutionContext.current();
        if (project != null) return requireUnredirectedDirectory(Path.of(project.sourceRoot()));
        if (!Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("代码工作区不存在，请配置 AGENT_STUDIO_CODING_WORKSPACE：" + configuredRoot);
        }
        if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("代码工作区不是目录：" + configuredRoot);
        }
        return requireUnredirectedDirectory(configuredRoot);
    }

    public static Path requireUnredirectedDirectory(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        var cursor = path.getRoot();
        for (var part : path) {
            cursor = cursor.resolve(part);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cursor)
                    || !cursor.toRealPath().equals(cursor))
                throw new IllegalArgumentException("目录不存在或包含路径重定向：" + cursor);
        }
        return path;
    }

    public static Path safeRelative(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("相对路径不能为空");
        var raw = value.replace('\\', '/');
        if (raw.startsWith("/") || raw.indexOf(':') >= 0) throw new IllegalArgumentException("只允许工作区相对路径");
        var path = Path.of(raw);
        for (var part : path) {
            var name = part.toString();
            if (name.equals(".")) continue;
            if (name.equals("..") || name.endsWith(".") || name.endsWith(" ") || name.split("\\.",2)[0].strip().matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
                throw new IllegalArgumentException("路径不能越出代码工作区（越界），或含别名、设备名");
        }
        if (path.isAbsolute()) throw new IllegalArgumentException("只允许工作区相对路径");
        return path.normalize();
    }
    public static String directoryIdentity(Path path) throws Exception { return GuardedTextFiles.directoryIdentity(path); }

    public void requireWritable(Path path) throws IOException {
        var project = com.agentstudio.project.ProjectExecutionContext.current();
        if (project == null) return; // Compatibility for direct, isolated unit-test fixtures.
        var root = root();
        if (!path.startsWith(root) || isProtected(path, root)) throw new IllegalArgumentException("目标受保护或越出工作区");
        if (project.writableDirectories().stream().map(root::resolve).noneMatch(path::startsWith))
            throw new IllegalArgumentException("目标不在允许修改的源码/测试子目录内");
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
        try { GuardedTextFiles.verifyRegularFiles(java.util.List.of(target),new com.fasterxml.jackson.databind.ObjectMapper()); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("文件安全检查失败："+e.getMessage(),e); }
        return target;
    }

    public boolean isProtected(Path path) throws IOException {
        return isProtected(path, root());
    }

    boolean isProtected(Path path, Path resolvedRoot) {
        var project = com.agentstudio.project.ProjectExecutionContext.current();
        if (project != null && project.protectedDirectories().stream().map(resolvedRoot::resolve).anyMatch(path::startsWith)) return true;
        if(project!=null && java.util.Set.of(".db",".sqlite",".sqlite3").stream().anyMatch(s -> path.getFileName()!=null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(s))) return true;
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
            return path.toRealPath().equals(path.toAbsolutePath().normalize()) && path.toRealPath().startsWith(resolvedRoot);
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
            relative = safeRelative(raw);
        } catch (IllegalArgumentException exception) {
            throw exception;
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
            if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor)) throw new IllegalArgumentException("不允许访问符号链接或路径重定向");
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
