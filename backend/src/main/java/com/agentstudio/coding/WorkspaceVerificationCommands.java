package com.agentstudio.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WorkspaceVerificationCommands {
    private final String configuredMaven;
    private final String configuredNpm;

    WorkspaceVerificationCommands(
            @Value("${agent-studio.coding.maven-command:}") String configuredMaven,
            @Value("${agent-studio.coding.npm-command:}") String configuredNpm) {
        this.configuredMaven = configuredMaven;
        this.configuredNpm = configuredNpm;
    }

    VerificationCommand resolve(String task) throws Exception {
        var windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
        var executable = switch (task) {
            case "MAVEN_TEST" -> resolveExecutable(configuredMaven, windows ? "mvn.cmd" : "mvn", windows);
            case "NPM_TEST", "NPM_BUILD" -> resolveExecutable(configuredNpm, windows ? "npm.cmd" : "npm", windows);
            default -> throw new IllegalArgumentException("不支持的验证任务：" + task);
        };
        var fixedArguments = switch (task) {
            case "MAVEN_TEST" -> List.of("--batch-mode", "--no-transfer-progress", "test");
            case "NPM_TEST" -> List.of("test");
            case "NPM_BUILD" -> List.of("run", "build");
            default -> throw new IllegalArgumentException("不支持的验证任务：" + task);
        };
        if (!windows) {
            var command = new ArrayList<String>();
            command.add(executable.toString());
            command.addAll(fixedArguments);
            return new VerificationCommand(List.copyOf(command), executable);
        }
        var commandProcessor = resolveCommandProcessor();
        var shellCommand = new StringBuilder("call \"").append(executable).append('"');
        fixedArguments.forEach(argument -> shellCommand.append(' ').append(argument));
        return new VerificationCommand(
                List.of(commandProcessor.toString(), "/d", "/s", "/c", shellCommand.toString()), executable);
    }

    private Path resolveExecutable(String configured, String fallbackName, boolean windows) throws Exception {
        var value = configured == null || configured.isBlank() ? fallbackName : configured.trim();
        if (value.indexOf('"') >= 0) throw new IllegalStateException("验证程序路径不能包含引号");
        var requested = Path.of(value);
        if (requested.isAbsolute()) return requireExecutable(requested, windows);
        if (requested.getNameCount() != 1) throw new IllegalStateException("验证程序必须配置为绝对路径或 PATH 中的程序名");
        var pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (var directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (directory.isBlank()) continue;
                var candidate = Path.of(directory).resolve(value);
                if (Files.isRegularFile(candidate)) return requireExecutable(candidate, windows);
            }
        }
        throw new IllegalStateException("找不到验证程序：" + value);
    }

    private Path requireExecutable(Path candidate, boolean windows) throws Exception {
        var real = candidate.toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(real)) throw new IllegalStateException("验证程序不是普通文件：" + candidate);
        if (!windows && !Files.isExecutable(real)) throw new IllegalStateException("验证程序不可执行：" + candidate);
        if (windows && (real.toString().indexOf('%') >= 0 || real.toString().indexOf('\r') >= 0
                || real.toString().indexOf('\n') >= 0 || real.toString().indexOf('"') >= 0)) {
            throw new IllegalStateException("验证程序路径包含不安全的命令处理器字符");
        }
        return real;
    }

    private Path resolveCommandProcessor() throws Exception {
        var configured = System.getenv("ComSpec");
        var candidate = configured == null || configured.isBlank()
                ? Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe")
                : Path.of(configured);
        return requireExecutable(candidate, true);
    }
}

record VerificationCommand(List<String> arguments, Path executable) {
}
