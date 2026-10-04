package com.agentstudio.project;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import com.agentstudio.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalProjectTests {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    JdbcTemplate jdbc;
    LocalProjectService service;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE local_project(id VARCHAR(36),configuration_json CLOB,revision INT,archived BOOLEAN)");
        jdbc.execute("CREATE TABLE conversation(id VARCHAR(36),local_project_id VARCHAR(36),local_project_revision INT,local_workspace_identity VARCHAR(500))");
        jdbc.execute("CREATE TABLE agent_run(id VARCHAR(36),conversation_id VARCHAR(36),status VARCHAR(40))");
        jdbc.execute("CREATE TABLE release_workflow(id VARCHAR(36),project_id VARCHAR(36))");jdbc.execute("CREATE TABLE release_workflow_lease(workflow_id VARCHAR(36))");
        service=new LocalProjectService(jdbc,mapper,directory.toString(),"");
    }
    LocalProject project(boolean confirmed) {
        return service.save(null,new LocalProjectService.Request("样例",directory.toString(),List.of("src"),List.of("uploads","database"),null,confirmed));
    }
    @Test void onlyRegistersExplicitRootAndDoesNotCreateMissingDirectories() throws Exception {
        assertThatThrownBy(()->service.save(null,new LocalProjectService.Request("错误",directory.getParent().toString(),List.of("src"),List.of(),null,true))).hasMessageContaining("授权");
        var missing=directory.resolve("not-created");
        var separate=new LocalProjectService(jdbc,mapper,missing.toString(),"");
        var value=separate.save(null,new LocalProjectService.Request("尚不存在",missing.toString(),List.of("src"),List.of(),null,true));
        assertThat(separate.status(value).status()).isEqualTo("UNAVAILABLE");assertThat(missing).doesNotExist();
    }
    @Test void requiresProtectionConfirmationAndInvalidatesOldConversationOnChanges() throws Exception {
        var value=project(false); var initialId=value.id();jdbc.update("INSERT INTO conversation(id) VALUES('c')");
        assertThatThrownBy(()->service.bind("c",initialId)).hasMessageContaining("保护");
        value=service.save(value.id(),new LocalProjectService.Request("样例",directory.toString(),List.of("src"),List.of("uploads"),value.revision(),true));
        service.bind("c",value.id());assertThat(service.resolve("c",null).id()).isEqualTo(value.id());
        assertThatThrownBy(()->service.resolve("c","other")).hasMessageContaining("其他项目");
        service.save(value.id(),new LocalProjectService.Request("改名",directory.toString(),List.of("src"),List.of("uploads"),value.revision(),true));
        assertThatThrownBy(()->service.resolve("c",null)).hasMessageContaining("已变化");
    }
    @Test void runningProjectsCannotChangeOrStartAnotherRun() throws Exception {
        var value=project(true);jdbc.update("INSERT INTO conversation(id) VALUES('c')");service.bind("c",value.id());
        jdbc.update("INSERT INTO agent_run VALUES('r','c','WAITING_APPROVAL')");
        assertThatThrownBy(()->service.archive(value.id(),true)).hasMessageContaining("运行中");
        assertThatThrownBy(()->service.requireNoActiveRun(value.id())).hasMessageContaining("运行中");
    }
    @Test void unfinishedReleaseTaskPreventsConfigurationChangeAndArchive(){var p=project(true);jdbc.update("INSERT INTO release_workflow VALUES('flow',?)",p.id());jdbc.update("INSERT INTO release_workflow_lease VALUES('flow')");assertThatThrownBy(()->service.archive(p.id(),true)).hasMessageContaining("发布任务");assertThatThrownBy(()->service.save(p.id(),new LocalProjectService.Request(p.name(),p.sourceRoot(),p.writableDirectories(),p.protectedDirectories(),p.revision(),true))).hasMessageContaining("发布任务");}
    @Test void contextSurvivesVirtualToolThreadAndDoesNotLeakIntoNextCall() throws Exception {
        Files.createDirectories(directory.resolve("src"));Files.writeString(directory.resolve("src/a.txt"),"safe");
        var workspace=new CodingWorkspace(directory.resolve("wrong-default").toString());
        var registry=new com.agentstudio.tool.ToolRegistry(List.of(new ReadWorkspaceTextFileTool(workspace,mapper)),mapper);
        try(var context=ProjectExecutionContext.enter(project(true))) {
            assertThat(registry.execute("read_workspace_text_file","{\"path\":\"src/a.txt\"}").output()).contains("safe");
        }
        assertThat(ProjectExecutionContext.current()).isNull();
        assertThatThrownBy(()->registry.execute("read_workspace_text_file","{\"path\":\"src/a.txt\"}")).hasCauseInstanceOf(IllegalStateException.class);
    }
    @Test void creationCannotOverwriteEscapeOrWriteRealData() throws Exception {
        Files.createDirectories(directory.resolve("src"));Files.createDirectories(directory.resolve("uploads"));
        var workspace=new CodingWorkspace(directory.toString());var tool=new CreateWorkspaceTextFileTool(workspace,mapper);
        try(var context=ProjectExecutionContext.enter(project(true))) {
            assertThatThrownBy(()->tool.execute(mapper.readTree("{\"path\":\"uploads/a.txt\",\"content\":\"bad\"}"))).hasMessageContaining("保护");
            assertThatThrownBy(()->tool.execute(mapper.readTree("{\"path\":\"../outside.txt\",\"content\":\"bad\"}"))).hasMessageContaining("越界");
            assertThatThrownBy(()->tool.execute(mapper.readTree("{\"path\":\"src/new/a.txt\",\"content\":\"bad\"}"))).hasMessageContaining("不存在");
            assertThat(tool.execute(mapper.readTree("{\"path\":\"src/a.txt\",\"content\":\"approved\"}"))).contains("created");
            assertThatThrownBy(()->tool.execute(mapper.readTree("{\"path\":\"src/a.txt\",\"content\":\"overwrite\"}"))).hasMessageContaining("已存在");
            assertThat(Files.readString(directory.resolve("src/a.txt"))).isEqualTo("approved");
        }
    }
    @Test void snapshotExcludesSecretsProtectedDataAndGeneratedOutput() throws Exception {
        Files.createDirectories(directory.resolve("src"));Files.createDirectories(directory.resolve("database"));Files.createDirectories(directory.resolve("target"));
        Files.writeString(directory.resolve("src/App.java"),"class App{}");Files.writeString(directory.resolve("database/real.txt"),"DO_NOT_COPY");
        Files.writeString(directory.resolve(".env"),"SECRET");Files.writeString(directory.resolve("target/app.jar"),"old");
        try(var context=ProjectExecutionContext.enter(project(true))) {
            var workspace=new CodingWorkspace(directory.toString());var before=ProjectSourceSnapshot.fingerprint(workspace);
            Files.writeString(directory.resolve("database/real.txt"),"changed protected data");
            assertThat(ProjectSourceSnapshot.fingerprint(workspace)).isEqualTo(before);
            Files.writeString(directory.resolve("src/App.java"),"class Changed{}");assertThat(ProjectSourceSnapshot.fingerprint(workspace)).isNotEqualTo(before);
            assertThat(new IsolatedProjectRunner(workspace,"","").status().get("configured")).isEqualTo(false);
        }
    }

    @Test void createsOnlyVersionedMigrationAndTestSqlWithinAuthorizedDirectories()throws Exception {
        var mysql=directory.resolve("src/main/resources/db/migration/mysql");Files.createDirectories(mysql);
        var h2=directory.resolve("src/main/resources/db/migration/h2");Files.createDirectories(h2);
        var tests=directory.resolve("src/test/resources");Files.createDirectories(tests);
        var tool=new CreateWorkspaceTextFileTool(new CodingWorkspace(directory.toString()),mapper);
        try(var context=ProjectExecutionContext.enter(project(true))) {
            for(String path:List.of("src/main/resources/db/migration/mysql/V2__content_year.sql","src/main/resources/db/migration/h2/V2__content_year.sql","src/test/resources/year_fixture.sql")) {
                assertThat(tool.execute(mapper.valueToTree(Map.of("path",path,"content","-- approved source only\n")))).contains("created");
                assertThat(Files.readString(directory.resolve(path))).contains("approved source only");
                assertThatThrownBy(()->tool.execute(mapper.valueToTree(Map.of("path",path,"content","overwrite")))).hasMessageContaining("已存在");
            }
            for(String path:List.of("src/run.sql","uploads/data.sql","src/main/resources/db/migration/mysql/probe.sql","src/test/resources/bad.cmd"))
                assertThatThrownBy(()->tool.execute(mapper.valueToTree(Map.of("path",path,"content","bad"))));
        }
    }
    @Test void rootLevelLargeArtifactsAreExcludedButLargeSourceStillFails() throws Exception {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("src/App.java"),"class App{}");
        Files.writeString(directory.resolve("pom.xml"),"<project/>");
        try(var context=ProjectExecutionContext.enter(project(true))) {
            var workspace=new CodingWorkspace(directory.toString());
            var before=ProjectSourceSnapshot.fingerprint(workspace);
            try(var file=java.nio.channels.FileChannel.open(directory.resolve("app.jar"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                file.position(9*1024*1024);file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
            }
            Files.writeString(directory.resolve("old.WAR"),"generated");
            Files.copy(directory.resolve("app.jar"),directory.resolve("site-backup.tar.gz"));
            Files.createDirectories(directory.resolve("BUILD"));Files.writeString(directory.resolve("BUILD/generated.txt"),"output");
            assertThat(ProjectSourceSnapshot.fingerprint(workspace)).isEqualTo(before);
            var copy=directory.resolveSibling(directory.getFileName()+"-snapshot");
            try {
                ProjectSourceSnapshot.copy(workspace,copy);
                assertThat(copy.resolve("src/App.java")).isRegularFile();assertThat(copy.resolve("pom.xml")).isRegularFile();
                assertThat(copy.resolve("app.jar")).doesNotExist();assertThat(copy.resolve("old.WAR")).doesNotExist();assertThat(copy.resolve("BUILD")).doesNotExist();
                assertThat(copy.resolve("site-backup.tar.gz")).doesNotExist();
            } finally { if(Files.exists(copy))try(var paths=Files.walk(copy)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);} }
            Files.move(directory.resolve("app.jar"),directory.resolve("src/oversized.txt"));
            assertThatThrownBy(()->ProjectSourceSnapshot.fingerprint(workspace)).hasMessageContaining("源码单文件超过 8 MiB");
        }
    }
    @Test void windowsAliasesAndAbsolutePathsAreRejected() {
        for(var path:List.of("src/CON.txt","src/a.txt:stream","C:/Windows/a.txt","src/../a.txt","src/a.txt.","/etc/passwd"))
            assertThatThrownBy(()->CodingWorkspace.safeRelative(path)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hardLinkedRealDataCannotBeReadOrCopiedIntoSandbox() throws Exception {
        Files.createDirectories(directory.resolve("src"));Files.createDirectories(directory.resolve("database"));
        var real=directory.resolve("database/real.txt");Files.writeString(real,"PRIVATE_DATA");
        try { Files.createLink(directory.resolve("src/alias.txt"),real); }
        catch(UnsupportedOperationException | java.io.IOException e){org.junit.jupiter.api.Assumptions.abort("Hard links unavailable");}
        try(var context=ProjectExecutionContext.enter(project(true))) {
            var workspace=new CodingWorkspace(directory.toString());
            assertThatThrownBy(()->new ReadWorkspaceTextFileTool(workspace,mapper).execute(mapper.readTree("{\"path\":\"src/alias.txt\"}"))).isInstanceOf(Exception.class);
            assertThatThrownBy(()->ProjectSourceSnapshot.fingerprint(workspace)).isInstanceOf(Exception.class);
            assertThat(Files.readString(real)).isEqualTo("PRIVATE_DATA");
        }
    }
}
