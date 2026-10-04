package com.agentstudio.coding;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
/** Inspects only the two configured immutable local images. No builds, pulls or cache cleanup. */
@RestController @RequestMapping("/api/local-runtime-environment")
public class LocalRuntimeEnvironmentController{
    private final PreviewDocker docker;private final String mysql;
    public LocalRuntimeEnvironmentController(PreviewDocker docker,@Value("${agent-studio.projects.mysql-image:}")String mysql){this.docker=docker;this.mysql=mysql;}
    @GetMapping public Map<String,Object> get(){
        var images=new ArrayList<Map<String,Object>>();
        for(var type:List.of("验证镜像","MySQL 合成数据库镜像")){
            String id=type.equals("验证镜像")?docker.image:mysql;var row=new LinkedHashMap<String,Object>();row.put("type",type);row.put("configuredId",id);row.put("retention","固定可信环境，保留；不在界面删除缓存。外部引用数量未知");
            try{docker.requireConfigured();if(!id.matches("sha256:[0-9a-f]{64}"))throw new IllegalStateException("未配置固定摘要");var value=docker.run(List.of("image","inspect","--format","{{.Id}}|{{.Size}}",id),10).split("\\|");if(value.length!=2)throw new IllegalStateException("身份未确认");row.put("localId",value[0]);row.put("bytes",Long.parseLong(value[1]));row.put("available",true);}catch(Exception e){row.put("available",false);row.put("message","本地镜像尚未确认，不会自动下载");}images.add(row);
        }
        return Map.of("images",images,"limits",Map.of("sourceSnapshotMiB",128,"sourceFileMiB",8,"mysqlWorkMiB",1536,"mysqlDataMiB",512,"previewInstances",1),"observedAt",java.time.Instant.now().toString());
    }
}
