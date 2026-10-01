package com.agentstudio.adapter.ssh;

/** Fixed metadata query: no application rows, column defaults, SQL supplied by a model, or DDL. */
final class RemoteDatabaseSchemaCommands {
    static final String SQL = """
            SET SESSION MAX_EXECUTION_TIME=15000;
            START TRANSACTION READ ONLY;
            SELECT JSON_ARRAY('DATABASE', DATABASE(), VERSION());
            SELECT JSON_ARRAY('TABLE', TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION)
              FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY BINARY TABLE_NAME;
            SELECT JSON_ARRAY('COLUMN', TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE,
                              IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME, EXTRA)
              FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY BINARY TABLE_NAME, ORDINAL_POSITION;
            SELECT JSON_ARRAY('INDEX', TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                              COLLATION, SUB_PART, INDEX_TYPE)
              FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
              ORDER BY BINARY TABLE_NAME, BINARY INDEX_NAME, SEQ_IN_INDEX;
            SELECT JSON_ARRAY('CONSTRAINT', TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE)
              FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE()
              ORDER BY BINARY TABLE_NAME, BINARY CONSTRAINT_NAME;
            SELECT JSON_ARRAY('KEY', TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION, COLUMN_NAME,
                              REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME)
              FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
              ORDER BY BINARY TABLE_NAME, BINARY CONSTRAINT_NAME, ORDINAL_POSITION;
            SELECT JSON_ARRAY('REFERENCE', TABLE_NAME, CONSTRAINT_NAME, UNIQUE_CONSTRAINT_SCHEMA,
                              REFERENCED_TABLE_NAME, MATCH_OPTION, UPDATE_RULE, DELETE_RULE)
              FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
              ORDER BY BINARY TABLE_NAME, BINARY CONSTRAINT_NAME;
            SELECT JSON_ARRAY('TRIGGER', EVENT_OBJECT_TABLE, TRIGGER_NAME, EVENT_MANIPULATION, ACTION_TIMING)
              FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY BINARY TRIGGER_NAME;
            SELECT JSON_ARRAY('ROUTINE', ROUTINE_NAME, ROUTINE_TYPE)
              FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() ORDER BY BINARY ROUTINE_NAME;
            COMMIT;
            SELECT JSON_ARRAY('END', 'schema-metadata-v1');
            """;

    String command(RemoteDeploymentProfile profile) {
        // Credentials never cross SSH: the existing fixed mysql container supplies them.
        // The local pipe delivers only this class-owned SQL, not model/user text.
        return "cd " + quote(profile.remoteDeployRoot()) + " && printf '%s' " + quote(SQL)
                + " | timeout --signal=TERM --kill-after=2s 25s docker compose --project-name "
                + quote(profile.composeProject()) + " --file " + quote(profile.composeFile())
                + " exec -T mysql sh -c "
                + quote("MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=socket --connect-timeout=5 "
                        + "--default-character-set=utf8mb4 --batch --raw --skip-column-names -uroot "
                        + "--database=\"$MYSQL_DATABASE\"");
    }

    private static String quote(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("部署配置包含不安全字符");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
