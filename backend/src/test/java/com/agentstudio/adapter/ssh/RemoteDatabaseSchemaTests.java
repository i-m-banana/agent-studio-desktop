package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RemoteDatabaseSchemaTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fixedSqlReadsOnlyMetadataAndHasNoDefaultsOrBusinessData() {
        assertThat(RemoteDatabaseSchemaCommands.SQL).contains("START TRANSACTION READ ONLY", "COMMIT",
                "information_schema.COLUMNS", "information_schema.STATISTICS", "information_schema.KEY_COLUMN_USAGE",
                "information_schema.REFERENTIAL_CONSTRAINTS", "information_schema.TRIGGERS", "information_schema.ROUTINES");
        assertThat(RemoteDatabaseSchemaCommands.SQL.toUpperCase()).doesNotContain("COLUMN_DEFAULT", "TABLE_ROWS",
                "INSERT ", "UPDATE ", "DELETE ", "ALTER ", "CREATE ", "DROP ", "BASELINE", "FROM USERS", "FROM FLYWAY");
    }

    @Test void fingerprintIsCanonicalButChangesWithSchemaAndRequiresCompleteOutput() throws Exception {
        var output = "[\"DATABASE\",\"fixture\",\"8.0\"]\n[\"TABLE\",\"中文\",\"BASE TABLE\",\"InnoDB\",null]\n[\"END\",\"schema-metadata-v1\"]\n";
        var result = RemoteDatabaseSchemaResult.parse(output, mapper);
        assertThat(result.get("schemaSha256")).isEqualTo(RemoteDatabaseSchemaResult.parse(output.replace(",", ", ").replace("\n", "\r\n"), mapper).get("schemaSha256"));
        assertThat(result.get("schemaSha256")).isNotEqualTo(RemoteDatabaseSchemaResult.parse(output.replace("中文", "users"), mapper).get("schemaSha256"));
        assertThatThrownBy(() -> RemoteDatabaseSchemaResult.parse(output + "unexpected stderr", mapper)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> RemoteDatabaseSchemaResult.parse("[\"DATABASE\",\"fixture\",\"8.0\"]\n[\"END\",\"wrong\"]", mapper)).hasMessageContaining("完整边界");
        assertThatThrownBy(() -> RemoteDatabaseSchemaResult.parse(output.replace("\"中文\"", "[]"), mapper)).hasMessageContaining("嵌套");
    }

    @Test void boundedOutputPreservesUtf8AcrossBufferBoundary() throws Exception {
        var output = new BoundedSshOutputStream(10);
        output.write("1234中文".getBytes(StandardCharsets.UTF_8));
        assertThat(output.truncated()).isFalse();
        assertThat(output.value()).isEqualTo("1234中文");
    }

    @Test void acceptsActualMySqlIndexRecordWidth() throws Exception {
        var output = "[\"DATABASE\",\"fixture\",\"8.0\"]\n[\"INDEX\",\"users\",\"PRIMARY\",0,1,\"id\",\"A\",null,\"BTREE\"]\n[\"END\",\"schema-metadata-v1\"]\n";
        assertThat(RemoteDatabaseSchemaResult.parse(output, mapper).get("schemaComplete")).isEqualTo(true);
    }
}
