package com.agentstudio.adapter.ssh;

import java.util.Map;
import java.util.Set;

final class RemoteExecCommands {
    private static final Set<String> TASKS = Set.of(
            "GIT_STATUS", "GIT_DIFF_SUMMARY", "MAVEN_TEST", "NPM_TEST", "NPM_BUILD");
    private static final Map<String, String> FIXED_COMMANDS = Map.of(
            "GIT_STATUS", "git --no-pager status --short --branch --untracked-files=normal",
            "GIT_DIFF_SUMMARY", "git --no-pager diff --stat HEAD -- .",
            "MAVEN_TEST", "CI=true NO_COLOR=1 mvn --batch-mode --no-transfer-progress test",
            "NPM_TEST", "CI=true NO_COLOR=1 npm test",
            "NPM_BUILD", "CI=true NO_COLOR=1 npm run build");

    String command(String task, String absoluteDirectory) {
        if (!TASKS.contains(task)) {
            throw new IllegalArgumentException(
                    "task 只允许 GIT_STATUS、GIT_DIFF_SUMMARY、MAVEN_TEST、NPM_TEST 或 NPM_BUILD");
        }
        if (absoluteDirectory == null || !absoluteDirectory.matches("/[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("远程执行目录包含不安全字符");
        }
        return "cd '" + absoluteDirectory + "' && " + FIXED_COMMANDS.get(task);
    }

    void validateRelativePath(String requestedPath) {
        if (requestedPath == null || !requestedPath.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("远程执行目录包含不安全字符");
        }
    }

    String marker(String task) {
        return switch (task) {
            case "GIT_STATUS", "GIT_DIFF_SUMMARY" -> ".git";
            case "MAVEN_TEST" -> "pom.xml";
            case "NPM_TEST", "NPM_BUILD" -> "package.json";
            default -> throw new IllegalArgumentException("不支持的远程执行任务：" + task);
        };
    }

    boolean directoryMarker(String task) {
        return task.startsWith("GIT_");
    }
}
