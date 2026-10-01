package com.agentstudio.adapter.ssh;

import com.agentstudio.tool.AgentTool;
import com.agentstudio.tool.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.sshd.client.channel.ClientChannelEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class AdoptRemoteDatabaseBaselineTool implements AgentTool {
    static final List<String> FIELDS = List.of("releaseId", "manifestSha256", "imageId", "schemaSha256", "backupId", "backupManifestSha256");
    private static Map<String,Object> schema() {
        var properties = new LinkedHashMap<String,Object>();
        FIELDS.forEach(key -> properties.put(key, Map.of("type", "string", "pattern", key.endsWith("Id") && !key.equals("imageId")
                ? "^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$" : key.equals("imageId") ? "^sha256:[0-9a-f]{64}$" : "^[0-9a-f]{64}$")));
        return Map.of("type", "object", "properties", properties, "required", FIELDS, "additionalProperties", false);
    }
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor("adopt_remote_database_baseline", "登记受审数据库基线",
            "绑定明确候选、镜像、结构摘要与30分钟内完整备份，重新核查后仅登记Flyway版本1；短暂阻止业务表写入，不执行迁移或生产切换。拒绝SQL、路径、版本或任意选项。",
            "SSH", "WRITE", "HIGH", 210, schema());
    private final RemoteDeploymentWorkspace workspace;
    private final RemoteDeploymentService profiles;
    private final ObjectMapper mapper;
    private final Duration timeout;
    @Autowired
    public AdoptRemoteDatabaseBaselineTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles, ObjectMapper mapper) {
        this(workspace, profiles, mapper, Duration.ofSeconds(150));
    }
    AdoptRemoteDatabaseBaselineTool(RemoteDeploymentWorkspace workspace, RemoteDeploymentService profiles, ObjectMapper mapper, Duration timeout) {
        this.workspace=workspace; this.profiles=profiles; this.mapper=mapper; this.timeout=timeout;
    }
    @Override public ToolDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String targetEnvironment() { return "SSH:" + profiles.approvalTarget() + "|BACKUP_ROOT:" + profiles.current().remoteBackupRoot() + "|BASELINE:1,NO_MIGRATE|BACKUP_MAX_AGE:1800s|SHORT_READ_LOCKS"; }
    @Override public String execute(JsonNode arguments) throws Exception {
        var args = validate(arguments);
        var profile = profiles.current(); var started = System.nanoTime();
        return workspace.execute(profile, (session, access, properties) -> {
            var candidate = access.validateCandidate(args.get("releaseId"));
            var manifest = access.readCandidateManifest(candidate);
            if (!RemoteReleaseCandidateStager.sha256(manifest).equals(args.get("manifestSha256"))) throw new SecurityException("候选摘要不匹配");
            var hashes = BuildReleaseCandidateImageTool.parseManifest(manifest, args.get("releaseId"));
            var backup = access.validateBackup(args.get("backupId"));
            var backupManifest = access.readCandidateManifest(backup);
            if (!RemoteReleaseCandidateStager.sha256(backupManifest).equals(args.get("backupManifestSha256"))) throw new SecurityException("备份摘要不匹配");
            var backupFields = new Properties(); backupFields.load(new java.io.StringReader(new String(backupManifest, java.nio.charset.StandardCharsets.UTF_8)));
            if (!"1".equals(backupFields.getProperty("backupFormat")) || !args.get("backupId").equals(backupFields.getProperty("backupId"))
                    || !profile.remoteDeployRoot().equals(backupFields.getProperty("deploymentRoot")) || !profile.composeProject().equals(backupFields.getProperty("composeProject")))
                throw new SecurityException("备份不属于当前目标");
            var age = Duration.between(Instant.parse(backupFields.getProperty("createdAt")), Instant.now());
            if (age.isNegative() || age.compareTo(Duration.ofMinutes(30)) > 0) throw new SecurityException("需要30分钟内新备份");
            var schemaResult = run(session, new RemoteDatabaseSchemaCommands().command(profile), properties.connectTimeout(), Duration.ofSeconds(30), 48_000);
            if (schemaResult.exit != 0 || schemaResult.truncated) throw new SecurityException("结构重查失败或不完整");
            if (!args.get("schemaSha256").equals(RemoteDatabaseSchemaResult.parse(schemaResult.output, mapper).get("schemaSha256")))
                throw new SecurityException("结构已变化，拒绝基线登记");
            var id = UUID.randomUUID().toString().replace("-", "");
            var attempt = access.createBaselineAttempt(candidate, id);
            var container = "agentstudio-baseline-" + id;
            var result = run(session, new DatabaseBaselineCommands().command(profile, candidate, attempt, container, args, hashes), properties.connectTimeout(), timeout, 16_000);
            if (result.exit == 42 || result.exit == 43 || result.exit == 44)
                throw new SecurityException("远程材料、备份时效、镜像身份或容器独占校验拒绝，未启动基线维护；exitCode=" + result.exit);
            boolean confirmed = result.exit == 0 && !result.truncated && result.output.lines().filter(line -> line.equals("AGENTSTUDIO_BASELINE_REGISTERED=" + args.get("schemaSha256"))).count() == 1;
            if (result.exit == 0 && !confirmed) throw new IllegalStateException("基线成功回执未确认；须只读检查历史，不能盲目重试");
            var response = new LinkedHashMap<String,Object>(args);
            response.put("target", profiles.target(profile)); response.put("attemptPath", attempt);
            response.put("successful", confirmed); response.put("exitCode", result.exit);
            response.put("baselineRegistered", confirmed ? Boolean.TRUE : null); response.put("baselineVersion", "1");
            response.put("databaseHistoryMayHaveChanged", !confirmed); response.put("businessDataWrittenByTool", false);
            response.put("servicesRestarted", false); response.put("output", result.output); response.put("outputTruncated", result.truncated);
            response.put("durationMs", Duration.ofNanos(System.nanoTime()-started).toMillis());
            if (result.exit == 124 || result.exit == 137) throw new IllegalStateException("维护超时或资源中断，登记状态未确认；须只读检查历史");
            return mapper.writeValueAsString(response);
        });
    }
    static Map<String,String> validate(JsonNode arguments) {
        if (!arguments.isObject() || arguments.size()!=FIELDS.size()) throw new IllegalArgumentException("基线登记只接受六项绑定身份，不接受SQL、路径或选项");
        var result = new LinkedHashMap<String,String>();
        for (var key : FIELDS) {
            var value=arguments.path(key);
            var pattern = key.equals("imageId") ? "sha256:[0-9a-f]{64}" : key.endsWith("Id") ? "[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}" : "[0-9a-f]{64}";
            if (!value.isTextual() || !value.asText().matches(pattern)) throw new IllegalArgumentException("绑定字段无效："+key);
            result.put(key,value.asText());
        }
        return result;
    }
    private record Result(int exit, String output, boolean truncated) {}
    private Result run(org.apache.sshd.client.session.ClientSession session, String command, Duration connect, Duration budget, int limit) throws Exception {
        var output = new BoundedSshOutputStream(limit);
        try (var channel = session.createExecChannel(command)) {
            channel.setOut(output); channel.setRedirectErrorStream(true); channel.open().verify(connect);
            var end=System.nanoTime()+budget.toNanos();
            while (!channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),250).contains(ClientChannelEvent.CLOSED)) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime()>=end) {
                    channel.close(true); throw new IllegalStateException("维护中断/超时，通道已关闭；数据库历史状态未确认");
                }
            }
            if(channel.getExitStatus()==null) throw new IllegalStateException("维护通道无退出码，状态未确认");
            return new Result(channel.getExitStatus(),output.value(),output.truncated());
        }
    }
}
