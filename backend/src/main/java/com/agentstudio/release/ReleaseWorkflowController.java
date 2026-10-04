package com.agentstudio.release;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController @RequestMapping("/api/release-workflows")
public class ReleaseWorkflowController {
    private final ReleaseWorkflowService workflows;
    public ReleaseWorkflowController(ReleaseWorkflowService workflows){this.workflows=workflows;}
    @GetMapping public List<ReleaseWorkflowService.View> list(@RequestParam String projectId){return workflows.list(projectId);}
    @GetMapping("/{id}") public ReleaseWorkflowService.View get(@PathVariable String id){return workflows.get(id);}
    @PostMapping public ReleaseWorkflowService.View create(@RequestBody ReleaseWorkflowService.Create request)throws Exception{return workflows.create(request);}
    @PostMapping(value="/{id}/advance",produces="text/event-stream") public SseEmitter advance(@PathVariable String id,@RequestBody ReleaseWorkflowService.Advance request)throws Exception{return workflows.advance(id,request);}
    public record Revision(int revision){}
    @PostMapping("/{id}/review") public ReleaseWorkflowService.View review(@PathVariable String id,@RequestBody Revision request)throws Exception{return workflows.review(id,request.revision());}
    @PostMapping("/{id}/accept") public ReleaseWorkflowService.View accept(@PathVariable String id,@RequestBody Revision request){return workflows.accept(id,request.revision());}
    @PostMapping("/{id}/close") public ReleaseWorkflowService.View close(@PathVariable String id,@RequestBody Revision request){return workflows.close(id,request.revision());}
}
