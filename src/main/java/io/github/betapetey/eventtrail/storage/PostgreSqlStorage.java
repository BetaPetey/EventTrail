package io.github.betapetey.eventtrail.storage;

import com.zaxxer.hikari.HikariConfig;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.ProductionAuditPayloadCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** PostgreSQL backend. */
public final class PostgreSqlStorage extends AbstractJdbcStorage {
    static final String STRICT_SAN_HOSTNAME_VERIFIER =
            "io.github.betapetey.eventtrail.jdbc.tls.EventTrailStrictSanHostnameVerifier";
    static final String SCHEMA_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";
    private static final long SCHEMA_LOCK_KEY = 0x4556545241494C31L;
    private static final String SERVER_ID_METADATA_SQL = """
            SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_CATALOG = current_database()
              AND TABLE_SCHEMA = current_schema()
              AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """;
    private static final String ID_METADATA_SQL = """
            SELECT
                pg_catalog.format_type(id_attribute.atttypid, id_attribute.atttypmod)
                    AS data_type,
                id_attribute.attnotnull AS not_null,
                id_attribute.attidentity AS identity_kind,
                table_namespace.nspname AS table_schema,
                owned_sequence.sequence_schema,
                owned_sequence.sequence_name,
                owned_sequence.dependency_type,
                owned_sequence.sequence_oid,
                default_sequence.sequence_oid AS default_sequence_oid
            FROM pg_catalog.pg_class table_class
            JOIN pg_catalog.pg_namespace table_namespace
              ON table_namespace.oid = table_class.relnamespace
            JOIN pg_catalog.pg_attribute id_attribute
              ON id_attribute.attrelid = table_class.oid
             AND id_attribute.attname = 'id'
             AND id_attribute.attnum > 0
             AND NOT id_attribute.attisdropped
            LEFT JOIN LATERAL (
                SELECT
                    sequence_namespace.nspname AS sequence_schema,
                    sequence_class.relname AS sequence_name,
                    ownership.deptype AS dependency_type,
                    sequence_class.oid::BIGINT AS sequence_oid
                FROM pg_catalog.pg_depend ownership
                JOIN pg_catalog.pg_class sequence_class
                  ON sequence_class.oid = ownership.objid
                 AND sequence_class.relkind = 'S'
                JOIN pg_catalog.pg_namespace sequence_namespace
                  ON sequence_namespace.oid = sequence_class.relnamespace
                WHERE ownership.classid = 'pg_catalog.pg_class'::regclass
                  AND ownership.refclassid = 'pg_catalog.pg_class'::regclass
                  AND ownership.refobjid = table_class.oid
                  AND ownership.refobjsubid = id_attribute.attnum
                  AND ownership.deptype IN ('a', 'i')
            ) owned_sequence ON TRUE
            LEFT JOIN pg_catalog.pg_attrdef id_default
              ON id_default.adrelid = table_class.oid
             AND id_default.adnum = id_attribute.attnum
            LEFT JOIN LATERAL (
                SELECT referenced_sequence.oid::BIGINT AS sequence_oid
                FROM pg_catalog.pg_depend default_dependency
                JOIN pg_catalog.pg_class referenced_sequence
                  ON referenced_sequence.oid = default_dependency.refobjid
                 AND referenced_sequence.relkind = 'S'
                WHERE default_dependency.classid = 'pg_catalog.pg_attrdef'::regclass
                  AND default_dependency.objid = id_default.oid
                  AND default_dependency.refclassid = 'pg_catalog.pg_class'::regclass
            ) default_sequence ON TRUE
            WHERE table_namespace.nspname = current_schema()
              AND table_class.relname = ?
              AND table_class.relkind = 'r'
            """;
    static final String EXACT_CASE_PROBE_SQL =
            "SELECT CAST(? AS VARCHAR(64)) = CAST(? AS VARCHAR(64))";
    static final List<IndexSpec> REQUIRED_INDEXES = List.of(
            indexSpec("idx_eventtrail_server_time", "server_id", "timestamp"),
            indexSpec("idx_eventtrail_server_world_time", "server_id", "world", "timestamp"),
            indexSpec("idx_eventtrail_server_pos", "server_id", "world", "pos_x", "pos_y", "pos_z"),
            indexSpec("idx_eventtrail_server_player_uuid", "server_id", "player_uuid"),
            indexSpec("idx_eventtrail_server_player_name_time", "server_id", "player_name", "timestamp"),
            indexSpec("idx_eventtrail_server_action_time", "server_id", "action_type", "timestamp"));
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final String sslCaFile;

