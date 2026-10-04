package com.agentstudio.coding;

import com.agentstudio.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalPreviewService {
    public record Preview(String id, String projectId, String state, String sourceSha256, String url,
                          long expiresAt, String container, String volume, String snapshot, String message) {}
    private final CodingWorkspace workspace;
    private final IsolatedProjectRunner runner;
    private final PreviewDocker docker;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private ProjectRuntimeService runtime;
    private final Map<String,HttpServer> servers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "preview-expiry"); t.setDaemon(true); return t; });
    private final Semaphore requests = new Semaphore(4);
    public LocalPreviewService(CodingWorkspace workspace, IsolatedProjectRunner runner, PreviewDocker docker, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.workspace=workspace; this.runner=runner; this.docker=docker; this.jdbc=jdbc; this.mapper=mapper;
    }
    @jakarta.annotation.PostConstruct void initialize() {
        // A restart never silently resumes workload execution. Cleanup is retried visibly.
        timer.scheduleWithFixedDelay(this::sweep, 5, 15, TimeUnit.SECONDS);
    }
    synchronized void sweep() {
        try { for (var p : all()) if (!Set.of("STOPPED", "FAILED").contains(p.state()) && (!servers.containsKey(p.id()) || p.expiresAt() <= System.currentTimeMillis())) stop(p.id()); }
        catch (Exception ignored) { /* persisted state remains available for manual retry */ }
    }
    @jakarta.annotation.PreDestroy void shutdown() {
        timer.shutdownNow();
        try { for(var p:all()) if(!Set.of("STOPPED","FAILED").contains(p.state())) {
            try { stop(p.id()); } catch(Exception ignored) { /* retry persisted cleanup on next startup */ }
        }} catch(Exception ignored) { /* database unavailable; hard container TTL still applies */ }
        for (var server : servers.values()) server.stop(0);
    }
    List<Preview> all() { return jdbc.query("SELECT record_json FROM local_preview ORDER BY expires_at DESC", (rs,n) -> decode(rs.getString(1))); }
    public List<Preview> list(String projectId) { return all().stream().filter(p -> p.projectId().equals(projectId)).map(p ->
            new Preview(p.id(), p.projectId(), p.state(), p.sourceSha256(), p.state().equals("READY") && servers.containsKey(p.id()) ? p.url() : null,
                    p.expiresAt(), null, null, null, p.message())).toList(); }
    private Preview decode(String json) { try { return mapper.readValue(json, Preview.class); } catch (Exception e) { throw new IllegalStateException("预览记录损坏",e); } }
    private void save(Preview p) throws Exception {
        if (jdbc.update("UPDATE local_preview SET state=?,expires_at=?,record_json=? WHERE id=?",p.state(),p.expiresAt(),mapper.writeValueAsString(p),p.id())==0)
            jdbc.update("INSERT INTO local_preview(id,project_id,state,expires_at,record_json) VALUES(?,?,?,?,?)",p.id(),p.projectId(),p.state(),p.expiresAt(),mapper.writeValueAsString(p));
    }
    public synchronized Preview start() throws Exception {
        var project=ProjectExecutionContext.current();
        if (project==null || project.archived() || !project.protectionConfirmed()) throw new IllegalStateException("预览必须绑定已确认保护范围的可用项目");
        var minutes=runtime==null?30:runtime.requirePreview(project.id()).previewMinutes();
        // First profile only. Not a general launcher or model-selected command.
        workspace.requireRegularFile("src/main/java/com/mylove/controller/BrowseController.java");
        workspace.requireRegularFile("src/main/resources/db/migration/h2/V1__current_website_schema.sql");
        docker.requireConfigured();
        if (Path.of(docker.executable).toRealPath().startsWith(workspace.root())) throw new IllegalArgumentException("不能执行项目内的Docker启动程序");
        if (all().stream().anyMatch(p -> !Set.of("STOPPED","FAILED").contains(p.state()))) throw new IllegalStateException("已有预览或待清理资源，请先停止；首版最多一个实例");
        var built=runner.run(".","RELEASE_PACKAGE");
        if (!built.successful()) throw new IllegalStateException("预览测试/打包未通过；请先运行本地验证查看失败结果");
        String id=UUID.randomUUID().toString(), name="agent-studio-preview-"+id;
        var p=new Preview(id,project.id(),"STARTING",built.sourceSha256(),null,System.currentTimeMillis()+minutes*60_000L,name,name,built.snapshotRoot().getParent().toString(),"启动中：H2合成数据，不是MySQL验证");
        boolean recorded=false;
        try {
            save(p); recorded=true;
            var trusted=built.snapshotRoot().getParent().resolve("preview"); Files.createDirectory(trusted);
            for (var file : List.of("PreviewBridge.java","application.properties","data.sql")) {
                try (var input=getClass().getResourceAsStream("/preview/"+file)) { if(input==null)throw new IllegalStateException("缺少预览资源"); Files.copy(input,trusted.resolve(file)); }
            }
            Files.createDirectory(trusted.resolve("migration"));
            Files.copy(trusted.resolve("data.sql"),trusted.resolve("migration/afterMigrate.sql"));
            docker.run(List.of("volume","create","--label","agent-studio.preview="+id,name),15);
            var code="mkdir -p /work/bridge /work/uploads && javac -d /work/bridge /preview/PreviewBridge.java && "
                    +"cp /source/target/app.jar /work/app.jar && H2=$(find /opt/maven-cache/com/h2database/h2 -name 'h2-*.jar' | head -n 1) && test -n \"$H2\" && "
                    +"exec timeout --signal=TERM --kill-after=5s "+(minutes*60)+"s java -Xmx384m -Dloader.path=\"$H2\" -cp /work/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.location=file:/preview/application.properties";
            docker.run(List.of("create","--name",name,"--label","agent-studio.preview="+id,"--pull=never","--network=none","--read-only",
                    "--cap-drop=ALL","--security-opt=no-new-privileges","--user=1000:1000","--memory=768m","--cpus=1","--pids-limit=128","--ipc=none",
                    "--log-driver=local","--log-opt=max-size=1m","--log-opt=max-file=2","--restart=no",
                    "--mount","type=bind,src="+built.snapshotRoot()+",dst=/source,readonly",
                    "--mount","type=bind,src="+trusted+",dst=/preview,readonly",
                    "--mount","type=volume,src="+name+",dst=/work","--tmpfs","/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                    "--env","HOME=/work","--entrypoint","/bin/sh",docker.image,"-c",code),20);
            docker.run(List.of("start",name),15);
            boolean ready=false;long readinessDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(System.nanoTime()<readinessDeadline) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if (!docker.run(List.of("inspect","--format","{{.State.Running}}",name),5).equals("true"))break;
                try { if (exchange(p,"GET","/browse",Map.of(),new byte[0]).status()==200) {ready=true;break;} }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw e; }
                catch(Exception ignored) {}
                Thread.sleep(500);
            }
            if (!ready) throw new IllegalStateException("网站预览未就绪。" + startupDiagnostics(name));
            var server=HttpServer.create(new InetSocketAddress("127.0.0.2",0),8);
            String url="http://127.0.0.2:"+server.getAddress().getPort();
            var fixed=p;
            server.createContext("/", e -> handle(fixed,url,e)); server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servers.put(id,server); server.start();
            p=new Preview(id,project.id(),"READY",built.sourceSha256(),url,p.expiresAt(),name,name,p.snapshot(),"H2合成预览；已执行当前H2版本迁移；"+minutes+"分钟到期。外部字体关闭；此预览不能替代MySQL集成验证。");
            save(p); return p;
        } catch(Exception e) {
            boolean interrupted=Thread.interrupted();
            try {
                if(recorded) {
                    stop(id);
                    save(new Preview(p.id(),p.projectId(),"FAILED",p.sourceSha256(),null,p.expiresAt(),p.container(),p.volume(),null,
                            "预览启动失败；本次隔离资源已清理，原项目未修改。"+safeDiagnostic(e.getMessage())));
                } else IsolatedProjectRunner.removeSnapshot(built.snapshotRoot().getParent());
            }
            catch(Exception cleanup) { e.addSuppressed(cleanup); }
            finally { if(interrupted)Thread.currentThread().interrupt(); }
            throw e;
        }
    }
    String startupDiagnostics(String container) {
        var details=new StringBuilder();
        try { details.append("容器状态：").append(docker.run(List.of("inspect","--format","{{.State.Status}}|exit={{.State.ExitCode}}|oom={{.State.OOMKilled}}",container),5)); }
        catch(Exception ignored) { details.append("容器状态未取得。"); }
        try { details.append("\n启动日志：\n").append(docker.run(List.of("logs","--tail","100",container),5)); }
        catch(Exception ignored) { details.append("\n启动日志未取得。"); }
        return safeDiagnostic(details.toString());
    }
    static String safeDiagnostic(String message) {
        if(message==null)return "没有取得启动诊断。";
        var result=message.replace("preview-only-admin","[preview credential]")
                .replace("preview-only-never-production-12345678901234567890","[preview credential]");
        return result.length()>6000?"启动日志已截断：\n"+result.substring(result.length()-6000):result;
    }
    public synchronized void stop(String id) throws Exception {
        if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("无效预览ID");
        var p=all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("预览不存在"));
        var server=servers.remove(id);if(server!=null)server.stop(0);
        try {
            String expected="agent-studio-preview-"+id;
            if(!expected.equals(p.container()) || !expected.equals(p.volume()))throw new IllegalStateException("预览资源身份不符");
            if(docker.owned("container",expected,id))docker.run(List.of("rm","--force",expected),15);
            if(docker.owned("volume",expected,id))docker.run(List.of("volume","rm",expected),15);
            if(p.snapshot()!=null) {
                var path=Path.of(p.snapshot()).toAbsolutePath().normalize();
                if(!path.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))throw new IllegalStateException("快照不在内部临时根");
                if(Files.exists(path))IsolatedProjectRunner.removeSnapshot(path);
            }
            save(new Preview(p.id(),p.projectId(),"STOPPED",p.sourceSha256(),null,p.expiresAt(),p.container(),p.volume(),null,"已停止并清理本次预览临时资源；原项目未修改"));
        } catch(Exception e) {
            save(new Preview(p.id(),p.projectId(),"CLEANUP_REQUIRED",p.sourceSha256(),null,p.expiresAt(),p.container(),p.volume(),p.snapshot(),"资源回收未确认，请重试停止；不运行全局清理"));throw e;
        }
    }
    record Reply(int status, Map<String,List<String>> headers, byte[] body) {}
    Reply exchange(Preview p,String method,String path,Map<String,List<String>> headers,byte[] body) throws Exception {
        var process=docker.spawn(List.of("exec","-i",p.container(),"java","-Xmx32m","-cp","/work/bridge","PreviewBridge"));
        var error=Thread.startVirtualThread(()->{try{process.getErrorStream().transferTo(OutputStream.nullOutputStream());}catch(Exception ignored){}});
        var future=CompletableFuture.supplyAsync(()->{try {
            try(var out=new DataOutputStream(process.getOutputStream())) {
                out.writeUTF(method);out.writeUTF(path);
                var selected=headers.entrySet().stream().filter(e -> Set.of("accept","content-type","cookie","user-agent").contains(e.getKey().toLowerCase(Locale.ROOT))).toList();
                out.writeInt(selected.size());for(var e:selected){out.writeUTF(e.getKey().toLowerCase(Locale.ROOT));out.writeUTF(String.join("; ",e.getValue()));}
                out.writeInt(body.length);out.write(body);
            }
            try(var in=new DataInputStream(process.getInputStream())) {
                int status=in.readInt(), count=in.readInt(); if(status<100||status>599||count<0||count>32)throw new IOException();
                var returned=new HashMap<String,List<String>>();for(int i=0;i<count;i++){var key=in.readUTF();var value=in.readUTF();if(!Set.of("content-type","location","set-cookie").contains(key))throw new IOException();returned.computeIfAbsent(key,k->new ArrayList<>()).add(value);}
                int size=in.readInt();if(size<0||size>4*1024*1024)throw new IOException();var bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();return new Reply(status,returned,bytes);
            }
        }catch(Exception e){throw new CompletionException(e);}});
        try {var reply=future.get(12,TimeUnit.SECONDS);if(!process.waitFor(1,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException();return reply;}
        finally {future.cancel(true);if(process.isAlive())process.destroyForcibly();error.join(100);}
    }
    void handle(Preview p,String url,HttpExchange e) throws IOException {
        try {
            if(p.expiresAt()<=System.currentTimeMillis() || !URI.create(url).getAuthority().equals(e.getRequestHeaders().getFirst("Host"))) {e.sendResponseHeaders(403,-1);return;}
            var origin=e.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals(url)){e.sendResponseHeaders(403,-1);return;}
            if(!Set.of("GET","HEAD","POST","DELETE","PUT").contains(e.getRequestMethod())){e.sendResponseHeaders(405,-1);return;}
            if(!requests.tryAcquire()){e.sendResponseHeaders(429,-1);return;}
            try {
                byte[] body=e.getRequestBody().readNBytes(1024*1024+1);if(body.length>1024*1024){e.sendResponseHeaders(413,-1);return;}
                var reply=exchange(p,e.getRequestMethod(),e.getRequestURI().toASCIIString(),e.getRequestHeaders(),body);
                reply.headers().forEach((key,value)->e.getResponseHeaders().put(key,value));
                e.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'");
                e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                e.sendResponseHeaders(reply.status(),e.getRequestMethod().equals("HEAD")||reply.body().length==0?-1:reply.body().length);
                if(!e.getRequestMethod().equals("HEAD"))e.getResponseBody().write(reply.body());
            }finally{requests.release();}
        }catch(Exception ex){try{e.sendResponseHeaders(502,-1);}catch(Exception ignored){}}
        finally{e.close();}
    }
}
