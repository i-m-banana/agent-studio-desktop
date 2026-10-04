package com.agentstudio.project;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/local-projects/{projectId}/runtime")
public class ProjectRuntimeController {
    private final ProjectRuntimeService runtime;
    public ProjectRuntimeController(ProjectRuntimeService runtime){this.runtime=runtime;}
    @GetMapping public ProjectRuntimeService.Configuration get(@PathVariable String projectId){return runtime.get(projectId);}
    @PutMapping public ProjectRuntimeService.Configuration save(@PathVariable String projectId,@RequestBody ProjectRuntimeService.Request request)throws Exception{try{return runtime.save(projectId,request);}catch(IllegalStateException e){throw new com.agentstudio.system.ApiException(org.springframework.http.HttpStatus.CONFLICT,e.getMessage());}}
}
