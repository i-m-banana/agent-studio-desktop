package com.agentstudio.release;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Pure deterministic planner. No model messages, arbitrary parameters, or execution. */
public final class WorkflowPlan {
    public record Evidence(String phase,String status,JsonNode receipt,Instant at) {}
    public record Next(String phase,String title,String purpose,Map<String,String> arguments,boolean blocked,String reason) {}
    private WorkflowPlan(){}
    static final Map<String,String> TOOLS=Map.ofEntries(Map.entry("CANDIDATE","prepare_release_candidate"),Map.entry("IMAGE","build_release_candidate_image"),Map.entry("BACKUP","prepare_remote_deployment_backup"),Map.entry("PUBLISH","publish_remote_release"));
    public static String tool(String phase){return TOOLS.getOrDefault(phase,"inspect_remote_deployment");}
    private static Next next(String phase,String title,String reason,Map<String,String> args){return new Next(phase,title,reason,args,false,"");}
    private static Next blocked(String reason){return new Next("BLOCKED","先处理异常",reason,Map.of(),true,reason);}
    public static boolean baseline(String output){return output.lines().anyMatch(line->line.strip().matches("\\[\\s*\"?1\"?\\s*,\\s*\"BASELINE\"\\s*,\\s*(1|true)\\s*\\]"));}
    private static boolean failedHistory(String output){return output.lines().anyMatch(line->line.strip().matches(".*,[\\s]*(0|false)[\\s]*\\]"));}
    private static boolean ok(Evidence e){return e!=null&&Set.of("SUCCEEDED","DEPLOYED").contains(e.status())&&e.receipt()!=null;}
    private static String text(Evidence e,String key){return e==null||e.receipt()==null?"":e.receipt().path(key).asText("");}
    private static boolean fresh(Evidence e,Instant now,Instant after){return ok(e)&&!e.at().isAfter(now)&&e.at().isAfter(after)&&Duration.between(e.at(),now).compareTo(Duration.ofMinutes(5))<0;}
    public static Next plan(Map<String,Evidence> evidence,String target,String source,Instant now,boolean recoveryConfirmed) {
        var candidate=evidence.get("CANDIDATE");var image=evidence.get("IMAGE");var publish=evidence.get("PUBLISH");
        if(publish!=null&&publish.status().equals("DEPLOYED")) {
            var status=evidence.get("POST_STATUS");var health=evidence.get("POST_HEALTH");
            if(!fresh(status,now,publish.at())||!text(status,"target").equals(target))return next("POST_STATUS","核对上线后的版本","核对实际运行镜像和应用健康",Map.of("task","RELEASE_STATUS"));
            if(!text(status,"currentImageId").equals(text(image,"imageId"))||!text(status,"appHealth").equals("healthy"))return blocked("上线后镜像或健康与候选不一致；停止后续发版并核查");
            if(!fresh(health,now,publish.at())||!text(health,"target").equals(target))return next("POST_HEALTH","核对网站入口","核对上线后入口健康，不代替人工业务验收",Map.of("task","SITE_HEALTH"));
            return next("ACCEPT","验收网站效果","请打开网站，检查本次需求和原有主要业务；确认后记录验收",Map.of());
        }
        // Recovery takes precedence even if older preparation receipts are missing.
        var after=publish==null?Instant.EPOCH:publish.at();
        if(publish!=null) {
            for(var phase:List.of("RECOVERY_STATUS","RECOVERY_HISTORY","RECOVERY_HEALTH"))if(!fresh(evidence.get(phase),now,after)||!text(evidence.get(phase),"target").equals(target))return next(phase,"核查上次上线后的状态","上次上线未确认；只读取现状，不重放发布",Map.of("task",phase.equals("RECOVERY_STATUS")?"RELEASE_STATUS":phase.equals("RECOVERY_HISTORY")?"DATABASE_BASELINE_STATUS":"SITE_HEALTH"));
            if(!text(evidence.get("RECOVERY_STATUS"),"appHealth").equals("healthy")||!baseline(text(evidence.get("RECOVERY_HISTORY"),"output"))||failedHistory(text(evidence.get("RECOVERY_HISTORY"),"output")))return blocked("恢复核查不符合安全条件，需要人工介入，禁止重新上线");
            if(image!=null&&text(evidence.get("RECOVERY_STATUS"),"currentImageId").equals(text(image,"imageId")))return next("RECONCILE","验收已在线的候选","最新核查显示候选已在线且健康；原上线回执仍未确认。请打开网站人工检查后结束任务，不重复上线、不改写原回执",Map.of());
            if(!recoveryConfirmed)return next("REVIEW","确认异常核查","阅读三项最新只读结果，确认已处理异常；此确认不会复用旧批准",Map.of());
        }
        if(!ok(candidate)) {
            if(candidate!=null&&!Set.of("FAILED","NOT_EXECUTED").contains(candidate.status()))return blocked("候选执行结果未确认，先查看该阶段运行及审计，不能自动重做");
            return next("CANDIDATE","准备发布候选","对已验收源码测试、打包并暂存，不切换生产",Map.of());
        }
        if(!text(candidate,"target").equals(target)||!text(candidate,"sourceSha256").equals(source))return blocked("候选与本任务项目源码或目标不一致，不能继续");
        if(!ok(image)) {
            if(image!=null&&!Set.of("FAILED","NOT_EXECUTED","INCOMPLETE_EVIDENCE").contains(image.status()))return blocked("镜像执行状态未确认，先核对资源与运行，不自动重新构建");
            return next("IMAGE","构建候选镜像","构建同一候选并核对维护入口自检",Map.of("releaseId",text(candidate,"releaseId"),"manifestSha256",text(candidate,"manifestSha256")));
        }
        if(!text(image,"releaseId").equals(text(candidate,"releaseId"))||!text(image,"manifestSha256").equals(text(candidate,"manifestSha256"))||!text(image,"target").equals(target))return blocked("镜像与候选材料身份不一致");
        var backup=evidence.get("BACKUP");
        if(backup!=null&&!Set.of("SUCCEEDED","FAILED","NOT_EXECUTED").contains(backup.status()))return blocked("备份执行结果未确认，先核对本次备份和运行；不会自动创建另一份备份");
        boolean backupFresh=false;
        try { var id=text(backup,"backupId");var created=LocalDateTime.parse(id.substring(0,15),java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC);
            var age=Duration.between(created,now);backupFresh=ok(backup)&&text(backup,"target").equals(target)&&!age.isNegative()&&age.compareTo(Duration.ofMinutes(30))<=0;
        }catch(Exception ignored){}
        if(!backupFresh)return next("BACKUP","准备有效备份","备份超过 30 分钟或缺少合格备份，只更新备份，旧备份保留",Map.of());
        for(var phase:List.of("SCHEMA","HISTORY","STATUS")) {
            var e=evidence.get(phase);
            if(!fresh(e,now,after)||!text(e,"target").equals(target))return next(phase,phase.equals("SCHEMA")?"核查数据库结构":phase.equals("HISTORY")?"核查数据库版本历史":"读取当前线上版本","这项证据缺失、过期或在上次尝试之前；仅更新本项（5 分钟有效）",Map.of("task",phase.equals("SCHEMA")?"DATABASE_SCHEMA":phase.equals("HISTORY")?"DATABASE_BASELINE_STATUS":"RELEASE_STATUS"));
        }
        var schema=evidence.get("SCHEMA");var history=evidence.get("HISTORY");var status=evidence.get("STATUS");
        if(!baseline(text(history,"output"))||failedHistory(text(history,"output")))return blocked("日常发布要求已有成功的版本 1 基线且无失败历史；首次接入请使用独立的基线流程，不自动登记");
        if(!text(status,"appHealth").equals("healthy"))return blocked("当前应用不健康，不能上线");
        if(text(status,"currentImageId").equals(text(image,"imageId")))return blocked("此候选已经在线，无需重复上线");
        var args=Map.of("releaseId",text(candidate,"releaseId"),"manifestSha256",text(candidate,"manifestSha256"),"imageId",text(image,"imageId"),"backupId",text(backup,"backupId"),"backupManifestSha256",text(backup,"manifestSha256"),"schemaSha256",text(schema,"schemaSha256"),"previousImageId",text(status,"currentImageId"),"productionSha256",text(status,"productionSha256"));
        for(var field:args.entrySet())if(!field.getValue().matches(field.getKey().endsWith("ImageId")||field.getKey().equals("imageId")?"sha256:[0-9a-f]{64}":field.getKey().endsWith("Id")?"[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}":"[0-9a-f]{64}"))return blocked("上线材料缺少完整身份："+field.getKey());
        return next("PUBLISH","确认上线","将切换生产应用并可能执行兼容迁移；失败可能保留数据库扩展。需新的逐项审批",args);
    }
}
