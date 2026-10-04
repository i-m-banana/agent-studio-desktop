package com.agentstudio.coding;

import com.agentstudio.tool.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StartProjectPreviewTool implements AgentTool {
    private final LocalPreviewService previews; private final ObjectMapper mapper;
    public StartProjectPreviewTool(LocalPreviewService previews,ObjectMapper mapper){this.previews=previews;this.mapper=mapper;}
    @Override public ToolDescriptor descriptor(){return new ToolDescriptor("start_project_preview","启动受审网站隔离预览",
        "审批后测试打包当前网站源码快照，使用断网容器和H2合成数据提供本机可交互预览，30分钟到期。不接入生产/MySQL/真实uploads，不替代MySQL集成测试。",
        "BUILTIN","EXECUTE","HIGH",480,Map.of("type","object","properties",Map.of(),"additionalProperties",false));}
    @Override public String targetEnvironment(){return "LOCAL_PREVIEW|NETWORK:none|H2_SYNTHETIC|NO_PRODUCTION|TTL:1800s";}
    @Override public String execute(JsonNode arguments)throws Exception{
        if(!arguments.isObject()||!arguments.isEmpty())throw new IllegalArgumentException("预览不接受命令、路径、端口或数据库参数");
        var p=previews.start();
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("receiptVersion",1);receipt.put("task","START_PROJECT_PREVIEW");
        receipt.put("executionType","SERVICE_LIFECYCLE");receipt.put("successful","READY".equals(p.state()));
        receipt.put("previewId",p.id());receipt.put("projectId",p.projectId());receipt.put("state",p.state());
        receipt.put("url",p.url());receipt.put("sourceSha256",p.sourceSha256());receipt.put("expiresAt",p.expiresAt());
        receipt.put("database","H2_SYNTHETIC");receipt.put("mysqlIntegrationTested",false);
        receipt.put("productionModified",false);receipt.put("outputTruncated",false);
        return mapper.writeValueAsString(receipt);
    }
}
