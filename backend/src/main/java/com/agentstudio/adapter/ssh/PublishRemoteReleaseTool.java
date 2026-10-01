package com.agentstudio.adapter.ssh;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.apache.sshd.client.channel.ClientChannelEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public final class PublishRemoteReleaseTool implements AgentTool {
    static final List<String> FIELDS = List.of("releaseId", "manifestSha256", "imageId", "schemaSha256", "backupId", "backupManifestSha256", "previousImageId", "productionSha256");
    static String pattern(String key) {
        return key.equals("imageId") || key.equals("previousImageId") ? "sha256:[0-9a-f]{64}"
                : key.equals("releaseId") || key.equals("backupId") ? "[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}" : "[0-9a-f]{64}";
    }
    private static Map<String,Object> schema() {
        var properties = new LinkedHashMap<String,Object>();
        FIELDS.forEach(k -> properties.put(k, Map.of("type", "string", "pattern", "^" + pattern(k) + "$")));
        return Map.of("type", "object", "properties", properties, "required", FIELDS, "additionalProperties", false);
    }
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor("publish_remote_release", "受审上线并验证恢复",
            "绑定候选、镜像、结构、新备份及当前生产身份；校验兼容扩展迁移，仅重建app、刷新Nginx并验证健康，失败自动恢复旧应用。数据库DDL不自动撤销；状态不明须人工处理。拒绝任意命令、路径、SQL、环境变量或选项。",
            "SSH", "EXECUTE", "HIGH", 960, schema());
    private final RemoteDeploymentWorkspace workspace;
    private final RemoteDeploymentService profiles;
    private final ObjectMapper mapper;
    private final Duration budget;
    @org.springframework.beans.factory.annotation.Autowired
    public PublishRemoteReleaseTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles, ObjectMapper mapper) {
        this(workspace,profiles,mapper,Duration.ofSeconds(900));
    }
    PublishRemoteReleaseTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles, ObjectMapper mapper, Duration budget) {
        this.workspace=workspace; this.profiles=profiles; this.mapper=mapper;
        this.budget=budget;
    }
    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() {
        return "SSH:" + profiles.approvalTarget() + "|BACKUP_ROOT:" + profiles.current().remoteBackupRoot()
                + "|APP_RECREATE+NGINX_RELOAD|COMPATIBLE_MIGRATIONS|AUTO_APP_ROLLBACK|NO_DB_RESTORE|BACKUP_MAX_AGE:1800s";
    }
    static Map<String,String> validate(JsonNode arguments) {
        if (!arguments.isObject() || arguments.size()!=FIELDS.size()) throw new IllegalArgumentException("上线只接受八项绑定身份");
        var result = new LinkedHashMap<String,String>();
        for (var key : FIELDS) {
            var value=arguments.path(key);
            if (!value.isTextual() || !value.asText().matches(pattern(key))) throw new IllegalArgumentException("绑定字段无效：" + key);
            result.put(key,value.asText());
        }
        if (result.get("imageId").equals(result.get("previousImageId"))) throw new IllegalArgumentException("候选已是当前镜像，不重复重建");
        return result;
    }
    @Override public String execute(JsonNode arguments) throws Exception {
        var args=validate(arguments); var profile=profiles.current(); var started=System.nanoTime();
        return workspace.execute(profile, (session, access, properties) -> {
            var candidate=access.validateCandidate(args.get("releaseId"));
            var manifest=access.readCandidateManifest(candidate);
            if (!RemoteReleaseCandidateStager.sha256(manifest).equals(args.get("manifestSha256"))) throw new SecurityException("候选摘要不匹配");
            var hashes=BuildReleaseCandidateImageTool.parseManifest(manifest,args.get("releaseId"));
            var backup=access.validateBackup(args.get("backupId")); var bytes=access.readCandidateManifest(backup);
            if (!RemoteReleaseCandidateStager.sha256(bytes).equals(args.get("backupManifestSha256"))) throw new SecurityException("备份摘要不匹配");
            var fields=new Properties(); fields.load(new java.io.StringReader(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            if (!"1".equals(fields.getProperty("backupFormat")) || !args.get("backupId").equals(fields.getProperty("backupId"))
                    || !profile.remoteDeployRoot().equals(fields.getProperty("deploymentRoot")) || !profile.composeProject().equals(fields.getProperty("composeProject")))
                throw new SecurityException("备份目标不匹配");
            var age=Duration.between(Instant.parse(fields.getProperty("createdAt")),Instant.now());
            if (age.isNegative() || age.compareTo(Duration.ofMinutes(30))>0) throw new SecurityException("上线需要30分钟内的新备份");
            var token=UUID.randomUUID().toString().replace("-", ""); var attempt=access.createPublishAttempt(candidate,token);
            access.uploadExclusive(attempt,"publish.sh",new PublishReleaseCommands().script(profile,candidate,attempt,token,args,hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var out=new BoundedSshOutputStream(16_000);
            Integer exit;
            // TERM allows the remote trap to finish bounded recovery; KILL is only a final ceiling.
            try (var channel=session.createExecChannel("timeout --signal=TERM --kill-after=240s 600s sh " + PublishReleaseCommands.q(attempt + "/publish.sh"))) {
                channel.setOut(out); channel.setRedirectErrorStream(true); channel.open().verify(properties.connectTimeout());
                var end=System.nanoTime()+budget.toNanos();
                while (!channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),250).contains(ClientChannelEvent.CLOSED)) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime()>=end) {
                        channel.close(true);
                        throw new IllegalStateException("发布通道中断，不能确认已上线或恢复；停止重试，先核查线上状态及回执：" + attempt);
                    }
                }
                exit=channel.getExitStatus();
            }
            if (exit==null) throw new IllegalStateException("发布无退出码，状态未确认："+attempt);
            var output=out.value();
            var stages=output.lines().filter(line -> line.startsWith("AGENTSTUDIO_PUBLISH_STAGE=")).map(line -> line.substring("AGENTSTUDIO_PUBLISH_STAGE=".length())).toList();
            var counts=output.lines().filter(line -> line.matches("AGENTSTUDIO_MIGRATIONS_EXECUTED=[0-9]{1,2}")).map(line -> Integer.parseInt(line.substring("AGENTSTUDIO_MIGRATIONS_EXECUTED=".length()))).toList();
            var migrated=!out.truncated() && counts.size()==1 ? counts.getFirst() : null;
            boolean deployed=exit==0 && !out.truncated() && marker(output,"AGENTSTUDIO_DEPLOYED","true") && marker(output,"AGENTSTUDIO_PUBLISH_STAGE","DEPLOYED");
            boolean rollback=!out.truncated() && marker(output,"AGENTSTUDIO_ROLLED_BACK","true") && marker(output,"AGENTSTUDIO_PUBLISH_STAGE","ROLLED_BACK");
            boolean uncertain=out.truncated() || !output.contains("AGENTSTUDIO_PUBLISH_STAGE=") || exit==124 || exit==137;
            var response=new LinkedHashMap<String,Object>(args);
            response.put("task","PUBLISH_RELEASE"); response.put("target",profiles.target(profile)); response.put("attemptPath",attempt);
            response.put("stage",stages.size()==1 && stages.getFirst().matches("[A-Z_]+") ? stages.getFirst() : "UNKNOWN");
            response.put("migrationsExecuted",migrated);
            response.put("successful",deployed); response.put("exitCode",exit); response.put("deployed",deployed); response.put("rolledBack",rollback);
            response.put("manualInterventionRequired",uncertain || marker(output,"AGENTSTUDIO_MANUAL_REQUIRED","true"));
            response.put("databaseMayHaveChanged",uncertain || (migrated != null ? migrated > 0 : marker(output,"AGENTSTUDIO_MIGRATION_STARTED","1")));
            response.put("currentImageId",deployed ? args.get("imageId") : rollback ? args.get("previousImageId") : null);
            response.put("output",output); response.put("outputTruncated",out.truncated()); response.put("durationMs",Duration.ofNanos(System.nanoTime()-started).toMillis());
            if (exit==0 && !deployed) response.put("manualInterventionRequired",true);
            return mapper.writeValueAsString(response);
        });
    }
    private static boolean marker(String output,String name,String value) { return output.lines().filter(line -> line.equals(name+"="+value)).count()==1; }
}
