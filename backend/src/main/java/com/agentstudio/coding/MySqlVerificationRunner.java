package com.agentstudio.coding;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.agentstudio.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Fixed synthetic MySQL suite. Engine remains platform-side; no production mounts or socket. */
@Service
public class MySqlVerificationRunner {
    private final PreviewDocker docker;private final CodingWorkspace workspace;private final ProjectRuntimeService runtime;
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final String mysqlImage;
    public MySqlVerificationRunner(PreviewDocker docker,CodingWorkspace workspace,ProjectRuntimeService runtime,JdbcTemplate jdbc,ObjectMapper json,
        @Value("${agent-studio.projects.mysql-image:}")String mysqlImage){this.docker=docker;this.workspace=workspace;this.runtime=runtime;this.jdbc=jdbc;this.json=json;this.mysqlImage=mysqlImage;}
    public String identity(){return "MYSQL_SUITE:v2|COLLATION:utf8mb4_0900_ai_ci|MYSQL_IMAGE:"+mysqlImage+"|NETWORK:owned-internal|DATA:synthetic|SOCKET:none";}
    @jakarta.annotation.PostConstruct void recoverInterrupted(){
        for(var record:jdbc.query("SELECT record_json FROM mysql_verification WHERE state='RUNNING'",(rs,n)->{try{return json.readValue(rs.getString(1),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}})){
            record.put("state","CLEANUP_REQUIRED");record.put("successful",false);record.put("cleanupConfirmed",false);record.put("cleanupMessage","上次平台中断；先回收原验证资源，不会重跑测试");try{save(record);}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    public List<Map<String,Object>> list(String project){return jdbc.query("SELECT record_json FROM mysql_verification WHERE project_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->{try{return json.readValue(rs.getString(1),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}},project);}
    private void save(Map<String,Object> r)throws Exception {if(jdbc.update("UPDATE mysql_verification SET state=?,record_json=? WHERE id=?",r.get("state"),json.writeValueAsString(r),r.get("verificationId"))==0)jdbc.update("INSERT INTO mysql_verification(id,project_id,state,record_json,created_at) VALUES(?,?,?,?,?)",r.get("verificationId"),r.get("projectId"),r.get("state"),json.writeValueAsString(r),java.sql.Timestamp.from(Instant.parse(r.get("createdAt").toString())));}
    public synchronized Map<String,Object> run(String path)throws Exception{
        var p=Objects.requireNonNull(ProjectExecutionContext.current(),"必须绑定项目");runtime.requireMysql(p.id());docker.requireConfigured();
        if(!mysqlImage.matches("sha256:[0-9a-f]{64}"))throw new IllegalStateException("尚未配置固定 MySQL 8.0 镜像，不会下载或访问生产");
        if(!CodingWorkspace.safeRelative(path).toString().isEmpty())throw new IllegalArgumentException("当前 MySQL 预设仅支持项目根目录 .");
        if(Path.of(docker.executable).toRealPath().startsWith(workspace.root()))throw new IllegalArgumentException("容器启动程序不能位于项目内");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM mysql_verification WHERE project_id=? AND state IN ('RUNNING','CLEANUP_REQUIRED')",Integer.class,p.id())>0)throw new IllegalStateException("先回收原 MySQL 验证资源，不能重复创建");
        var id=UUID.randomUUID().toString();var net="studio-mysql-"+id;var db=net+"-db";var test=net+"-test";
        var temp=Files.createTempDirectory("agent-studio-source-");var source=Files.createDirectory(temp.resolve("source"));
        var r=new LinkedHashMap<String,Object>();r.put("verificationId",id);r.put("projectId",p.id());r.put("createdAt",Instant.now().toString());r.put("state","RUNNING");r.put("task","MYSQL_INTEGRATION");r.put("successful",false);r.put("isolated",true);r.put("network","owned-internal");r.put("database","MYSQL_8_SYNTHETIC");r.put("mysqlImage",mysqlImage);r.put("productionModified",false);r.put("adapterVersion",2);r.put("databaseCollation","utf8mb4_0900_ai_ci");r.put("scope","原有三组集成测试及断言；平台适配容器生命周期，不验证 Testcontainers 的 Docker 管理");
        r.put("internalSnapshot",temp.toString());long began=System.nanoTime();boolean recorded=false;
        try{
            var sha=ProjectSourceSnapshot.copy(workspace,source);r.put("sourceSha256",sha);
            if(!sha.equals(ProjectExecutionContext.sourceSha256())||!sha.equals(ProjectSourceSnapshot.fingerprint(workspace)))throw new IllegalStateException("源码与审批不一致");
            for(var file:List.of("com/mylove/MySqlMigrationSmokeIT.java","com/mylove/DatabaseBaselineMaintenanceIT.java","com/mylove/database/DatabaseReleaseMaintenanceIT.java"))if(!Files.isRegularFile(source.resolve("src/test/java/"+file)))throw new IllegalStateException("不支持的测试预设：缺少 "+file);
            var adapter=source.resolve("src/test/java/org/testcontainers/containers/MySQLContainer.java");if(Files.exists(adapter))throw new IllegalStateException("项目存在生命周期适配器冲突");
            Files.createDirectories(adapter.getParent());try(var input=getClass().getResourceAsStream("/preview/MySQLContainer.java.txt")){Files.copy(Objects.requireNonNull(input),adapter);}
            save(r);recorded=true;var password=UUID.randomUUID().toString().replace("-","");
            docker.run(List.of("network","create","--internal","--opt","com.docker.network.bridge.gateway_mode_ipv4=isolated","--label","agent-studio.preview="+id,net),15);
            var network=json.readTree(docker.run(List.of("network","inspect","--format","{{json .}}",net),10));
            if(!network.path("Internal").asBoolean()||!network.path("Options").path("com.docker.network.bridge.gateway_mode_ipv4").asText().equals("isolated"))throw new IllegalStateException("内部网络隔离未确认，测试不会启动");
            docker.run(List.of("create","--name",db,"--label","agent-studio.preview="+id,"--pull=never","--network",net,"--network-alias","studio-mysql","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--user=999:999","--memory=768m","--cpus=1","--pids-limit=128","--log-driver=none","--tmpfs","/var/lib/mysql:rw,nosuid,nodev,size=512m,uid=999,gid=999","--tmpfs","/var/run/mysqld:rw,nosuid,nodev,size=16m,uid=999,gid=999","--tmpfs","/tmp:rw,nosuid,nodev,size=32m,mode=1777","--env","MYSQL_ROOT_PASSWORD="+password,"--env","MYSQL_ROOT_HOST=%",mysqlImage,"--innodb-buffer-pool-size=128M","--skip-log-bin"),30);
            docker.run(List.of("start",db),15);
            var deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);boolean ready=false;
            while(System.nanoTime()<deadline){if(Thread.currentThread().isInterrupted())throw new InterruptedException();try{docker.run(List.of("exec",db,"mysqladmin","ping","--silent"),5);ready=true;break;}catch(Exception e){Thread.sleep(1500);}}
            if(!ready)throw new IllegalStateException("合成 MySQL 未就绪，测试未运行");
            var code="cp -R /source/. /work/ && cp -R /opt/maven-cache /work/.m2 && mkdir -p /work/.tmp; mvn -o -B --no-transfer-progress -Dmaven.repo.local=/work/.m2 -Pmysql-verification -DskipTests=false -DskipITs=false -Dfailsafe.failIfNoSpecifiedTests=true -Dit.test=MySqlMigrationSmokeIT,DatabaseBaselineMaintenanceIT,DatabaseReleaseMaintenanceIT verify > /work/.studio-output 2>&1; result=$?; printf '%s' \"$result\" > /work/.studio-exit; sleep 480";
            docker.run(List.of("create","--name",test,"--label","agent-studio.preview="+id,"--pull=never","--network",net,"--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--user=1000:1000","--memory=1536m","--cpus=2","--pids-limit=256","--ipc=none","--log-driver=none","--mount","type=bind,src="+source+",dst=/source,readonly","--tmpfs","/work:rw,nosuid,nodev,size=1536m,uid=1000,gid=1000","--tmpfs","/tmp:rw,nosuid,nodev,size=128m,mode=1777","--env","STUDIO_SYNTHETIC_MYSQL_PASSWORD="+password,"--env","HOME=/work/.tmp","--env","JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/work/.tmp","--entrypoint","/bin/sh",docker.image,"-c",code),30);
            if(!docker.run(List.of("inspect","--format","{{.HostConfig.NetworkMode}}|{{.HostConfig.ReadonlyRootfs}}|{{.Config.User}}|{{json .HostConfig.PortBindings}}",test),10).equals(net+"|true|1000:1000|{}"))throw new IllegalStateException("测试容器隔离配置未确认");
            r.put("isolationConfirmed",true);
            docker.run(List.of("start",test),15);
            var finished=System.nanoTime()+TimeUnit.SECONDS.toNanos(360);boolean done=false;
            while(System.nanoTime()<finished){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                try{docker.run(List.of("exec",test,"test","-f","/work/.studio-exit"),5);done=true;break;}catch(Exception e){
                    if(!"true".equals(docker.run(List.of("inspect","--format","{{.State.Running}}",test),5)))throw new IllegalStateException("测试容器在报告产生前停止，测试未确认");Thread.sleep(1500);
                }
            }
            if(!done)throw new IllegalStateException("MySQL 集成验证超时，未确认测试结果");
            String output=docker.run(List.of("exec",test,"tail","-c","16000","/work/.studio-output"),10);
            var exit=Integer.parseInt(docker.run(List.of("exec",test,"cat","/work/.studio-exit"),10).strip());
            r.put("exitCode",exit);r.put("output",output.replace(password,"[synthetic credential redacted]"));r.put("outputTruncated",output.length()>=15900);
            // Failsafe must execute all fixed suites, with no skips. Headers are bounded and externally produced.
            var reports=new ArrayList<Map<String,Object>>();
            for(var suite:List.of("com.mylove.MySqlMigrationSmokeIT","com.mylove.DatabaseBaselineMaintenanceIT","com.mylove.database.DatabaseReleaseMaintenanceIT"))reports.add(readReport(test,suite));
            r.put("reports",reports);boolean complete=reports.stream().allMatch(item->Boolean.TRUE.equals(item.get("passed")));r.put("allRequiredSuitesPassed",complete);r.put("successful",exit==0&&complete);r.put("state",Boolean.TRUE.equals(r.get("successful"))?"SUCCEEDED":"FAILED");
        }catch(Exception e){r.put("state","FAILED");r.put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        finally{
            boolean interrupted=Thread.interrupted();
            try{for(var name:List.of(test,db))if(docker.owned("container",name,id))docker.run(List.of("rm","--force",name),15);if(docker.owned("network",net,id))docker.run(List.of("network","rm",net),15);r.put("cleanupConfirmed",true);}
            catch(Exception e){r.put("state","CLEANUP_REQUIRED");r.put("successful",false);r.put("cleanupConfirmed",false);r.put("cleanupMessage","原隔离资源待回收，不能重复创建");}
            try{IsolatedProjectRunner.removeSnapshot(temp);}catch(Exception e){r.put("state","CLEANUP_REQUIRED");r.put("successful",false);}
            if(interrupted)Thread.currentThread().interrupt();r.put("durationMs",(System.nanoTime()-began)/1000000);if(recorded)save(r);
        }
        return r;
    }
    static boolean verifiedReports(String output){
        for(var name:List.of("com.mylove.MySqlMigrationSmokeIT","com.mylove.DatabaseBaselineMaintenanceIT","com.mylove.database.DatabaseReleaseMaintenanceIT")){
            var matcher=java.util.regex.Pattern.compile("<testsuite\\b[^>]*name=\""+java.util.regex.Pattern.quote(name)+"\"[^>]*>").matcher(output);if(!matcher.find())return false;var line=matcher.group();
            for(var attr:List.of("failures","errors","skipped"))if(!line.contains(attr+"=\"0\""))return false;
            var count=java.util.regex.Pattern.compile("tests=\"([0-9]+)\"").matcher(line);if(!count.find()||Integer.parseInt(count.group(1))<1)return false;
        }return true;
    }
    private Map<String,Object> readReport(String container,String suite)throws Exception{
        var file="/work/target/failsafe-reports/TEST-"+suite+".xml";
        docker.run(List.of("exec",container,"test","-f",file),5);
        if(!docker.run(List.of("exec",container,"stat","--format=%F",file),5).equals("regular file"))throw new IllegalStateException("测试报告不是普通文件");
        var process=docker.spawn(List.of("exec",container,"cat",file));
        var future=java.util.concurrent.CompletableFuture.supplyAsync(()->{try(var in=process.getInputStream()){
            var bytes=in.readNBytes(2*1024*1024+1);if(bytes.length==0||bytes.length>2*1024*1024)throw new IllegalStateException("测试报告为空或超过 2 MiB");return bytes;
        }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
        try{
            var bytes=future.get(15,TimeUnit.SECONDS);if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("测试报告提取失败");
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var root=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();if(!"testsuite".equals(root.getTagName())||!suite.equals(root.getAttribute("name")))throw new IllegalStateException("测试报告身份不符");
            int tests=Integer.parseInt(root.getAttribute("tests")),failures=Integer.parseInt(root.getAttribute("failures")),errors=Integer.parseInt(root.getAttribute("errors")),skipped=Integer.parseInt(root.getAttribute("skipped"));
            return Map.of("suite",suite,"tests",tests,"failures",failures,"errors",errors,"skipped",skipped,"passed",tests>0&&failures==0&&errors==0&&skipped==0);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    public synchronized void cleanup(String project,String id)throws Exception{
        var record=jdbc.query("SELECT record_json FROM mysql_verification WHERE project_id=? AND id=?",(rs,n)->{try{return json.readValue(rs.getString(1),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}},project,id).stream().findFirst().orElseThrow();
        if(!"CLEANUP_REQUIRED".equals(record.get("state")))throw new IllegalStateException("该验证不需要回收");
        if(!id.matches("[0-9a-f-]{36}"))throw new IllegalStateException("身份无效");var net="studio-mysql-"+id;
        for(var name:List.of(net+"-test",net+"-db"))if(docker.owned("container",name,id))docker.run(List.of("rm","--force",name),15);
        if(docker.owned("network",net,id))docker.run(List.of("network","rm",net),15);
        if(record.get("internalSnapshot") instanceof String value){var path=Path.of(value).toAbsolutePath().normalize();var base=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();if(!path.getParent().equals(base)||!path.getFileName().toString().startsWith("agent-studio-source-"))throw new IllegalStateException("临时资源范围无效");if(Files.exists(path))IsolatedProjectRunner.removeSnapshot(path);}
        record.put("state","FAILED");record.put("cleanupConfirmed",true);save(record);
    }
}
