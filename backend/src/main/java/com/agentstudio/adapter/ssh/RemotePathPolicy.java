package com.agentstudio.adapter.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RemotePathPolicy {
    private static final Set<String> BLOCKED_DIRECTORIES = Set.of(".git", ".ssh", ".gnupg", ".aws");
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");
    private final String root;

    RemotePathPolicy(String configuredRoot) {
        var normalized = configuredRoot == null ? "" : configuredRoot.trim().replaceAll("/+$", "");
        if (!normalized.startsWith("/") || normalized.equals("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("远程根目录必须是非根目录的绝对 POSIX 路径");
        }
        for (var part : normalized.substring(1).split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("远程根目录包含不安全的路径分量");
            }
        }
        this.root = normalized;
    }

    String resolve(String requested, boolean allowRoot) {
        var relative = normalize(requested);
        if (!allowRoot && relative.isBlank()) throw new IllegalArgumentException("path 必须指向远程工作区内的文件");
        if (!relative.isBlank() && isProtected(relative)) throw new IllegalArgumentException("该远程路径受保护，不能访问");
        return relative.isBlank() ? root : root + "/" + relative;
    }

    String relative(String absolute) {
        if (absolute.equals(root)) return ".";
        if (!absolute.startsWith(root + "/")) throw new IllegalArgumentException("远程路径越出授权根目录");
        return absolute.substring(root.length() + 1);
    }

    boolean isProtected(String relative) {
        var parts = List.of(relative.toLowerCase(Locale.ROOT).split("/"));
        if (parts.stream().anyMatch(BLOCKED_DIRECTORIES::contains)) return true;
        for (int index = 0; index + 1 < parts.size(); index++) {
            if (parts.get(index).equals("data") && parts.get(index + 1).equals("secrets")) return true;
        }
        var name = parts.isEmpty() ? "" : parts.getLast();
        if ((name.equals(".env") || name.startsWith(".env.")) && !name.equals(".env.example")) return true;
        return BLOCKED_SUFFIXES.stream().anyMatch(name::endsWith)
                || name.equals("id_rsa") || name.equals("id_ed25519");
    }

    List<String> prefixes(String absolute) {
        var relative = relative(absolute);
        var result = new ArrayList<String>();
        result.add(root);
        if (relative.equals(".")) return result;
        var cursor = root;
        for (var part : relative.split("/")) {
            cursor += "/" + part;
            result.add(cursor);
        }
        return result;
    }

    private String normalize(String requested) {
        var raw = requested == null || requested.isBlank() ? "." : requested.trim();
        if (raw.startsWith("/") || raw.contains("\\") || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("只允许远程工作区内的 POSIX 相对路径");
        }
        var parts = new ArrayList<String>();
        for (var part : raw.split("/")) {
            if (part.isBlank() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException("路径不能越出远程工作区");
                parts.removeLast();
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }
}