    public PostgreSqlStorage(String serverId, String host, int port, String database, String username, String password, boolean ssl) {
        this(serverId, host, port, database, username, password, ssl, null);
    }

    public PostgreSqlStorage(
            String serverId,
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean ssl,
            String sslCaFile) {
        super(serverId, ProductionAuditPayloadCodec.instance());
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
        this.sslCaFile = sslCaFile == null || sslCaFile.isBlank() ? null : sslCaFile;
        if (!ssl && this.sslCaFile != null) {
            throw new IllegalArgumentException("A PostgreSQL CA file requires TLS");
        }
    }

    @Override
    protected HikariConfig createPoolConfig() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("PostgreSQL JDBC driver is missing from the EventTrail JAR", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(host, port));
        config.setDriverClassName("org.postgresql.Driver");
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("PGDBNAME", database);
        configureTransportSecurity(config, ssl, sslCaFile);
        config.addDataSourceProperty("connectTimeout", configuredConnectTimeoutSeconds());
        config.addDataSourceProperty("socketTimeout", configuredSocketTimeoutSeconds());
        return config;
    }

    @Override
    protected void acquireSchemaLock(Connection connection) throws SQLException {
        /*
         * PostgreSQL's CREATE TABLE IF NOT EXISTS can still race in the system
         * catalog when two sessions create the same relation simultaneously.
         * Serialize PostgreSQL's fresh catalog DDL through its first committed
         * EventTrail schema version; existing v1 schemas are then validated
         * without mutation.
         * The transaction releases the lock automatically on commit/rollback.
         */
        try (PreparedStatement statement = connection.prepareStatement(SCHEMA_LOCK_SQL)) {
            statement.setLong(1, SCHEMA_LOCK_KEY);
            statement.execute();
        }
    }

    static int configuredConnectTimeoutSeconds() {
        return JdbcTimeouts.connectTimeoutSeconds(EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get());
    }

    static int configuredSocketTimeoutSeconds() {
        return JdbcTimeouts.socketTimeoutSeconds(
                EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get(),
                EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
    }

    static String jdbcUrl(String host, int port) {
        return "jdbc:postgresql://" + JdbcUrlSupport.hostForUrl(host) + ":" + port + "/";
    }

    static void configureTransportSecurity(HikariConfig config, boolean ssl, String sslCaFile) {
        if (!ssl && sslCaFile != null && !sslCaFile.isBlank()) {
            throw new IllegalArgumentException("A PostgreSQL CA file requires TLS");
        }
        config.addDataSourceProperty("sslmode", ssl ? "verify-full" : "disable");
        if (sslCaFile != null && !sslCaFile.isBlank()) {
            config.addDataSourceProperty("sslrootcert", sslCaFile);
        }
        if (ssl) {
            config.addDataSourceProperty("sslhostnameverifier", STRICT_SAN_HOSTNAME_VERIFIER);
        }
    }

    @Override
    public StorageTransportSecurity getTransportSecurity() {
        return ssl
                ? StorageTransportSecurity.verifiedTls(sslCaFile != null)
                : StorageTransportSecurity.disabled();
    }

    @Override
    protected String schemaVersionUpsertSql() {
        return """
                INSERT INTO eventtrail_schema_version (component, version, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (component) DO UPDATE SET
                    version = GREATEST(eventtrail_schema_version.version, EXCLUDED.version),
                    updated_at = CASE
                        WHEN EXCLUDED.version > eventtrail_schema_version.version THEN EXCLUDED.updated_at
                        ELSE eventtrail_schema_version.updated_at
                    END
                """;
    }

    // ---- writer-spool design-02 6.4 (M-2): transaction-scoped, so nothing to restore ----
    @Override
    protected int spoolBackendCode() {
        return DiskSpool.BACKEND_POSTGRESQL;
    }

    @Override
    protected void spoolApplySessionLimit(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL idle_in_transaction_session_timeout = '60s'");
        }
    }

    @Override
    protected void spoolApplyInitLockBound(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '2s'");
        }
    }

    /** MAJOR-4 (r2): the advance waits at most 2 s for the position row's lock (this transaction only). */
    @Override
    protected void spoolApplyAdvanceLockBound(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '2s'");
        }
    }

    /** MAJOR-4 (r2): back to the session's value right after the advance, so the row INSERT is unchanged. */
    @Override
    protected boolean spoolEndAdvanceLockBound(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = DEFAULT");
        }
        return true;
    }

    @Override
    protected String createIndexSql(String indexName, String table, String columns) {
        return "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (" + columns + ")";
    }

    @Override
    protected String createLogTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS eventtrail_logs (
                    id BIGSERIAL PRIMARY KEY,
                    server_id VARCHAR(64) NOT NULL,
                    timestamp BIGINT NOT NULL,
                    action_type VARCHAR(64) NOT NULL,
                    player_uuid VARCHAR(64) NOT NULL,
                    player_name VARCHAR(255) NOT NULL,
                    world VARCHAR(255) NOT NULL,
                    pos_x INTEGER NOT NULL,
                    pos_y INTEGER NOT NULL,
                    pos_z INTEGER NOT NULL,
                    block_state TEXT NULL,
                    extra_data TEXT NULL
                )
                """;
    }

    @Override
    protected void validateStorageSchemaContract(Connection connection) throws SQLException {
        validateExternalColumnContract(connection);
        PostgreSqlIdContract contract = readIdContract(connection);
        if (!isExpectedIdContract(contract)) {
            throw new SQLException(
                    "EventTrail PostgreSQL schema requires its exact BIGSERIAL id sequence contract.");
        }
        validateServerIdDefinition(connection);
        try (PreparedStatement statement = connection.prepareStatement(EXACT_CASE_PROBE_SQL)) {
            statement.setString(1, "Survival");
            statement.setString(2, "survival");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getBoolean(1)) {
                    throw new SQLException(
                            "EventTrail PostgreSQL schema requires exact-case server identity comparisons.");
                }
            }
        }
    }

    private static PostgreSqlIdContract readIdContract(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ID_METADATA_SQL)) {
            statement.setString(1, "eventtrail_logs");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("EventTrail PostgreSQL id column metadata is unavailable.");
                }
                PostgreSqlIdContract contract = new PostgreSqlIdContract(
                        result.getString("DATA_TYPE"),
                        result.getBoolean("NOT_NULL"),
                        result.getString("IDENTITY_KIND"),
                        result.getString("TABLE_SCHEMA"),
                        result.getString("SEQUENCE_SCHEMA"),
                        result.getString("SEQUENCE_NAME"),
                        result.getString("DEPENDENCY_TYPE"),
                        nullableLong(result, "SEQUENCE_OID"),
                        nullableLong(result, "DEFAULT_SEQUENCE_OID"));
                if (result.next()) {
                    throw new SQLException("EventTrail PostgreSQL id column metadata is ambiguous.");
                }
                return contract;
            }
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    static boolean isExpectedIdContract(PostgreSqlIdContract contract) {
        if (contract == null
                || !"bigint".equalsIgnoreCase(contract.dataType())
                || !contract.notNull()
                || contract.identityKind() == null
                || !contract.identityKind().isEmpty()
                || contract.tableSchema() == null
                || contract.tableSchema().isBlank()
                || !contract.tableSchema().equals(contract.sequenceSchema())
                || !"eventtrail_logs_id_seq".equals(contract.sequenceName())
                || !"a".equals(contract.dependencyType())
                || contract.sequenceOid() == null
                || contract.defaultSequenceOid() == null) {
            return false;
        }
        return contract.sequenceOid().equals(contract.defaultSequenceOid());
    }

    private static void validateServerIdDefinition(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SERVER_ID_METADATA_SQL)) {
            statement.setString(1, "eventtrail_logs");
            statement.setString(2, "server_id");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !isExactServerIdColumn(
                        result.getString("DATA_TYPE"),
                        result.getLong("CHARACTER_MAXIMUM_LENGTH"),
                        result.getString("IS_NULLABLE"))) {
                    throw new SQLException(
                            "EventTrail PostgreSQL schema requires server_id VARCHAR(64) NOT NULL.");
                }
                if (result.next()) {
                    throw new SQLException("EventTrail PostgreSQL server_id column metadata is ambiguous.");
                }
            }
        }
    }

    static boolean isExactServerIdColumn(String dataType, long maximumLength, String nullable) {
        return "character varying".equalsIgnoreCase(dataType)
                && maximumLength == EventTrailConfig.MAX_EXTERNAL_SERVER_ID_LENGTH
                && "NO".equalsIgnoreCase(nullable);
    }

    record PostgreSqlIdContract(
            String dataType,
            boolean notNull,
            String identityKind,
            String tableSchema,
            String sequenceSchema,
            String sequenceName,
            String dependencyType,
            Long sequenceOid,
            Long defaultSequenceOid) {}

    @Override
    protected void ensureSchemaIndexes(Connection connection) throws SQLException {
        ensureIndexes(connection, "eventtrail_logs", REQUIRED_INDEXES);
    }

    @Override
    protected void validateSchemaIndexes(Connection connection) throws SQLException {
        requireIndexes(connection, "eventtrail_logs", REQUIRED_INDEXES);
    }

    @Override
    protected String tableName() {
        return "eventtrail_logs";
    }

    @Override
    protected String containerAccessPhaseComparisonSql() {
        return "SUBSTR(COALESCE(extra_data,''),1,?) COLLATE \"C\" = ? COLLATE \"C\"";
    }

    @Override
    protected String asciiNeedleFoldSql(String textExpression) {
        // T31-R4. lower() under "C" folds ASCII letters only, whatever the database locale (a tr_TR locale
        // would lower 'I' to dotless i); the comparison is byte for byte.
        return "replace(lower(replace(replace(" + textExpression + ", chr(304), 'I'), chr(8490), 'K') COLLATE \"C\"),"
                + " 'i' || chr(775), 'i') COLLATE \"C\"";
    }

    @Override
    protected String asciiTextLowerSql(String textExpression) {
        // T31-R4 fix F-A. The fold above without its replacements (no-ops on pure ASCII text): the same lower()
        // under "C" and the same "C" collation for the comparison.
        return "lower(" + textExpression + " COLLATE \"C\") COLLATE \"C\"";
    }

    @Override
    protected String nonAsciiTextSql(String textExpression) {
        return "octet_length(" + textExpression + ") <> char_length(" + textExpression + ")";
    }

    @Override
    protected String searchSelfTestTextSql() {
        return "CAST(? AS text)";
    }

    @Override
    protected boolean usesServerIdColumn() {
        return true;
    }

    @Override
    public String getBackendName() {
        return "PostgreSQL";
    }

    @Override
    public StorageMaintenanceSnapshot maintenanceSnapshot() {
        return StorageMaintenanceSnapshot.external(StorageMaintenanceSnapshot.Backend.POSTGRESQL);
    }
}
