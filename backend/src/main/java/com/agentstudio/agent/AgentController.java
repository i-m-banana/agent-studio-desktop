package com.agentstudio.agent;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    List<AgentDefinition> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    @PostMapping("/{id}/archive")
    AgentDefinition archiveAgent(@PathVariable String id) { return service.archive(id,true); }

    @PostMapping("/{id}/restore")
    AgentDefinition restoreAgent(@PathVariable String id) { return service.archive(id,false); }

    @PostMapping
    ResponseEntity<AgentDefinition> create(@Valid @RequestBody AgentDefinitionRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/agents/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AgentDefinition update(@PathVariable String id, @Valid @RequestBody AgentDefinitionRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/publish")
    AgentVersion publish(@PathVariable String id) {
        return service.publish(id);
    }

    @GetMapping("/{id}/versions")
    List<AgentVersion> versions(@PathVariable String id,
                                @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.versions(id, includeArchived);
    }

    @PostMapping("/{id}/versions/{versionId}/archive")
    AgentVersion archive(@PathVariable String id, @PathVariable String versionId) {
        return service.archiveVersion(id, versionId);
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    AgentVersion restore(@PathVariable String id, @PathVariable String versionId) {
        return service.restoreVersion(id, versionId);
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    ResponseEntity<Void> deleteVersion(@PathVariable String id, @PathVariable String versionId) {
        service.deleteVersion(id, versionId);
        return ResponseEntity.noContent().build();
    }
}
