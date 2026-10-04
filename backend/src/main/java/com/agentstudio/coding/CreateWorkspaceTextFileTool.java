package com.agentstudio.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.agentstudio.tool.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;

@Component
public class CreateWorkspaceTextFileTool implements AgentTool {
    private static final Set<String> EXTENSIONS = Set.of("java","kt","js","jsx","ts","tsx","css","html","vue","py","md","txt");
    private final CodingWorkspace workspace;
    private final ObjectMapper mapper;
    public CreateWorkspaceTextFileTool(CodingWorkspace workspace, ObjectMapper mapper) { this.workspace=workspace; this.mapper=mapper; }
    @Override public ToolDescriptor descriptor() {
        return new ToolDescriptor("create_workspace_text_file", "新建受审源码或测试文件",
                "审批后在授权源码/测试目录创建单个 UTF-8 文件。支持源码、文档、src/main/resources/db/migration/mysql 或 h2 下的 V数字__名称.sql，以及 src/test/resources 下的 SQL 夹具。不覆盖，不隐式建目录，不执行 SQL。失败不得改用探测文件或其他扩展名绕过限制。", "BUILTIN","WRITE","HIGH",30,
                Map.of("type","object","properties", Map.of("path",Map.of("type","string"),"content",Map.of("type","string","maxLength",64000)),
                       "required",List.of("path","content"),"additionalProperties",false));
    }
    @Override public String execute(JsonNode args) throws Exception {
        if (!args.isObject() || args.size()!=2 || !args.path("path").isTextual() || !args.path("content").isTextual())
            throw new IllegalArgumentException("只接受 path 和 content 字符串");
        var relative = validatePath(args.path("path").asText());
        var name = relative.getFileName().toString();
        var root=workspace.root(); var target=root.resolve(relative);
        workspace.requireWritable(target);
        var parent=workspace.requireDirectory(relative.getParent()==null ? "." : relative.getParent().toString());
        var bytes=args.path("content").asText().getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>65536 || args.path("content").asText().indexOf('\0')>=0)
            throw new IllegalArgumentException("内容须为 1 到 65536 字节的 UTF-8 文本");
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目标已存在，不能覆盖");
        GuardedTextFiles.create(parent,name,bytes,mapper);
        return mapper.writeValueAsString(Map.of("created",true,"path",relative.toString().replace('\\','/'),"sha256",WorkspaceTextFiles.sha256(bytes),"bytes",bytes.length));
    }
    public static Path validatePath(String requestedPath) {
        var relative=CodingWorkspace.safeRelative(requestedPath);
        var name=relative.getFileName().toString();
        var extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(name.startsWith(".")||name.equalsIgnoreCase("package.json"))
            throw new IllegalArgumentException("不能创建隐藏文件或部署配置");
        if(extension.equals("sql")) {
            var path=relative.toString().replace('\\','/');
            if(!path.matches("src/main/resources/db/migration/(mysql|h2)/V[1-9][0-9]*__[A-Za-z0-9_]+\\.sql")
                    &&!path.matches("src/test/resources/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.sql"))
                throw new IllegalArgumentException("SQL 新建仅支持标准版本化 MySQL/H2 迁移路径或 src/test/resources SQL 夹具；不允许其他目录或文件名");
        }else if(!EXTENSIONS.contains(extension))
            throw new IllegalArgumentException("文件类型不受支持；允许源码、普通文档和指定路径的 SQL，不允许脚本或部署配置");
        return relative;
    }
}
