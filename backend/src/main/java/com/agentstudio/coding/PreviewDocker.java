package com.agentstudio.coding;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** CLI is controlled by the platform; no socket, host network or user command enters the workload. */
@Component
public class PreviewDocker {
    final String executable, image;
    final String host = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
            ? "npipe:////./pipe/dockerDesktopLinuxEngine" : "unix:///var/run/docker.sock";
    public PreviewDocker(@Value("${agent-studio.projects.docker-executable:}") String executable,
                         @Value("${agent-studio.projects.sandbox-image:}") String image) {
        this.executable = executable; this.image = image;
    }
    void requireConfigured() throws Exception {
        if (executable.isBlank() || !Path.of(executable).isAbsolute() || !image.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalStateException("预览需要本机 Docker 绝对路径及固定 sha256 离线验证镜像");
        Path.of(executable).toRealPath();
    }
    Process spawn(List<String> arguments) throws Exception {
        var command = new ArrayList<String>(List.of(executable, "--host", host)); command.addAll(arguments);
        var builder = new ProcessBuilder(command);
        // Docker config may otherwise contain user credentials or remote contexts.
        builder.environment().remove("DOCKER_HOST"); builder.environment().remove("DOCKER_CONTEXT");
        return builder.start();
    }
    String run(List<String> arguments, int seconds) throws Exception {
        return run(arguments,seconds,false);
    }
    String run(List<String> arguments, int seconds,boolean allowFailure) throws Exception {
        var process = spawn(arguments); process.getOutputStream().close();
        var out = new StringBuilder();
        var reader = Thread.startVirtualThread(() -> { try (var input = process.getInputStream()) {
            var bytes = new byte[2048]; int n;
            while ((n = input.read(bytes)) >= 0) synchronized (out) {
                out.append(new String(bytes,0,n,java.nio.charset.StandardCharsets.UTF_8));if(out.length()>16000)out.delete(0,out.length()-16000);
            }
        } catch (Exception ignored) {} });
        var errors = new StringBuilder();
        var error = Thread.startVirtualThread(() -> { try (var input=process.getErrorStream()) {
            var bytes=new byte[1024]; int n;
            while((n=input.read(bytes))>=0) synchronized(errors) {
                if(errors.length()<4096)errors.append(new String(bytes,0,Math.min(n,4096-errors.length()),java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {} });
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IllegalStateException("预览容器操作超时");
            reader.join(1000); error.join(1000);
            if (process.exitValue() != 0 && !allowFailure) throw new IllegalStateException("预览容器操作失败，退出码 " + process.exitValue()+": "+errors.toString().trim());
            return (out.toString() + (!arguments.isEmpty() && arguments.getFirst().equals("logs")
                    ? "\n" + errors : "")).trim();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    boolean owned(String kind, String name, String owner) throws Exception {
        try {
            var json=run(List.of(kind, "inspect", "--format", "{{json ." + (kind.equals("container") ? "Config." : "") + "Labels}}", name), 10);
            var label=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("agent-studio.preview").asText();
            if(!label.equals(owner)) throw new IllegalStateException("拒绝清理非本次预览资源");
            return true;
        }
        catch (IllegalStateException e) {
            // Missing resource is fine; a failed engine is not proof of successful cleanup.
            var ids = run(List.of(kind, "ls", kind.equals("container") ? "--all" : "--quiet", "--filter", "name=" + name, "--format", kind.equals("container") ? "{{.Names}}" : "{{.Name}}"), 10);
            if (ids.isBlank()) return false;
            throw new IllegalStateException("预览资源身份无法确认，停止清理");
        }
    }
}
