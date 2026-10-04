package com.agentstudio.coding;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import com.agentstudio.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

/** Real Docker workload, synthetic H2 data, HTTP bridge and owned resource disposal. Opt-in only. */
@EnabledIfEnvironmentVariable(named="AGENT_TEST_WEBSITE_PREVIEW",matches="true")
class WebsitePreviewTests {
    @Test void realWebsitePreviewIsInteractiveDisconnectedAndDisposable() throws Exception {
        var root=Path.of("../../shiguangxv").toAbsolutePath().normalize();
        var workspace=new CodingWorkspace(root.toString());
        var project=new LocalProject("preview-fixture","网站预览验收",root.toString(),List.of("src"),List.of("mysql-data","uploads","data","logs","backups",".secrets"),1,false,true);
        var engine=System.getenv("AGENT_TEST_DOCKER_EXECUTABLE");var image=System.getenv("AGENT_TEST_SANDBOX_IMAGE");
        var docker=new PreviewDocker(engine,image);var runner=new IsolatedProjectRunner(workspace,image,engine);
        var database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var jdbc=new JdbcTemplate(database);jdbc.execute("CREATE TABLE local_preview(id VARCHAR(36),project_id VARCHAR(36),state VARCHAR(32),expires_at BIGINT,record_json CLOB)");
        var previews=new LocalPreviewService(workspace,runner,docker,jdbc,new ObjectMapper());
        try(var context=ProjectExecutionContext.enter(project)) {
            ProjectExecutionContext.bindSourceSha256(ProjectSourceSnapshot.fingerprint(workspace));
            LocalPreviewService.Preview p=null;
            try {
                p=previews.start();assertThat(p.state()).isEqualTo("READY");
                System.out.println("SYNTHETIC_PREVIEW_URL="+p.url());
                var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                var result=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/browse?theme=old&q=%E6%B5%8B%E8%AF%95&tagId=1")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).contains("预览测试：童年记忆","当前筛选","童年动画","时光录 · 隔离预览");
                assertThat(result.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("connect-src 'self'");
                var missing=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/browse?q=preview-no-match-123")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(missing.body()).contains("没有找到匹配的内容");
                assertThat(docker.run(List.of("inspect","--format","{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|{{.Config.User}}",p.container()),10)).isEqualTo("none|true|1000:1000");
                var isolation=docker.run(List.of("exec",p.container(),"sh","-c","test ! -e /var/run/docker.sock && test ! -e /source/uploads && test ! -e /source/mysql-data && test ! -e /source/.env && echo ISOLATED"),10);
                assertThat(isolation).isEqualTo("ISOLATED");
                assertThat(previews.list(project.id()).getFirst().sourceSha256()).isEqualTo(ProjectExecutionContext.sourceSha256());
                // Credential/cookie forwarding and write requests affect synthetic data only.
                var registration=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/api/auth/register")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"preview-fixture-user\",\"password\":\"preview-fixture-password\"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(registration.statusCode()).isEqualTo(200);
                var cookie=registration.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                var favorites=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/browse?tab=favorites")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(favorites.statusCode()).isEqualTo(200);assertThat(favorites.body()).contains("我的收藏");
                // The current website has a V2 content_year mapping. Startup must apply it, not only V1.
                var create=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/api/items")).header("Cookie",cookie)
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                                "{\"type\":\"text\",\"title\":\"合成预览年份验证\",\"content\":\"仅合成数据\",\"theme\":\"OLD\",\"contentYear\":1995}"))
                        .build(),HttpResponse.BodyHandlers.ofString());
                assertThat(create.statusCode()).isEqualTo(200);
                assertThat(create.body()).contains("1995");
                var years=client.send(HttpRequest.newBuilder(URI.create(p.url()+"/browse?tab=candidate&era=1990s"))
                        .GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(years.statusCode()).isEqualTo(200);
                assertThat(years.body()).contains("合成预览年份验证","1995");
                assertThat(ProjectSourceSnapshot.fingerprint(workspace)).isEqualTo(ProjectExecutionContext.sourceSha256());
                int hold=Integer.parseInt(System.getenv().getOrDefault("AGENT_TEST_PREVIEW_HOLD_SECONDS","0"));
                assertThat(hold).isBetween(0,180);
                if(hold>0){System.out.println("BROWSER_ACCEPTANCE_PREVIEW="+p.url()+"/browse");Thread.sleep(hold*1000L);}
                previews.stop(p.id());assertThat(previews.list(project.id()).getFirst().state()).isEqualTo("STOPPED");
                assertThat(docker.owned("container",p.container(),p.id())).isFalse();assertThat(docker.owned("volume",p.volume(),p.id())).isFalse();
                assertThat(Path.of(p.snapshot())).doesNotExist();
            }finally {if(p!=null && !previews.list(project.id()).getFirst().state().equals("STOPPED"))previews.stop(p.id());}
        }finally{previews.shutdown();database.shutdown();}
    }
}
