package com.agentstudio.adapter.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Explicit integration suite: mvn -Dtest=RemoteDatabaseSchemaMySqlIT test. Requires local Docker. */
class RemoteDatabaseSchemaMySqlIT {
    @Test void realMySqlMetadataIsReadOnlyCompleteAndDetectsDdlNotRows() throws Exception {
        var name = "agentstudio-schema-fixture-" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        try {
            run(List.of("docker", "run", "--detach", "--rm", "--name", name,
                    "--env", "MYSQL_ROOT_PASSWORD=fixture-only", "--env", "MYSQL_DATABASE=website_schema_fixture",
                    "mysql:8.0"), "", Duration.ofSeconds(90));
            created = true;
            var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (true) {
                try { query(name, "SELECT 1;"); break; }
                catch (AssertionError | IllegalStateException unavailable) {
                    if (System.nanoTime() > deadline) throw unavailable;
                    Thread.sleep(500);
                }
            }
            query(name, """
                    CREATE TABLE parents (id BIGINT PRIMARY KEY, secret VARCHAR(100) DEFAULT 'never-return-default');
                    CREATE TABLE children (id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL,
                      CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE);
                    INSERT INTO parents VALUES (1, 'never-return-user-data');
                    INSERT INTO children VALUES (2, 1);
                    """);
            var mapper = new ObjectMapper();
            var first = query(name, RemoteDatabaseSchemaCommands.SQL);
            var before = RemoteDatabaseSchemaResult.parse(first, mapper);
            assertThat(first).contains("\"COLUMN\"", "\"INDEX\"", "\"KEY\"", "\"REFERENCE\"", "CASCADE")
                    .doesNotContain("never-return", "fixture-only");
            query(name, "INSERT INTO parents VALUES (3, 'private');");
            assertThat(before.get("schemaSha256")).isEqualTo(RemoteDatabaseSchemaResult.parse(
                    query(name, RemoteDatabaseSchemaCommands.SQL), mapper).get("schemaSha256"));
            query(name, "ALTER TABLE parents ADD optional_name VARCHAR(100) NULL;");
            assertThat(before.get("schemaSha256")).isNotEqualTo(RemoteDatabaseSchemaResult.parse(
                    query(name, RemoteDatabaseSchemaCommands.SQL), mapper).get("schemaSha256"));
            assertThat(query(name, "SELECT COUNT(*) FROM parents; SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();"))
                    .isEqualTo("2\n2\n"); // inspection created no history or other table and preserved rows
        } finally {
            if (created) run(List.of("docker", "rm", "--force", "--volumes", name), "", Duration.ofSeconds(20));
        }
    }

    private static String query(String name, String sql) throws Exception {
        return run(List.of("docker", "exec", "-i", name, "sh", "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=socket --connect-timeout=2 "
                        + "--default-character-set=utf8mb4 --batch --raw --skip-column-names -uroot --database=\"$MYSQL_DATABASE\""),
                sql, Duration.ofSeconds(20));
    }

    private static String run(List<String> args, String input, Duration timeout) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readNBytes(1_000_000), StandardCharsets.UTF_8).replace("\r\n", "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("isolated MySQL fixture command timeout");
            var result = output.get(5, TimeUnit.SECONDS);
            assertThat(process.exitValue()).as(result).isZero();
            return result;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
