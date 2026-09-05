package com.agentstudio.adapter.mcp;

import java.util.List;
import java.util.Base64;

import com.agentstudio.knowledge.KnowledgeDocument;
import com.agentstudio.knowledge.KnowledgeService;
import com.agentstudio.system.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {
    private final McpService service;
    private final KnowledgeService knowledgeService;

    public McpController(McpService service, KnowledgeService knowledgeService) {
        this.service = service;
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    List<McpServer> list() { return service.listServers(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    McpServer create(@Valid @RequestBody McpServerRequest request) { return service.create(request); }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    McpServer importConfiguration(@Valid @RequestBody McpServerRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    McpServer update(@PathVariable String id, @Valid @RequestBody McpServerRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/enabled")
    McpServer setEnabled(@PathVariable String id, @RequestBody McpEnabledRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { service.delete(id); }

    @PostMapping("/{id}/sync")
    McpSyncResult sync(@PathVariable String id) { return service.sync(id); }

    @GetMapping("/{id}/sync-events")
    List<McpSyncEvent> syncEvents(@PathVariable String id) { return service.listSyncEvents(id); }

    @GetMapping("/{id}/configuration")
    McpServerRequest configuration(@PathVariable String id) { return service.configuration(id); }

    @GetMapping("/{id}/resources")
    List<McpCatalogResource> resources(@PathVariable String id) { return service.listResources(id); }

    @GetMapping("/{id}/resources/{publicId}")
    List<McpResourceContent> readResource(@PathVariable String id, @PathVariable String publicId) {
        return service.readResource(id, publicId);
    }

    @PostMapping("/{id}/resources/{publicId}/import")
    KnowledgeDocument importResource(@PathVariable String id, @PathVariable String publicId,
                                     @Valid @RequestBody McpResourceImportRequest request) {
        var resource = service.requireResource(id, publicId);
        var contents = service.readResource(id, publicId);
        if (contents.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "MCP Resource 没有可导入内容");
        var first = contents.getFirst();
        byte[] bytes;
        try {
            var textOnly = contents.stream().allMatch(item -> item.text() != null);
            if (textOnly) {
                var joined = contents.stream().map(McpResourceContent::text)
                        .collect(java.util.stream.Collectors.joining("\n\n"));
                bytes = joined.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (contents.size() == 1 && first.blob() != null) {
                bytes = Base64.getDecoder().decode(first.blob());
            } else {
                throw new IllegalArgumentException("混合或多段二进制 Resource 不能直接导入");
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "MCP Resource 内容格式无效：" + exception.getMessage());
        }
        var mediaType = first.mimeType() == null ? resource.mimeType() : first.mimeType();
        var fileName = request.fileName() == null || request.fileName().isBlank()
                ? resource.displayName() + extension(mediaType) : request.fileName();
        return knowledgeService.importContent(request.knowledgeBaseId(), fileName, mediaType, bytes);
    }

    @GetMapping("/{id}/prompts")
    List<McpCatalogPrompt> prompts(@PathVariable String id) { return service.listPrompts(id); }

    @PostMapping("/{id}/prompts/{publicName}")
    McpPromptResult getPrompt(@PathVariable String id, @PathVariable String publicName,
                              @RequestBody McpPromptGetRequest request) {
        return service.getPrompt(id, publicName, request.arguments());
    }

    @PutMapping("/tools/{publicName}/policy")
    McpCatalogTool updateToolPolicy(@PathVariable String publicName,
                                    @RequestBody McpToolPolicyRequest request) {
        return service.updateToolPolicy(publicName, request);
    }

    private String extension(String mimeType) {
        if (mimeType == null) return ".txt";
        if (mimeType.contains("markdown")) return ".md";
        if (mimeType.contains("json")) return ".json";
        if (mimeType.contains("pdf")) return ".pdf";
        return mimeType.startsWith("text/") ? ".txt" : ".bin";
    }
}
