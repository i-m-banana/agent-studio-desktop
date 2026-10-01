package com.agentstudio.adapter.ssh;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class RemoteDatabaseSchemaResult {
    private static final Map<String, Integer> WIDTHS = Map.of(
            "DATABASE", 3, "TABLE", 5, "COLUMN", 9, "INDEX", 9, "CONSTRAINT", 4,
            "KEY", 8, "REFERENCE", 8, "TRIGGER", 5, "ROUTINE", 3, "END", 2);

    static Map<String, Object> parse(String output, ObjectMapper mapper) throws Exception {
        var rows = new ArrayList<JsonNode>();
        var lines = output.strip().split("\\R");
        for (var line : lines) {
            var row = mapper.readTree(line);
            if (row == null || !row.isArray() || row.size() == 0
                    || row.size() != WIDTHS.getOrDefault(row.path(0).asText(), -1))
                throw new IllegalStateException("数据库结构输出不完整或格式无效，不能用于基线审批");
            for (var field : row) {
                if (field.isContainerNode())
                    throw new IllegalStateException("数据库结构输出包含无效嵌套字段");
            }
            rows.add(row);
        }
        if (rows.size() < 2 || !rows.getFirst().path(0).asText().equals("DATABASE")
                || !rows.getLast().equals(mapper.valueToTree(List.of("END", "schema-metadata-v1")))
                || rows.stream().filter(r -> r.path(0).asText().equals("DATABASE")).count() != 1
                || rows.stream().filter(r -> r.path(0).asText().equals("END")).count() != 1)
            throw new IllegalStateException("数据库结构输出缺少完整边界，不能用于基线审批");
        var canonical = mapper.writeValueAsBytes(rows);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        return Map.of("schemaFormat", "schema-metadata-v1", "schemaSha256", sha,
                "schemaComplete", true, "databaseModified", false,
                "baselineRegistered", false);
    }
}
