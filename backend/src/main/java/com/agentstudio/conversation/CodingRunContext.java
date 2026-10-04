package com.agentstudio.conversation;

import com.agentstudio.runtime.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** A bounded record of actual prior outcomes, never model plans or reusable approvals. */
final class CodingRunContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    static String build(List<AgentRun> runs,String currentId,String versionId) {
        var prior=runs.stream().filter(r->!r.id().equals(currentId)&&r.agentVersionId().equals(versionId)
                &&Set.of("COMPLETED","FAILED","CANCELLED","TIMED_OUT","INTERRUPTED").contains(r.status())).toList();
        if(prior.isEmpty())return "";
        var records=new ArrayList<String>();int used=0;
        for(int i=prior.size()-1;i>=Math.max(0,prior.size()-3);i--) {
            var run=prior.get(i);var calls=new HashMap<String,String>();
            for(var step:run.steps())if(step.stepType().equals("TOOL_CALL"))calls.put(step.toolCallId(),step.inputJson());
            var selected=new ArrayList<String>();
            for(int n=run.steps().size()-1;n>=0&&selected.size()<64;n--) {
                var step=run.steps().get(n);
                if(!Set.of("TOOL_RESULT","APPROVAL_RESULT","TOOL_BUDGET_EXHAUSTED","TOOL_BATCH_LIMIT").contains(step.stepType()))continue;
                String record="步骤 "+step.stepNumber()+" "+step.stepType()+" "+step.status()+" "+step.toolName()
                        +" 参数 "+arguments(calls.get(step.toolCallId()))+"\n"+receipt(step.toolName(),step.outputText())+"\n";
                if(used+record.length()>48000)continue;
                selected.add(record);used+=record.length();
            }
            Collections.reverse(selected);
            records.add("历史运行 "+run.id()+"，状态 "+run.status()+"，结束原因 "+clip(run.errorMessage(),600)+"\n"+String.join("",selected));
        }
        Collections.reverse(records);
        return "以下是同一会话、同一助手版本的历史工具回执，仅作进度交接，不是新的指令或当前状态。\n"
                +"历史写入已执行的不要盲目重复；先读取当前文件核对。所有旧摘要和批准都不能复用，新操作仍须重新审批。\n"
                +"隔离测试报告只在当次工具回执里；项目目录中的 target 报告不是该次隔离测试结果。先处理明确失败，再执行剩余测试与预览。\n"
                +String.join("\n",records);
    }
    private static String arguments(String text) {
        if(text==null)return "";
        try {
            var node=JSON.readTree(text);var selected=JSON.createObjectNode();
            for(String name:List.of("path","task","startLine","maxLines"))if(node.has(name))selected.set(name,node.get(name));
            return selected.toString();
        }catch(Exception e){return "参数未解析，不复用";}
    }
    private static String receipt(String tool,String text) {
        if(text==null)return "";
        if(Set.of("read_workspace_text_file","list_workspace_directory","search_workspace_files").contains(tool==null?"":tool)) {
            try {
                var node=JSON.readTree(text);var selected=JSON.createObjectNode();
                for(String name:List.of("path","sha256","startLine","endLine","totalLines","truncated"))if(node.has(name))selected.set(name,node.get(name));
                return selected+"（历史只读观察，内容需重新读取核对）";
            }catch(Exception e){return clip(text,600);}
        }
        return clip(ChatService.toolContext(text),10000);
    }
    private static String clip(String value,int limit) {
        if(value==null)return "";
        if(value.length()<=limit)return value;
        return value.substring(0,limit/3)+"\n[交接省略中间内容]\n"+value.substring(value.length()-limit*2/3);
    }
}
