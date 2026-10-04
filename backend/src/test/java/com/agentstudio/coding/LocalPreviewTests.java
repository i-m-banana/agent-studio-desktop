package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class LocalPreviewTests {
    @Test void failedStartupKeepsDiagnosisAfterOwnedResourcesAreCleaned() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("preview-project-");
        var snapshot=java.nio.file.Files.createTempDirectory("agent-studio-source-");
        var source=java.nio.file.Files.createDirectory(snapshot.resolve("source"));
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var service=(LocalPreviewService)null;
        try {
            for(var path:List.of("src/main/java/com/mylove/controller/BrowseController.java","src/main/resources/db/migration/h2/V1__current_website_schema.sql")) {
                var file=root.resolve(path);java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"fixture");
            }
            var jdbc=new JdbcTemplate(db);jdbc.execute("CREATE TABLE local_preview(id VARCHAR(36),project_id VARCHAR(36),state VARCHAR(32),expires_at BIGINT,record_json CLOB)");
            var runner=mock(IsolatedProjectRunner.class);
            when(runner.run(".","RELEASE_PACKAGE")).thenReturn(new IsolatedProjectRunner.Result(true,0,1,"passed",false,"sha",source,null,List.of(),null));
            var executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var docker=spy(new PreviewDocker(executable,"sha256:"+"a".repeat(64)));
            doAnswer(invocation->{List<String> args=invocation.getArgument(0);
                if(args.contains("{{.State.Running}}"))return "false";
                if(args.contains("logs"))return "Schema-validation: missing column [content_year]";
                return "fixture";
            }).when(docker).run(anyList(),anyInt());
            doReturn(true).when(docker).owned(anyString(),anyString(),anyString());
            service=new LocalPreviewService(new CodingWorkspace(root.toString()),runner,docker,jdbc,new ObjectMapper());
            var project=new com.agentstudio.project.LocalProject("fixture","预览",root.toString(),List.of("src"),List.of("uploads"),1,false,true);
            final var previewService=service;
            try(var context=com.agentstudio.project.ProjectExecutionContext.enter(project)) {
                assertThatThrownBy(previewService::start).hasMessageContaining("missing column [content_year]");
            }
            var failed=service.list("fixture").getFirst();
            assertThat(failed.state()).isEqualTo("FAILED");
            assertThat(failed.message()).contains("已清理","missing column [content_year]");
            assertThat(failed.url()).isNull();assertThat(snapshot).doesNotExist();
            verify(docker).run(argThat(args->args!=null&&args.contains("rm")&&args.contains("--force")),eq(15));
            verify(docker).run(argThat(args->args!=null&&args.contains("volume")&&args.contains("rm")),eq(15));
        } finally {
            if(service!=null)service.shutdown();db.shutdown();
            if(java.nio.file.Files.exists(snapshot))IsolatedProjectRunner.removeSnapshot(snapshot);
            try(var paths=java.nio.file.Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
        }
    }
    @Test void previewRunsCurrentH2MigrationsBeforeSyntheticDataAndDoesNotLoadBusinessConfig() throws Exception {
        var settings=new java.util.Properties();
        try(var input=getClass().getResourceAsStream("/preview/application.properties")){settings.load(input);}
        assertThat(settings.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(settings.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration/h2,filesystem:/preview/migration");
        assertThat(settings.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(settings.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(settings.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(settings.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
    }
    @Test void startupDiagnosticsAreBoundedAndDoNotRevealPreviewCredentials() throws Exception {
        var docker=mock(PreviewDocker.class);
        when(docker.run(argThat(args->args!=null && args.contains("inspect")),eq(5))).thenReturn("exited|exit=1|oom=false");
        when(docker.run(argThat(args->args!=null && args.contains("logs")),eq(5))).thenReturn("x".repeat(10000)+" missing content_year preview-only-admin");
        var service=new LocalPreviewService(null,null,docker,null,null);
        assertThat(service.startupDiagnostics("fixture")).contains("missing content_year","[preview credential]")
                .doesNotContain("preview-only-admin").hasSizeLessThan(6100);
    }
    @Test void toolRejectsArbitraryInputsBeforeStart() throws Exception {
        var service=mock(LocalPreviewService.class);var mapper=new ObjectMapper();var tool=new StartProjectPreviewTool(service,mapper);
        assertThatThrownBy(()->tool.execute(mapper.readTree("{\"command\":\"docker run\"}"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);assertThat(tool.descriptor().riskLevel()).isEqualTo("HIGH");
        assertThat(tool.targetEnvironment()).contains("NETWORK:none","H2_SYNTHETIC","NO_PRODUCTION");
    }
    @Test void foreignResourcesAndUnavailableEngineAreNotCleaned() throws Exception {
        var docker=spy(new PreviewDocker("C:/docker.exe","sha256:"+"a".repeat(64)));
        doReturn("{\"agent-studio.preview\":\"other-owner\"}").when(docker).run(argThat(a->a.contains("inspect")),eq(10));
        doReturn("occupied").when(docker).run(argThat(a->a.contains("ls")),eq(10));
        assertThatThrownBy(()->docker.owned("container","occupied","owner")).isInstanceOf(IllegalStateException.class);
        doThrow(new IllegalStateException("engine unavailable")).when(docker).run(argThat(a->a.contains("ls")),eq(10));
        assertThatThrownBy(()->docker.owned("container","occupied","owner")).isInstanceOf(IllegalStateException.class);
    }
    @Test void controllerHasNoUnapprovedStartAndProxyRejectsForeignOrigins() throws Exception {
        assertThat(Arrays.stream(LocalPreviewController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("start");
        var service=new LocalPreviewService(null,null,null,null,null);
        var p=new LocalPreviewService.Preview("test","project","READY","sha",null,System.currentTimeMillis()+10000,null,null,null,"fixture");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.2",0),1);
        var url="http://127.0.0.2:"+server.getAddress().getPort();server.createContext("/",e->service.handle(p,url,e));server.start();
        try {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url+"/browse")).header("Origin","http://untrusted.example").GET().build(),HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(403);
        }finally{server.stop(0);service.shutdown();}
    }
    @Test void stopPersistsCleanupFailureAndDoesNotDeleteForeignContainer() throws Exception {
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc=new JdbcTemplate(db);jdbc.execute("CREATE TABLE local_preview(id VARCHAR(36),project_id VARCHAR(36),state VARCHAR(32),expires_at BIGINT,record_json CLOB)");
            var mapper=new ObjectMapper();var docker=mock(PreviewDocker.class);
            var id=UUID.randomUUID().toString();var name="agent-studio-preview-"+id;
            var p=new LocalPreviewService.Preview(id,"fixture","READY","sha",null,0,name,name,null,"fixture");
            jdbc.update("INSERT INTO local_preview VALUES(?,?,?,?,?)",id,"fixture","READY",0,mapper.writeValueAsString(p));
            when(docker.owned("container",name,id)).thenThrow(new IllegalStateException("foreign"));
            var service=new LocalPreviewService(null,null,docker,jdbc,mapper);
            assertThatThrownBy(()->service.stop(id)).isInstanceOf(IllegalStateException.class);
            assertThat(service.list("fixture").getFirst().state()).isEqualTo("CLEANUP_REQUIRED");
            verify(docker,never()).run(anyList(),anyInt());service.shutdown();
        }finally{db.shutdown();}
    }
    @Test void restartAndExpiredRecordsAreReclaimedWithoutResuming() throws Exception {
        var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc=new JdbcTemplate(db);jdbc.execute("CREATE TABLE local_preview(id VARCHAR(36),project_id VARCHAR(36),state VARCHAR(32),expires_at BIGINT,record_json CLOB)");
            var mapper=new ObjectMapper();var docker=mock(PreviewDocker.class);var service=new LocalPreviewService(null,null,docker,jdbc,mapper);
            for(long expiry:List.of(0L,System.currentTimeMillis()+60000)) {
                var id=UUID.randomUUID().toString();var name="agent-studio-preview-"+id;
                var p=new LocalPreviewService.Preview(id,"fixture","READY","sha","http://127.0.0.2:12345",expiry,name,name,null,"fixture");
                jdbc.update("INSERT INTO local_preview VALUES(?,?,?,?,?)",id,"fixture","READY",expiry,mapper.writeValueAsString(p));
                if(expiry==0L) {
                    var server=HttpServer.create(new InetSocketAddress("127.0.0.2",0),1);server.start();
                    @SuppressWarnings("unchecked") var servers=(Map<String,HttpServer>)org.springframework.test.util.ReflectionTestUtils.getField(service,"servers");
                    servers.put(id,server); // Expiry must stop a live instance, not only restart leftovers.
                }
                when(docker.owned("container",name,id)).thenReturn(true);when(docker.owned("volume",name,id)).thenReturn(true);
            }
            service.sweep();assertThat(service.list("fixture")).hasSize(2).allMatch(p->p.state().equals("STOPPED")&&p.url()==null);
            verify(docker,times(2)).run(argThat(a->a.contains("--force")),eq(15));
            verify(docker,never()).run(argThat(a->a.contains("start")),anyInt());service.shutdown();
        } finally {db.shutdown();}
    }
}
