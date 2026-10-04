package com.agentstudio.project;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
class ProjectRuntimeTests{
    private org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database;private ProjectRuntimeService runtime;private LocalProjectService projects;private LocalProject project;private JdbcTemplate jdbc;
    @BeforeEach void setup(){database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();jdbc=new JdbcTemplate(database);jdbc.execute("CREATE TABLE project_runtime(project_id VARCHAR(36),record_json CLOB)");jdbc.execute("CREATE TABLE local_preview(project_id VARCHAR(36),state VARCHAR(32))");projects=mock(LocalProjectService.class);project=new LocalProject("runtime","fixture","D:/fixture",List.of("src"),List.of("data"),2,false,true);when(projects.get("runtime")).thenReturn(project);runtime=new ProjectRuntimeService(projects,jdbc,new ObjectMapper());}
    @AfterEach void end(){database.shutdown();}
    ProjectRuntimeService.Request request(int runtimeRev,boolean confirmed){return new ProjectRuntimeService.Request(2,runtimeRev,"SHIGUANGXV_SPRING_BOOT_H2_V1",false,false,10,confirmed);}
    @Test void savesOnlyPresetAndInvalidatesProjectRevision()throws Exception{var updated=runtime.save("runtime",request(0,true));assertThat(updated.revision()).isEqualTo(1);assertThatThrownBy(()->runtime.requireMysql("runtime")).hasMessageContaining("关闭");verify(projects).save(eq("runtime"),any());assertThatThrownBy(()->runtime.save("runtime",request(0,true))).hasMessageContaining("已变化");}
    @Test void requiresAcknowledgementAndStoppedPreview(){assertThatThrownBy(()->runtime.save("runtime",request(0,false))).hasMessageContaining("确认");jdbc.update("INSERT INTO local_preview VALUES('runtime','READY')");assertThatThrownBy(()->runtime.save("runtime",request(0,true))).hasMessageContaining("停止");verify(projects,never()).save(any(),any());}
}
