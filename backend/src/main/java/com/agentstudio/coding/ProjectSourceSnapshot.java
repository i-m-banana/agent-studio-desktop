package com.agentstudio.coding;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Bounded source-only snapshot; runtime data, secrets and generated output never enter the container. */
public final class ProjectSourceSnapshot {
    private static final Set<String> GENERATED = Set.of(".git","node_modules","target","dist","build","out","coverage",".gradle",".next",".run",".venv","__pycache__");
    private static boolean generatedArtifact(Path path) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        return List.of(".jar",".war",".ear",".class",".pyc",".zip",".tar",".gz",".tgz",".7z",".rar",".bz2",".xz",".zst").stream().anyMatch(name::endsWith);
    }
    private ProjectSourceSnapshot() {}
    public static String fingerprint(CodingWorkspace workspace) throws Exception { return copy(workspace,null); }
    public static String copy(CodingWorkspace workspace,Path destination) throws Exception {
        var root=workspace.root(); var files=new ArrayList<Path>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            int count;
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs) throws java.io.IOException {
                if(!directory.equals(root) && (GENERATED.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT)) || workspace.isProtected(directory))) return FileVisitResult.SKIP_SUBTREE;
                if(!workspace.isSafeEntry(directory)) throw new java.io.IOException("源码含路径重定向："+root.relativize(directory));
                if(++count>20000) throw new java.io.IOException("源码目录过大，最多 20000 个条目");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws java.io.IOException {
                if(workspace.isProtected(file) || generatedArtifact(file)) return FileVisitResult.CONTINUE;
                if(!attrs.isRegularFile() || !workspace.isSafeEntry(file)) throw new java.io.IOException("源码包含非普通文件或链接："+root.relativize(file));
                if(++count>20000) throw new java.io.IOException("源码条目过多");
                files.add(file); return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(root::relativize));
        GuardedTextFiles.verifyRegularFiles(files,new com.fasterxml.jackson.databind.ObjectMapper());
        var digest=MessageDigest.getInstance("SHA-256"); long total=0;
        for(var file:files) {
            if(Files.size(file)>8*1024*1024) throw new IllegalArgumentException("源码单文件超过 8 MiB："+root.relativize(file));
            var bytes=Files.readAllBytes(file); total+=bytes.length;
            if(total>128*1024*1024) throw new IllegalArgumentException("源码快照超过 128 MiB");
            var relative=root.relativize(file);
            digest.update(relative.toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0);
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length).array()); digest.update(bytes);
            if(destination!=null) { var target=destination.resolve(relative); Files.createDirectories(target.getParent()); Files.write(target,bytes,StandardOpenOption.CREATE_NEW); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
