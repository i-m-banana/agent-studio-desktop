package com.agentstudio.conversation;

import com.agentstudio.runtime.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Distinguish completed receipts and tests performed before later writes. */
public final class InterruptedCodingProgress {
    public static String summarize(AgentRun run,String reason) {
        var json=new ObjectMapper();var paths=new LinkedHashSet<String>();int latestWrite=-1,latestTest=-1;Boolean testPassed=null;
        for(var step:run.steps())if(step.stepType().equals("TOOL_RESULT")&&step.outputText()!=null) {
            try {
                var receipt=json.readTree(step.outputText());
                if(Set.of("apply_workspace_text_patch","create_workspace_text_file").contains(step.toolName())
                        &&(receipt.path("updated").asBoolean()||receipt.path("created").asBoolean())) {
                    latestWrite=step.stepNumber();paths.add(receipt.path("path").asText());
                }
                if("run_workspace_verification".equals(step.toolName())&&"MAVEN_TEST".equals(receipt.path("task").asText())) {
                    latestTest=step.stepNumber();testPassed=receipt.path("successful").asBoolean()&&receipt.path("exitCode").asInt(-1)==0;
                }
            }catch(Exception ignored){/* Plain failure messages are not successful receipts. */}
        }
        var message=new StringBuilder(reason).append("\n\n本次实际进度：\n");
        if(paths.isEmpty())message.append("没有取得成功文件写入回执。\n");
        else {
            message.append("成功写入 ").append(paths.size()).append(" 个文件：\n");
            paths.stream().limit(12).forEach(path->message.append("- ").append(path).append('\n'));
            if(paths.size()>12)message.append("其余文件见运行记录。\n");
        }
        if(testPassed==null)message.append("本次没有 Java 测试结果。\n");
        else if(latestTest<latestWrite)message.append("最近一次 Java 测试").append(testPassed?"通过":"失败")
                .append("，但随后还有文件写入；当前改动尚未完成重新验证。\n");
        else message.append("最近一次 Java 测试").append(testPassed?"通过":"失败").append("；其他验证以各自回执为准。\n");
        var unresolved=run.steps().stream().filter(step->"TOOL_CALL".equals(step.stepType()))
                .filter(call->run.steps().stream().noneMatch(result->"TOOL_RESULT".equals(result.stepType())
                        && Objects.equals(call.toolCallId(),result.toolCallId())))
                .map(step->step.toolName()).filter(Objects::nonNull).distinct().toList();
        if(!unresolved.isEmpty())message.append("以下工具没有取得执行结果，不能算作完成或通过：")
                .append(String.join("、",unresolved)).append("。继续前须核对状态；不会自动重跑。\n");
        return message.append("在原会话继续已授权批次的剩余工作：先核对当前文件和迁移版本，再完成必要修改与测试。所有新写入和验证仍须逐项审批。").toString();
    }
}
