package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import com.agentstudio.project.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named="AGENT_TEST_SANDBOX_IMAGE",matches="sha256:[0-9a-f]{64}")
class IsolatedProjectRunnerTests {
    @TempDir Path root;
    LocalProject project() { return new LocalProject("fixture","隔离样例",root.toString(),List.of("src"),List.of("uploads"),1,false,true); }
    IsolatedProjectRunner runner(CodingWorkspace workspace) {
        return new IsolatedProjectRunner(workspace,System.getenv("AGENT_TEST_SANDBOX_IMAGE"),System.getenv("AGENT_TEST_DOCKER_EXECUTABLE"));
    }
    void nodeFixture() throws Exception {
        Files.writeString(root.resolve("package.json"),"{\"name\":\"isolation-fixture\",\"version\":\"1.0.0\",\"scripts\":{\"test\":\"node --test test.cjs\",\"build\":\"node test.cjs\"}}");
        Files.writeString(root.resolve("package-lock.json"),"{\"name\":\"isolation-fixture\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"isolation-fixture\",\"version\":\"1.0.0\"}}}");
        Files.createDirectories(root.resolve("uploads"));Files.writeString(root.resolve("uploads/real-data.txt"),"PRIVATE_FIXTURE");
        Files.writeString(root.resolve("test.cjs"),"""
            const assert=require('node:assert');const fs=require('node:fs');const net=require('node:net');
            assert(!fs.existsSync('/work/uploads/real-data.txt'));
            assert(!fs.existsSync('/source/uploads/real-data.txt'));
            assert(!fs.existsSync('/var/run/docker.sock'));
            assert(!process.env.AGENT_TEST_HOST_SECRET);
            assert.throws(()=>fs.writeFileSync('/source/outside.txt','bad'));
            const socket=net.connect({host:'1.1.1.1',port:443});
            socket.setTimeout(1000,()=>socket.destroy(new Error('blocked')));
            socket.on('connect',()=>{console.error('NETWORK_OPEN');process.exit(2)});
            socket.on('error',()=>{console.log('ISOLATION_OK');process.exit(0)});
            """);
    }
    @Test void realContainerCannotAccessDataCredentialsHostSocketOrNetwork() throws Exception {
        nodeFixture();var workspace=new CodingWorkspace(root.toString());
        try(var context=ProjectExecutionContext.enter(project())) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            var result=runner(workspace).run(".","NPM_TEST");
            assertThat(result.successful()).as(result.output()).isTrue();assertThat(result.output()).contains("ISOLATION_OK");
            assertThat(Files.readString(root.resolve("uploads/real-data.txt"))).isEqualTo("PRIVATE_FIXTURE");
            assertThat(root.resolve("outside.txt")).doesNotExist();assertThat(root.resolve("node_modules")).doesNotExist();
        }
    }
    @Test void changedSourceNeverRunsUnderOldApproval() throws Exception {
        nodeFixture();var workspace=new CodingWorkspace(root.toString());
        try(var context=ProjectExecutionContext.enter(project())) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            Files.writeString(root.resolve("test.cjs"),"console.log('CHANGED')");
            assertThatThrownBy(()->runner(workspace).run(".","NPM_BUILD")).hasMessageContaining("审批快照不一致");
        }
    }
    @Test void javaTestsAndPackageProduceOnlySnapshotArtifact() throws Exception {
        var pom=Files.readString(Path.of("pom.xml"));pom=pom.replace("<build>","<build><finalName>app</finalName>");
        Files.writeString(root.resolve("pom.xml"),pom);
        Files.createDirectories(root.resolve("src/main/java/example"));
        Files.writeString(root.resolve("src/main/java/example/Demo.java"),"package example; public class Demo { public static void main(String[] args){ System.out.println(\"demo\"); } }");
        var workspace=new CodingWorkspace(root.toString());
        try(var context=ProjectExecutionContext.enter(project())) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            var result=runner(workspace).run(".","RELEASE_PACKAGE");
            try {
                assertThat(result.successful()).as(result.output()).isTrue();assertThat(result.artifact()).isRegularFile();
                assertThat(Files.size(result.artifact())).isGreaterThan(1000);assertThat(root.resolve("target")).doesNotExist();
            } finally { if(result.successful())IsolatedProjectRunner.removeSnapshot(result.snapshotRoot().getParent()); }
        }
    }
    @Test void springBoot325TestsAndPackageResolveOffline() throws Exception {
        var pom=Files.readString(Path.of("../docker/verifier/spring-boot-3.2.5-pom.xml"));
        Files.writeString(root.resolve("pom.xml"),pom.replace("<build>","<build><finalName>app</finalName>"));
        Files.createDirectories(root.resolve("src/main/java/example"));Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("src/main/java/example/Demo.java"),"package example; public class Demo { public static void main(String[] args) {} }");
        Files.writeString(root.resolve("src/test/java/example/DemoTest.java"),"package example; class DemoTest { @org.junit.jupiter.api.Test void works() { org.junit.jupiter.api.Assertions.assertTrue(true); } }");
        var workspace=new CodingWorkspace(root.toString());
        try(var context=ProjectExecutionContext.enter(project())) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            var tests=runner(workspace).run(".","MAVEN_TEST");
            assertThat(tests.successful()).as(tests.output()).isTrue();
            assertThat(tests.output()).contains("Tests run: 1");
            var packaged=runner(workspace).run(".","RELEASE_PACKAGE");
            try { assertThat(packaged.successful()).as(packaged.output()).isTrue();assertThat(packaged.artifact()).isRegularFile(); }
            finally { if(packaged.successful())IsolatedProjectRunner.removeSnapshot(packaged.snapshotRoot().getParent()); }
            assertThat(root.resolve("target")).doesNotExist();
        }
    }

    @Test void failingTestReturnsCurrentReportAndFinalErrorInsteadOfHostStaleReport()throws Exception {
        Files.writeString(root.resolve("pom.xml"),Files.readString(Path.of("../docker/verifier/spring-boot-3.2.5-pom.xml")));
        Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("src/test/java/example/DemoTest.java"),"""
                package example;
                class DemoTest {
                    @org.junit.jupiter.api.Test void currentFailure() {
                        System.out.println("PAGE_DUMP".repeat(8000));
                        org.junit.jupiter.api.Assertions.fail("FRESH_ASSERTION_FAILED");
                    }
                }
                """);
        Files.createDirectories(root.resolve("target/surefire-reports"));
        var stale=root.resolve("target/surefire-reports/TEST-example.DemoTest.xml");Files.writeString(stale,"OLD_HOST_REPORT_DO_NOT_USE");
        var workspace=new CodingWorkspace(root.toString());
        try(var context=ProjectExecutionContext.enter(project())) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            var result=runner(workspace).run(".","MAVEN_TEST");
            assertThat(result.successful()).isFalse();assertThat(result.exitCode()).isEqualTo(1);assertThat(result.truncated()).isTrue();
            assertThat(result.output()).contains("FRESH_ASSERTION_FAILED","BUILD FAILURE").doesNotContain("OLD_HOST_REPORT");
            assertThat(result.reportWarning()).isNull();assertThat(result.reports()).hasSize(1);
            assertThat(result.reports().getFirst()).containsEntry("failures",1);
            assertThat(result.reports().getFirst().get("failedTests").toString()).contains("currentFailure","FRESH_ASSERTION_FAILED");
            assertThat(Files.readString(stale)).isEqualTo("OLD_HOST_REPORT_DO_NOT_USE");
        }
    }
}
