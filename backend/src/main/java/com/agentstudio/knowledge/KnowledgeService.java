package com.agentstudio.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.agentstudio.system.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {

    private final KnowledgeMetadataRepository metadata;
    private final ObjectProvider<VectorChunkRepository> vectors;
    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final Path dataRoot;

    public KnowledgeService(KnowledgeMetadataRepository metadata,
                            ObjectProvider<VectorChunkRepository> vectors,
                            DocumentTextExtractor extractor, TextChunker chunker,
                            @Value("${agent-studio.data-dir:../data}") String dataDir) {
        this.metadata = metadata;
        this.vectors = vectors;
        this.extractor = extractor;
        this.chunker = chunker;
        this.dataRoot = Path.of(dataDir).toAbsolutePath().normalize();
    }

    public List<KnowledgeBase> listBases() {
        return metadata.findBases();
    }

    public KnowledgeBase getBase(String id) {
        return metadata.findBase(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "知识库不存在"));
    }

    public KnowledgeBase createBase(KnowledgeBaseRequest request) {
        var now = Instant.now();
        var knowledgeBase = new KnowledgeBase(UUID.randomUUID().toString(), request.name().trim(),
                request.description() == null ? "" : request.description().trim(), now, now);
        metadata.insertBase(knowledgeBase);
        return knowledgeBase;
    }

    public List<KnowledgeDocument> documents(String knowledgeBaseId) {
        getBase(knowledgeBaseId);
        return metadata.findDocuments(knowledgeBaseId);
    }

    public KnowledgeDocument upload(String knowledgeBaseId, MultipartFile file) {
        getBase(knowledgeBaseId);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "上传文件为空");
        var originalName = safeFileName(file.getOriginalFilename());
        try {
            var bytes = file.getBytes();
            var id = UUID.randomUUID().toString();
            var directory = checkedPath(dataRoot.resolve("knowledge").resolve(knowledgeBaseId));
            Files.createDirectories(directory);
            var storedFile = checkedPath(directory.resolve(id + extension(originalName)));
            Files.write(storedFile, bytes);
            var now = Instant.now();
            var document = new KnowledgeDocument(id, knowledgeBaseId, originalName,
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    bytes.length, sha256(bytes), storedFile.toString(), "PROCESSING", 0, null, now, now);
            metadata.insertDocument(document);
            return ingest(document, bytes);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存上传文件失败");
        }
    }

    public void deleteDocument(String knowledgeBaseId, String documentId) {
        getBase(knowledgeBaseId);
        var document = metadata.findDocument(documentId)
                .filter(item -> item.knowledgeBaseId().equals(knowledgeBaseId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "文档不存在"));
        vectorStore().deleteDocument(documentId);
        metadata.deleteDocument(documentId);
        try {
            Files.deleteIfExists(checkedPath(Path.of(document.storedPath())));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "文档记录已删除，但本地文件清理失败");
        }
    }

    public ReindexResult reindex(String knowledgeBaseId) {
        getBase(knowledgeBaseId);
        var indexed = new java.util.ArrayList<KnowledgeDocument>();
        for (var document : metadata.findDocuments(knowledgeBaseId)) {
            try {
                var bytes = Files.readAllBytes(checkedPath(Path.of(document.storedPath())));
                metadata.updateDocumentStatus(document.id(), "PROCESSING", 0, null);
                indexed.add(ingest(document, bytes));
            } catch (IOException exception) {
                metadata.updateDocumentStatus(document.id(), "FAILED", 0, "读取本地文件失败");
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "重建文档失败：" + document.fileName() + " 的本地文件不可读");
            }
        }
        return new ReindexResult(knowledgeBaseId, vectorStore().embeddingDescription(), indexed.size(),
                indexed.stream().mapToInt(KnowledgeDocument::chunkCount).sum(), List.copyOf(indexed));
    }

    private KnowledgeDocument ingest(KnowledgeDocument document, byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes)) {
            var text = extractor.extract(input, document.fileName());
            if (text.isBlank()) throw new IllegalArgumentException("未能从文件中提取文本");
            var contentChunks = chunker.chunk(text);
            var chunks = IntStream.range(0, contentChunks.size())
                    .mapToObj(index -> new KnowledgeChunk(UUID.randomUUID().toString(),
                            document.knowledgeBaseId(), document.id(), index,
                            contentChunks.get(index), document.fileName()))
                    .toList();
            vectorStore().replaceDocument(document.id(), chunks);
            metadata.updateDocumentStatus(document.id(), "READY", chunks.size(), null);
            return metadata.findDocument(document.id()).orElseThrow();
        } catch (Exception exception) {
            vectors.ifAvailable(store -> store.deleteCurrentIndex(document.id()));
            var message = safeError(exception);
            metadata.updateDocumentStatus(document.id(), "FAILED", 0, message);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文档解析失败：" + message);
        }
    }

    private VectorChunkRepository vectorStore() {
        var store = vectors.getIfAvailable();
        if (store == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "向量数据库未启用");
        return store;
    }

    private Path checkedPath(Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件路径越界");
        }
        return normalized;
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) return "document";
        return Path.of(original).getFileName().toString().replaceAll("[\\p{Cntrl}]", "_");
    }

    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? ".bin" : name.substring(index).replaceAll("[^A-Za-z0-9.]", "");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String safeError(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) return "未知解析错误";
        return message.length() <= 900 ? message : message.substring(0, 900);
    }
}
