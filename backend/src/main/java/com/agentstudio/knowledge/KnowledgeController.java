package com.agentstudio.knowledge;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping
    List<KnowledgeBase> list() {
        return service.listBases();
    }

    @PostMapping
    ResponseEntity<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        var created = service.createBase(request);
        return ResponseEntity.created(URI.create("/api/knowledge-bases/" + created.id())).body(created);
    }

    @GetMapping("/{id}/documents")
    List<KnowledgeDocument> documents(@PathVariable String id) {
        return service.documents(id);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<KnowledgeDocument> upload(@PathVariable String id,
                                             @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(id, file));
    }

    @GetMapping("/{id}/documents/{documentId}/text")
    KnowledgeService.TextPreview text(@PathVariable String id, @PathVariable String documentId,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "8000") int limit) {
        return service.preview(id, documentId, offset, limit);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDocument(@PathVariable String id, @PathVariable String documentId) {
        service.deleteDocument(id, documentId);
    }

    @PostMapping("/{id}/reindex")
    ReindexResult reindex(@PathVariable String id) {
        return service.reindex(id);
    }
}
