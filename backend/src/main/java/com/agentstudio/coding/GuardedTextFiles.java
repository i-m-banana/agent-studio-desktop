package com.agentstudio.coding;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Use directory handles, not a check followed by an unguarded path write. */
final class GuardedTextFiles {
    private GuardedTextFiles() {}
    static void verifyRegularFiles(List<Path> files,ObjectMapper mapper) throws Exception {
        if(com.agentstudio.project.ProjectExecutionContext.current()==null || files.isEmpty()) return;
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")) {
            runWindows(Map.of("mode","verify","paths",files.stream().map(Path::toString).toList()),mapper);
        } else {
            for(var file:files)if(((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)
                throw new IllegalArgumentException("硬链接文件不能进入编码工作区");
        }
    }
    static void create(Path parent,String name,byte[] content,ObjectMapper mapper) throws Exception {
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")) {
            runWindows(Map.of("parent",parent.toString(),"name",name,"content",Base64.getEncoder().encodeToString(content)),mapper);
            return;
        }
        createSecure(parent,name,content);
    }
    static void patch(Path parent,String name,byte[] content,String expected,ObjectMapper mapper) throws Exception {
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")) {
            runWindows(Map.of("mode","patch","parent",parent.toString(),"name",name,"content",Base64.getEncoder().encodeToString(content),"expected",expected),mapper);
            return;
        }
        var streams=new ArrayList<DirectoryStream<Path>>();
        try {
            DirectoryStream<Path> current=Files.newDirectoryStream(parent.getRoot());streams.add(current);
            if(!(current instanceof SecureDirectoryStream<Path>))throw new IllegalStateException("文件系统不支持安全目录句柄，补丁未应用");
            for(var part:parent){current=((SecureDirectoryStream<Path>)current).newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);streams.add(current);}
            var directory=(SecureDirectoryStream<Path>)current;
            var target=Path.of(name);var temporary=Path.of(".agent-studio-patch-"+UUID.randomUUID()+".tmp");
            try {
                try(var channel=directory.newByteChannel(target,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                    if(channel.size()>WorkspaceTextFiles.MAX_FILE_BYTES)throw new IllegalArgumentException("文件过大");
                    var buffer=ByteBuffer.allocate((int)channel.size());while(buffer.hasRemaining() && channel.read(buffer)>=0){}
                    if(!WorkspaceTextFiles.sha256(buffer.array()).equals(expected))throw new IllegalStateException("文件变化，补丁未应用");
                }
                try(var channel=directory.newByteChannel(temporary,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS))) {
                    var buffer=ByteBuffer.wrap(content);while(buffer.hasRemaining())channel.write(buffer);
                }
                directory.move(temporary,directory,target);
            } finally { try{directory.deleteFile(temporary);}catch(NoSuchFileException ignored){} }
        } finally { Collections.reverse(streams);for(var stream:streams)stream.close(); }
    }
    static String directoryIdentity(Path path) throws Exception {
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")) {
            var result=runWindows(Map.of("mode","identity","path",path.toString()),new ObjectMapper());
            return result.lines().filter(s->s.startsWith("IDENTITY:")).findFirst().orElseThrow(()->new IllegalStateException("目录身份缺失")).substring(9);
        }
        var value=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(value==null)throw new IllegalStateException("文件系统不提供稳定目录身份，工作区已禁用");return value.toString();
    }
    private static String runWindows(Map<String,Object> payload,ObjectMapper mapper) throws Exception {
            var executable=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe");
            String code;
            try(var input=GuardedTextFiles.class.getResourceAsStream("/guarded-create.ps1")) {
                if(input==null) throw new IllegalStateException("安全文件创建组件缺失");
                code=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
            var encoded=Base64.getEncoder().encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            var process=new ProcessBuilder(executable.toString(),"-NoProfile","-NonInteractive","-EncodedCommand",encoded).redirectErrorStream(true).start();
            try {
                try(var out=process.getOutputStream()) { out.write(mapper.writeValueAsBytes(payload)); }
                var result=new java.util.concurrent.atomic.AtomicReference<String>("");
                var reader=Thread.startVirtualThread(()->{try{result.set(new String(process.getInputStream().readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}});
                if(!process.waitFor(20,TimeUnit.SECONDS)) throw new IllegalStateException("安全文件创建超时");
                reader.join(2000);
                if(process.exitValue()!=0 || !result.get().contains("CREATED")) throw new IllegalStateException("安全文件创建失败，未获得成功回执："+result.get());
                return result.get();
            } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private static void createSecure(Path parent,String name,byte[] content) throws Exception {
        var streams=new ArrayList<DirectoryStream<Path>>();
        try {
            DirectoryStream<Path> current=Files.newDirectoryStream(parent.getRoot()); streams.add(current);
            if(!(current instanceof SecureDirectoryStream<Path>)) throw new IllegalStateException("文件系统不支持安全目录句柄，已禁用新建文件");
            for(var part:parent) {
                current=((SecureDirectoryStream<Path>)current).newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS); streams.add(current);
            }
            var directory=(SecureDirectoryStream<Path>)current;
            boolean created=false;
            try(var channel=directory.newByteChannel(Path.of(name),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))) {
                created=true; var buffer=ByteBuffer.wrap(content); while(buffer.hasRemaining())channel.write(buffer);
            } catch(Exception e) { if(created)directory.deleteFile(Path.of(name)); throw e; }
        } finally { Collections.reverse(streams); for(var stream:streams)stream.close(); }
    }
}
