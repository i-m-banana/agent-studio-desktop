package com.agentstudio.adapter.ssh;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
class LocalReleaseCandidateBuilder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LocalReleaseCandidateBuilder.class);
    private static final int OUTPUT_LIMIT = 24_000;
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "SystemRoot", "WINDIR", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP",
            "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "HOME", "LANG", "LC_ALL");
    private final Duration timeout;
    @Autowired private com.agentstudio.coding.IsolatedProjectRunner isolated;

    @Autowired
    LocalReleaseCandidateBuilder() { this(Duration.ofMinutes(4)); }
    LocalReleaseCandidateBuilder(Duration timeout) { this.timeout = timeout; }

    BuildResult build(RemoteDeploymentProfile profile) throws Exception {
        var root = requireSafeRoot(profile.localSourceRoot());
        requireFile(root, "pom.xml");
        requireFile(root, "Dockerfile");
        requireFile(root, profile.localComposeFile());
        requireFile(root, profile.nginxConfig());
        if (isolated != null) {
            var project=com.agentstudio.project.ProjectExecutionContext.current();
            if(project==null || !root.equals(Path.of(project.sourceRoot()))) throw new IllegalArgumentException("发布构建必须绑定同源本地项目");
            var result=isolated.run(".","RELEASE_PACKAGE");
            return new BuildResult(result.successful(),"ISOLATED_BUILD",result.exitCode(),result.durationMs(),
                    result.output(),result.truncated(),result.snapshotRoot(),result.artifact(),result.sourceSha256());
        }
        var artifact = root.resolve("target/app.jar").normalize();
        if (!artifact.startsWith(root)) throw new IllegalStateException("固定构建制品路径越界");
        var target = root.resolve("target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("固定构建目录 target 不得是符号链接");
        }

        var test = run(root, "TEST", "clean", "test");
        if (test.exitCode() != 0) return new BuildResult(false, "MAVEN_TEST", test.exitCode(),
                test.durationMs(), test.output(), test.truncated(), root, artifact);
        var pack = run(root, "PACKAGE", "package", "-DskipTests");
        var output = test.output() + "\n--- MAVEN_PACKAGE ---\n" + pack.output();
        var truncated = test.truncated() || pack.truncated() || output.length() > OUTPUT_LIMIT;
        if (output.length() > OUTPUT_LIMIT) output = output.substring(0, OUTPUT_LIMIT / 2)
                + "\n... 输出已截断 ...\n" + output.substring(output.length() - OUTPUT_LIMIT / 2);
        if (pack.exitCode() != 0) return new BuildResult(false, "MAVEN_PACKAGE", pack.exitCode(),
                test.durationMs() + pack.durationMs(), output, truncated, root, artifact);
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact)) {
            throw new IllegalStateException("Maven 成功后未生成固定制品 target/app.jar");
        }
        requireNoSymbolicPrefixes(root, artifact);
        return new BuildResult(true, "LOCAL_BUILD", 0, test.durationMs() + pack.durationMs(),
                output, truncated, root, artifact);
    }

    private CommandResult run(Path root, String stage, String... goals) throws Exception {
        var executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "mvn.cmd" : "mvn";
        var command = new java.util.ArrayList<String>(); command.add(executable);
        command.add("-B"); command.add("--no-transfer-progress"); command.addAll(java.util.List.of(goals));
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        sanitize(builder.environment());
        var started = System.nanoTime(); var process = builder.start(); var output = new BoundedText(OUTPUT_LIMIT);
        process.getOutputStream().close();
        LOG.info("Release candidate stage={} started, timeoutSeconds={}", stage, timeout.toSeconds());
        var readFailure = new AtomicReference<Exception>();
        var reader = Thread.startVirtualThread(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                var buffer = new char[2048]; int count;
                while ((count = input.read(buffer)) >= 0) output.append(buffer, count);
            } catch (Exception exception) { readFailure.set(exception); }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process); throw new IllegalStateException(stage + " 超过 " + timeout.toSeconds() + " 秒，已终止进程树；输出：\n" + output.value());
            }
        } catch (InterruptedException exception) {
            terminate(process); Thread.currentThread().interrupt(); throw exception;
        } finally {
            if (process.isAlive()) terminate(process);
            reader.join(2_000);
        }
        if (readFailure.get() != null) throw new IllegalStateException("读取 Maven 输出失败", readFailure.get());
        LOG.info("Release candidate stage={} exitCode={} durationMs={}", stage, process.exitValue(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return new CommandResult(process.exitValue(), Duration.ofNanos(System.nanoTime() - started).toMillis(),
                output.value(), output.truncated());
    }

    private Path requireSafeRoot(String configured) throws Exception {
        var path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("本地源码根不存在、不是目录或是符号链接：" + path);
        }
        var real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(path)) throw new IllegalArgumentException("本地源码根包含不安全的路径重定向：" + path);
        return path;
    }

    private void requireFile(Path root, String relative) throws Exception {
        RemoteDeploymentService.validateRelative(relative, "本地发布文件");
        var file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("本地发布文件不存在、不是普通文件或是符号链接：" + relative);
        }
        requireNoSymbolicPrefixes(root, file);
    }

    private void requireNoSymbolicPrefixes(Path root, Path file) {
        var current = root;
        for (var part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("本地发布路径包含符号链接：" + root.relativize(current));
            }
        }
    }

    private void sanitize(Map<String, String> environment) {
        var original = Map.copyOf(environment); environment.clear();
        var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        original.forEach((name, value) -> {
            if (windows ? ALLOWED_ENVIRONMENT.stream().anyMatch(item -> item.equalsIgnoreCase(name))
                    : ALLOWED_ENVIRONMENT.contains(name)) environment.put(name, value);
        });
        environment.put("CI", "true"); environment.put("NO_COLOR", "1");
    }

    private void terminate(Process process) {
        process.toHandle().descendants().forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    record BuildResult(boolean successful, String stage, int exitCode, long durationMs, String output,
                       boolean outputTruncated, Path sourceRoot, Path artifact, String sourceSha256) {
        BuildResult(boolean successful,String stage,int exitCode,long durationMs,String output,boolean outputTruncated,Path sourceRoot,Path artifact) {
            this(successful,stage,exitCode,durationMs,output,outputTruncated,sourceRoot,artifact,null);
        }
    }
    private record CommandResult(int exitCode, long durationMs, String output, boolean truncated) {}

    private static final class BoundedText {
        private final int limit; private final StringBuilder value = new StringBuilder(); private boolean truncated;
        BoundedText(int limit) { this.limit = limit; }
        synchronized void append(char[] buffer, int count) {
            value.append(buffer, 0, count);
            if (value.length() > limit) { value.delete(limit / 2, value.length() - limit / 2); truncated = true; }
        }
        synchronized String value() { return truncated ? value.substring(0, limit / 2)
                + "\n... 输出已截断 ...\n" + value.substring(limit / 2) : value.toString(); }
        synchronized boolean truncated() { return truncated; }
    }
}
