package com.agentstudio.release;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WorkflowEvidenceService {
    private final WorkflowStore store;private final ReleaseTaskService tasks;
    public WorkflowEvidenceService(WorkflowStore store,ReleaseTaskService tasks){this.store=store;this.tasks=tasks;}
    public Map<String,WorkflowPlan.Evidence> collect(String flow,String excludedCall){
        var result=new LinkedHashMap<String,WorkflowPlan.Evidence>();
        for(var member:store.members(flow))if(!member.callId().equals(excludedCall)) {
            var task=tasks.forCall(member.runId(),member.callId());
            if(task.isPresent()){var t=task.get();result.put(member.phase(),new WorkflowPlan.Evidence(member.phase(),t.status(),t.receipt(),t.observedAt()));}
            else result.put(member.phase(),new WorkflowPlan.Evidence(member.phase(),"UNKNOWN",null,member.createdAt()));
        }
        return result;
    }
}
