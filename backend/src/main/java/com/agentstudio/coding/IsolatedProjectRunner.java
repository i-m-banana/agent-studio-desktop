package com.agentstudio.coding;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IsolatedProjectRunner {
    public record Result(boolean successful,int exitCode,long durationMs,String output,boolean truncated,
                         String sourceSha256,Path snapshotRoot,Path artifact,List<Map<String,Object>> reports,String reportWarning) {}
    private final CodingWorkspace workspace;
    private final String image,executable;
    private final String engineHost=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")
            ? "npipe:////./pipe/dockerDesktopLinuxEngine" : "unix:///var/run/docker.sock";
    public IsolatedProjectRunner(CodingWorkspace workspace,
            @Value("${agent-studio.projects.sandbox-image:}") String image,
            @Value("${agent-studio.projects.docker-executable:}") String executable) {
        this.workspace=workspace;this.image=image;this.executable=executable;
    }
    public Map<String,Object> status() {
        var configured=(image.matches("[A-Za-z0-9._/:\\-]+@sha256:[0-9a-f]{64}") || image.matches("sha256:[0-9a-f]{64}")) && !executable.isBlank() && Path.of(executable).isAbsolute();
        return Map.of("configured",configured,"message",configured ? "已配置固定镜像；运行时核查本地引擎，网络关闭" : "验证关闭：需要配置 Docker 绝对路径和带 sha256 的离线验证镜像");
    }
    public String identity() { return "DOCKER:"+executable+"|ENGINE:"+engineHost+"|IMAGE:"+image+"|NETWORK:none|HOST_DATA:none"; }
    public Result run(String requestedPath,String task) throws Exception {
        if(!Boolean.TRUE.equals(status().get("configured"))) throw new IllegalStateException(status().get("message").toString());
        if(com.agentstudio.project.ProjectExecutionContext.current()==null) throw new IllegalStateException("隔离验证必须绑定本地项目");
        var engine=Path.of(executable).toRealPath();
        if(engine.startsWith(workspace.root())) throw new IllegalArgumentException("不能执行项目目录内的容器启动程序");
        var relative=CodingWorkspace.safeRelative(requestedPath);
        workspace.requireDirectory(requestedPath);
        if(!Set.of("MAVEN_TEST","NPM_TEST","NPM_BUILD","RELEASE_PACKAGE").contains(task)) throw new IllegalArgumentException("不支持的固定验证任务");
        var temporary=Files.createTempDirectory("agent-studio-source-"); var source=temporary.resolve("source"); Files.createDirectory(source);
        String container=null; String volume="agent-studio-work-"+UUID.randomUUID(); boolean keep=false; long started=System.nanoTime();
        try {
            var sourceSha=ProjectSourceSnapshot.copy(workspace,source);
            var approvedSha=com.agentstudio.project.ProjectExecutionContext.sourceSha256();
            if(approvedSha==null || !sourceSha.equals(approvedSha)) throw new IllegalStateException("源码与审批快照不一致，请重新审批");
            if(!sourceSha.equals(ProjectSourceSnapshot.fingerprint(workspace))) throw new IllegalStateException("源码在快照期间变化，请重新运行");
            var marker=task.startsWith("NPM") ? "package.json" : "pom.xml";
            if(!Files.isRegularFile(source.resolve(relative).resolve(marker))) throw new IllegalArgumentException("验证目录缺少 "+marker);
            if(source.toString().contains(",")) throw new IllegalStateException("临时目录不能包含逗号");
            var subpath=relative.toString().replace('\\','/');
            // Path is passed as an argument, never interpolated into shell code.
            var code="cp -R /source/. /work/ && cp -R /opt/maven-cache /work/.m2 && cp -R /opt/npm-cache /work/.npm && mkdir -p /work/.tmp && cd \"/work/$1\" && "+switch(task) {
                case "MAVEN_TEST" -> "mvn -o -B --no-transfer-progress -Dmaven.repo.local=/work/.m2 test";
                case "RELEASE_PACKAGE" -> "mvn -o -B --no-transfer-progress -Dmaven.repo.local=/work/.m2 clean test && mvn -o -B --no-transfer-progress -Dmaven.repo.local=/work/.m2 package -DskipTests";
                case "NPM_TEST" -> "npm ci --offline --ignore-scripts && npm test";
                default -> "npm ci --offline --ignore-scripts && npm run build";
            };
            var create=command(List.of(executable,"create","--pull=never","--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges",
                    "--user=1000:1000","--memory=1g","--cpus=2","--pids-limit=128","--ipc=none","--log-driver=none",
                    "--mount","type=bind,src="+source+",dst=/source,readonly",
                    "--mount","type=volume,src="+volume+",dst=/work","--tmpfs","/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                    "--env","HOME=/work/.tmp","--env","TMPDIR=/work/.tmp","--env","JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/work/.tmp","--env","CI=true","--env","NO_COLOR=1","--env","NPM_CONFIG_CACHE=/work/.npm","--entrypoint","/bin/sh",image,"-c",code,"agent-studio",subpath),30);
            container=create.output().trim();
            if(create.exitCode()!=0 || !container.matches("[0-9a-f]{64}")) throw new IllegalStateException("隔离容器未创建："+create.output());
            var run=command(List.of(executable,"start","--attach",container),task.equals("RELEASE_PACKAGE")?240:75);
            var state=command(List.of(executable,"inspect","--format","{{.State.ExitCode}}|{{.State.OOMKilled}}",container),10);
            if(state.exitCode()!=0 || !state.output().trim().matches("[0-9]+\\|(true|false)")) throw new IllegalStateException("无法确认容器退出状态");
            var values=state.output().trim().split("\\|");
            int exit=Integer.parseInt(values[0]); if("true".equals(values[1]))exit=137;
            run=new Command(exit,run.output(),run.truncated());
            var reports=List.<Map<String,Object>>of();String reportWarning=null;
            if(task.equals("MAVEN_TEST")||task.equals("RELEASE_PACKAGE")) {
                try { reports=readReports(container,subpath); }
                catch(Exception e) { reportWarning="本次隔离测试报告未完整取得："+e.getMessage()+"。不能使用项目目录中旧 target 报告代替。"; }
            }
            Path artifact=null;
            if(run.exitCode()==0 && task.equals("RELEASE_PACKAGE")) {
                // Copy a tar stream, extract only one bounded regular file to a fixed destination.
                artifact=source.resolve("target/app.jar"); Files.createDirectories(artifact.getParent());
                copyArtifact(container,artifact);
            }
            keep=task.equals("RELEASE_PACKAGE") && run.exitCode()==0;
            return new Result(run.exitCode()==0,run.exitCode(),(System.nanoTime()-started)/1000000,run.output(),run.truncated(),sourceSha,source,artifact,reports,reportWarning);
        } finally {
            if(container!=null && container.matches("[0-9a-f]{64}")) {
                boolean interrupted=Thread.interrupted();
                try { command(List.of(executable,"rm","--force",container),15); command(List.of(executable,"volume","rm",volume),15); } finally { if(interrupted)Thread.currentThread().interrupt(); }
            }
            if(!keep)removeSnapshot(temporary);
        }
    }
    public static void removeSnapshot(Path path) throws Exception {
        // Internal generated temp paths only; never a project or user-supplied path.
        if(!path.getFileName().toString().startsWith("agent-studio-source-")) throw new IllegalArgumentException("不是内部临时快照");
        Files.walkFileTree(path,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir,java.io.IOException error) throws java.io.IOException { if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE; }
        });
    }
    private record Command(int exitCode,String output,boolean truncated) {}
    private Command command(List<String> arguments,int seconds) throws Exception {
        var process=new ProcessBuilder(localEngineArguments(arguments)).redirectErrorStream(true).start(); process.getOutputStream().close();
        var output=new BoundedProcessOutput(8000,16000);
        var reader=Thread.startVirtualThread(()->{try(var input=new java.io.InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)) {
            var buffer=new char[2048];int count;while((count=input.read(buffer))>=0)output.append(buffer,count);
        }catch(Exception ignored){}});
        try {
            if(!process.waitFor(seconds,TimeUnit.SECONDS)) throw new IllegalStateException("隔离验证超时，已中断");
            reader.join(2000); return new Command(process.exitValue(),output.text(),output.truncated());
        } finally { if(process.isAlive()){process.descendants().forEach(p->p.destroyForcibly());process.destroyForcibly();} }
    }
    private List<Map<String,Object>> readReports(String container,String subpath)throws Exception {
        var process=new ProcessBuilder(localEngineArguments(List.of(executable,"cp",container+":/work/"+subpath+"/target/surefire-reports","-")))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();process.getOutputStream().close();
        var future=CompletableFuture.supplyAsync(()->{try(var input=process.getInputStream()){return MavenTestReports.read(input);}catch(Exception e){throw new CompletionException(e);}});
        try {
            var reports=future.get(15,TimeUnit.SECONDS);
            if(!process.waitFor(5,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("报告目录不可用");
            return reports;
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    private void copyArtifact(String container,Path destination) throws Exception {
        var process=new ProcessBuilder(localEngineArguments(List.of(executable,"cp",container+":/work/target/app.jar","-"))).start();process.getOutputStream().close();
        var future=CompletableFuture.runAsync(()->{
            try(var input=process.getInputStream()) {
                var header=input.readNBytes(512);int extended=0;
                while(header.length==512 && header[156]=='x') {
                    if(++extended>4)throw new IllegalStateException("制品扩展归档条目过多");
                    long meta=Long.parseLong(new String(header,124,12,java.nio.charset.StandardCharsets.US_ASCII).replace("\0","").trim(),8);
                    if(meta<0 || meta>16384 || input.readNBytes((int)(meta+(512-meta%512)%512)).length!=meta+(512-meta%512)%512)
                        throw new IllegalStateException("制品扩展归档无效");
                    header=input.readNBytes(512);
                }
                if(header.length!=512 || (header[156]!=0 && header[156]!='0')) throw new IllegalStateException("制品不是普通文件");
                var sizeText=new String(header,124,12,java.nio.charset.StandardCharsets.US_ASCII).replace("\0","").trim();
                long size=Long.parseLong(sizeText,8);
                if(size<=0 || size>128*1024*1024)throw new IllegalStateException("制品大小无效或超过 128 MiB");
                try(var output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)) {
                    var buffer=new byte[8192];long left=size;
                    while(left>0){int n=input.read(buffer,0,(int)Math.min(buffer.length,left));if(n<0)throw new java.io.EOFException();output.write(buffer,0,n);left-=n;}
                }
                long padding=(512-size%512)%512; if(input.readNBytes((int)padding).length!=padding)throw new java.io.EOFException();
                var remaining=input.readNBytes(65537);
                if(remaining.length>65536)throw new IllegalStateException("制品归档尾部过长");
                for(var b:remaining)if(b!=0)throw new IllegalStateException("制品归档含额外条目");
            }catch(Exception e){throw new CompletionException(e);}
        });
        try {
            future.get(30,TimeUnit.SECONDS);
            if(!process.waitFor(5,TimeUnit.SECONDS) || process.exitValue()!=0)throw new IllegalStateException("制品提取失败");
        } catch(Exception e){Files.deleteIfExists(destination);throw e;}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    private List<String> localEngineArguments(List<String> args) {
        var result=new ArrayList<String>(); result.add(args.getFirst());result.add("--host");result.add(engineHost);result.addAll(args.subList(1,args.size()));return result;
    }
}
