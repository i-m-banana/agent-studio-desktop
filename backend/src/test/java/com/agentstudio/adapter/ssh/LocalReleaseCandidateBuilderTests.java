package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalReleaseCandidateBuilderTests {
    @TempDir Path root;

    @Test
    void runsFixedMavenLifecycleAndUsesOnlyTargetAppJar() throws Exception {
        fixture("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>candidate</artifactId><version>1.0.0</version>
                  <build><finalName>app</finalName></build>
                </project>
                """);
        Files.writeString(root.resolve("app.jar"), "stale-root-jar-must-not-be-selected");

        var result = new LocalReleaseCandidateBuilder(Duration.ofSeconds(90)).build(profile());

        assertThat(result.successful()).isTrue();
        assertThat(result.stage()).isEqualTo("LOCAL_BUILD");
        assertThat(result.artifact()).isEqualTo(root.resolve("target/app.jar"));
        assertThat(Files.readString(root.resolve("app.jar"))).isEqualTo("stale-root-jar-must-not-be-selected");
        assertThat(Files.size(result.artifact())).isGreaterThan(0);
        assertThat(result.output()).contains("BUILD SUCCESS", "MAVEN_PACKAGE");
    }

    @Test
    void returnsMavenTestFailureBeforeAnyCandidateCanBeStaged() throws Exception {
        fixture("<not-a-valid-pom>");
        var result = new LocalReleaseCandidateBuilder(Duration.ofSeconds(30)).build(profile());
        assertThat(result.successful()).isFalse();
        assertThat(result.stage()).isEqualTo("MAVEN_TEST");
        assertThat(result.exitCode()).isNotZero();
        assertThat(Files.exists(root.resolve("target/app.jar"))).isFalse();
    }

    private void fixture(String pom) throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom);
        Files.writeString(root.resolve("Dockerfile"), "FROM scratch");
        Files.writeString(root.resolve("docker-compose.yml"), "services: {}");
        Files.writeString(root.resolve("nginx.conf"), "server {}");
    }

    private RemoteDeploymentProfile profile() {
        return new RemoteDeploymentProfile(root.toString(), "/srv/app", "/srv/backups",
                "docker-compose.yml", "compose.yml", "app", "nginx.conf", "http://127.0.0.1/",
                true, "READY", null, null, Instant.now());
    }
}
