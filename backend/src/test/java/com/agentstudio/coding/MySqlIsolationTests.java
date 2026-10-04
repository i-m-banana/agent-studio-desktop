package com.agentstudio.coding;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import com.agentstudio.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class MySqlIsolationTests {
    @Test void suiteReportsMustIncludeEveryRequiredSuiteWithoutSkips(){
        String reports="";for(var name:List.of("com.mylove.MySqlMigrationSmokeIT","com.mylove.DatabaseBaselineMaintenanceIT","com.mylove.database.DatabaseReleaseMaintenanceIT"))reports+="<testsuite name=\""+name+"\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">\n";
        assertThat(MySqlVerificationRunner.verifiedReports(reports)).isTrue();assertThat(MySqlVerificationRunner.verifiedReports(reports.replace("tests=\"2\"","tests=\"0\""))).isFalse();assertThat(MySqlVerificationRunner.verifiedReports(reports.replace("skipped=\"0\"","skipped=\"1\""))).isFalse();assertThat(MySqlVerificationRunner.verifiedReports("BUILD SUCCESS")).isFalse();
    }
    @Test @EnabledIfEnvironmentVariable(named="AGENT_TEST_MYSQL",matches="true")
    void realSyntheticMysqlLifecycleRunsExistingJUnitAssertionsAndDisposesEverything()throws Exception{
        var root=Files.createTempDirectory("studio-mysql-fixture-");
        try{
            var pom=Files.readString(Path.of("../docker/verifier/spring-boot-3.2.5-pom.xml"));
            pom=pom.replace("<build><plugins>","<profiles><profile><id>mysql-verification</id><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions></plugin></plugins></build></profile></profiles><build><plugins>");
            Files.writeString(root.resolve("pom.xml"),pom);var main=root.resolve("src/main/java/example/Seed.java");Files.createDirectories(main.getParent());Files.writeString(main,"package example; @org.springframework.boot.autoconfigure.SpringBootApplication public class Seed {public static void main(String[] args){org.springframework.boot.SpringApplication.run(Seed.class,args);}}");
            for(var entry:Map.of("com/mylove/MySqlMigrationSmokeIT","static final","com/mylove/DatabaseBaselineMaintenanceIT","final","com/mylove/database/DatabaseReleaseMaintenanceIT","final").entrySet()){
                var full=entry.getKey();var split=full.lastIndexOf('/');var pkg=full.substring(0,split).replace('/','.');var name=full.substring(split+1);var file=root.resolve("src/test/java/"+full+".java");Files.createDirectories(file.getParent());
                Files.writeString(file,"package "+pkg+"; @org.testcontainers.junit.jupiter.Testcontainers class "+name+" { @org.testcontainers.junit.jupiter.Container "+entry.getValue()+" org.testcontainers.containers.MySQLContainer<?> database=new org.testcontainers.containers.MySQLContainer<>(\"mysql:8.0\").withDatabaseName(\"fixture\"); @org.junit.jupiter.api.Test void realMysql() throws Exception {try(var c=java.sql.DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());var s=c.createStatement()){s.execute(\"CREATE TABLE fixture(id INT PRIMARY KEY)\");s.execute(\"INSERT INTO fixture VALUES (7)\");try(var r=s.executeQuery(\"SELECT id,VERSION() FROM fixture\")){org.junit.jupiter.api.Assertions.assertTrue(r.next());org.junit.jupiter.api.Assertions.assertEquals(7,r.getInt(1));org.junit.jupiter.api.Assertions.assertTrue(r.getString(2).startsWith(\"8.0\"));}try(var r=s.executeQuery(\"SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fixture'\")){org.junit.jupiter.api.Assertions.assertTrue(r.next());org.junit.jupiter.api.Assertions.assertEquals(\"utf8mb4_0900_ai_ci\",r.getString(1));}}} @org.junit.jupiter.api.Test void freshLifecycle() {org.junit.jupiter.api.Assertions.assertTrue(database.getJdbcUrl().contains(\"studio_\"));}}");
            }
            var project=new LocalProject(UUID.randomUUID().toString(),"合成验证夹具",root.toString(),List.of("src"),List.of("mysql-data","uploads"),1,false,true);var workspace=new CodingWorkspace(root.toString());
            var database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();var jdbc=new JdbcTemplate(database);jdbc.execute("CREATE TABLE mysql_verification(id VARCHAR(36),project_id VARCHAR(36),state VARCHAR(32),record_json CLOB,created_at TIMESTAMP(6))");
            var runtime=mock(ProjectRuntimeService.class);when(runtime.requireMysql(project.id())).thenReturn(new ProjectRuntimeService.Configuration(project.id(),0,"SHIGUANGXV_SPRING_BOOT_H2_V1",true,true,30));
            var docker=new PreviewDocker(System.getenv("AGENT_TEST_DOCKER_EXECUTABLE"),System.getenv("AGENT_TEST_SANDBOX_IMAGE"));
            var runner=new MySqlVerificationRunner(docker,workspace,runtime,jdbc,new ObjectMapper(),System.getenv("AGENT_TEST_MYSQL_IMAGE"));
            try(var context=ProjectExecutionContext.enter(project,ProjectSourceSnapshot.fingerprint(workspace))){var result=runner.run(".");System.out.println("MYSQL_FIXTURE_RECEIPT="+new ObjectMapper().writeValueAsString(result));assertThat(result.get("successful")).isEqualTo(true);assertThat(result.get("cleanupConfirmed")).isEqualTo(true);assertThat(result.get("allRequiredSuitesPassed")).isEqualTo(true);assertThat(Files.exists(root.resolve("src/test/java/org/testcontainers/containers/MySQLContainer.java"))).isFalse();String id=result.get("verificationId").toString();assertThat(docker.owned("container","studio-mysql-"+id+"-db",id)).isFalse();assertThat(docker.owned("network","studio-mysql-"+id,id)).isFalse();}
            finally{database.shutdown();}
        }finally{try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
}
