package io.github.betapetey.eventtrail.storage;

import com.zaxxer.hikari.HikariConfig;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.ProductionAuditPayloadCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/** MySQL backend using the official MySQL Connector/J driver. */
public final class MySqlStorage extends AbstractJdbcStorage {
    static final String NULL_LOGGER = "com.mysql.cj.log.NullLogger";
    static final String ACQUIRE_SCHEMA_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    static final String RELEASE_SCHEMA_LOCK_SQL = "SELECT RELEASE_LOCK(?)";
    private static final String SERVER_ID_METADATA_SQL = """
            SELECT DATA_TYPE, CHARACTER_SET_NAME, COLLATION_NAME,
                   CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """;
    private static final String TABLE_CONTRACT_SQL = """
            SELECT t.ENGINE, c.DATA_TYPE, c.IS_NULLABLE, c.EXTRA
            FROM INFORMATION_SCHEMA.TABLES t
            JOIN INFORMATION_SCHEMA.COLUMNS c
              ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME
            WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = ? AND c.COLUMN_NAME = 'id'
            """;
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
    private final Path tlsTemporaryDirectory;
    private final String schemaLockName;
    private MySqlTlsTrustStore tlsTrustStore;

    public MySqlStorage(String serverId, String host, int port, String database, String username, String password, boolean ssl) {
        this(serverId, host, port, database, username, password, ssl, null);
    }

    public MySqlStorage(
            String serverId,
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean ssl,
            String sslCaFile) {
        this(serverId, host, port, database, username, password, ssl, sslCaFile, null);
    }

    MySqlStorage(
            String serverId,
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean ssl,
            String sslCaFile,
            Path tlsTemporaryDirectory) {
        super(serverId, ProductionAuditPayloadCodec.instance());
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
        this.sslCaFile = sslCaFile == null || sslCaFile.isBlank() ? null : sslCaFile;
        this.tlsTemporaryDirectory = tlsTemporaryDirectory;
        if (!ssl && this.sslCaFile != null) {
            throw new IllegalArgumentException("A MySQL CA file requires TLS");
        }
        if (ssl && this.sslCaFile == null) {
            throw new IllegalArgumentException(
                    "MySQL TLS requires an explicit CA certificate file");
        }
        this.schemaLockName = "eventtrail-schema-" + Integer.toUnsignedString(database.hashCode(), 16);
    }

    @Override
    protected HikariConfig createPoolConfig() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL JDBC driver is missing from the EventTrail JAR", e);
        }

        if (tlsTrustStore != null) {
            throw new IllegalStateException("MySQL storage already owns a TLS truststore");
        }
        MySqlTlsTrustStore createdTrustStore = null;
        try {
            if (ssl) {
                createdTrustStore = MySqlTlsTrustStore.create(
                        Path.of(sslCaFile), tlsTemporaryDirectory);
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl(host, port));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setUsername(username);
            config.setPassword(password);
            config.setCatalog(database);
            configureTransportSecurity(config, ssl, createdTrustStore);
            config.addDataSourceProperty(
                    "connectTimeout",
                    Integer.toString(EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get()));
            config.addDataSourceProperty(
                    "socketTimeout", Integer.toString(configuredSocketTimeoutMillis()));
            tlsTrustStore = createdTrustStore;
            createdTrustStore = null;
            return config;
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "EventTrail could not create the verified MySQL TLS truststore", failure);
        } finally {
            closeTrustStoreQuietly(createdTrustStore);
        }
    }

    @Override
    protected void acquireSchemaLock(Connection connection) throws SQLException {
        int timeoutSeconds = Math.max(1,
                (EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get() + 999) / 1_000);
        try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SCHEMA_LOCK_SQL)) {
            statement.setString(1, schemaLockName);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Timed out waiting for EventTrail MySQL schema initialization lock.");
                }
            }
        }
    }

    @Override
    protected void releaseSchemaLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_SCHEMA_LOCK_SQL)) {
            statement.setString(1, schemaLockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("EventTrail MySQL schema initialization lock was not released.");
                }
            }
        }
    }

    static int configuredSocketTimeoutMillis() {
        return JdbcTimeouts.socketTimeoutMillis(
                EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get(),
                EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
    }

    static boolean allowsPublicKeyRetrieval(boolean ssl) {
        return !ssl;
    }

    static String jdbcUrl(String host, int port) {
        return "jdbc:mysql://" + JdbcUrlSupport.hostForUrl(host) + ":" + port + "/";
    }

    static void configureTransportSecurity(
            HikariConfig config,
            boolean ssl,
            MySqlTlsTrustStore trustStore) {
        if (ssl != (trustStore != null)) {
            throw new IllegalArgumentException(
                    "Verified MySQL TLS requires exactly one run-owned truststore");
        }
        config.addDataSourceProperty("sslMode", ssl ? "VERIFY_IDENTITY" : "DISABLED");
        config.addDataSourceProperty("paranoid", "true");
        config.addDataSourceProperty("logger", NULL_LOGGER);
        config.addDataSourceProperty("openTelemetry", JdbcShutdownDiagnostics.enabled() ? "REQUIRED" : "DISABLED");
        if (ssl) {
            config.addDataSourceProperty("fallbackToSystemTrustStore", "false");
            config.addDataSourceProperty("fallbackToSystemKeyStore", "false");
            config.addDataSourceProperty("trustCertificateKeyStoreUrl", trustStore.url());
            config.addDataSourceProperty("trustCertificateKeyStoreType", "PKCS12");
            config.addDataSourceProperty(
                    "trustCertificateKeyStorePassword", trustStore.password());
        }
        config.addDataSourceProperty(
                "allowPublicKeyRetrieval", Boolean.toString(allowsPublicKeyRetrieval(ssl)));
    }

    Path activeTlsTrustStorePathForTesting() {
        return tlsTrustStore == null ? null : tlsTrustStore.pathForTesting();
    }

    @Override
    protected void closeBackendResources() throws IOException {
        MySqlTlsTrustStore current = tlsTrustStore;
        tlsTrustStore = null;
        if (current != null) current.close();
    }

    private static void closeTrustStoreQuietly(MySqlTlsTrustStore trustStore) {
        if (trustStore == null) return;
        try {
            trustStore.close();
        } catch (IOException ignored) {
            // MySqlTlsTrustStore has already registered deleteOnExit fallback.
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
                ON DUPLICATE KEY UPDATE
                    updated_at = IF(VALUES(version) > version, VALUES(updated_at), updated_at),
                    version = GREATEST(version, VALUES(version))
                """;
    }

    // ---- writer-spool design-02 6.3 and 6.4 (M-1, M-2) ----
    private static final String VERSION_TABLE_ENGINE_SQL = """
            SELECT ENGINE FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eventtrail_schema_version'
            """;
    /** The values read before the spool changed them; writer thread or init only. */
    private long spoolPreviousWaitTimeout = -1L;
    private long spoolPreviousLockWaitTimeout = -1L;
    /** MAJOR-4 (r2): the session value before the advance's bound (writer thread only). */
    private long spoolPreviousAdvanceLockWaitTimeout = -1L;

    @Override
    protected int spoolBackendCode() {
        return DiskSpool.BACKEND_MYSQL;
    }

    /** M-1: the position row must be in a transactional table; InnoDB only (MariaDB's Aria is refused too). */
    @Override
    protected boolean spoolVersionTableTransactional(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(VERSION_TABLE_ENGINE_SQL);
             ResultSet result = statement.executeQuery()) {
            return result.next() && isTransactionalEngine(result.getString(1)) && !result.next();
        }
    }

    /** M-2 step 1: a dead client's transaction ends within about a minute; the value read before is restored. */
    @Override
    protected void spoolApplySessionLimit(Connection connection) throws SQLException {
        spoolPreviousWaitTimeout = readSessionValue(connection, "SELECT @@SESSION.wait_timeout");
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION wait_timeout = 60");
        }
    }

    @Override
    protected boolean spoolRestoreSessionLimit(Connection connection) {
        return restoreSpoolSessionValue(connection, "wait_timeout", spoolPreviousWaitTimeout);
    }

    /** M-2 step 2: the start-up check waits at most 2 s for the position row's lock. */
    @Override
    protected void spoolApplyInitLockBound(Connection connection) throws SQLException {
        // r6: cleared first, as the advance bound does, so a failed read never lets the restore SET a value that an
        // earlier start-up step left here.
        spoolPreviousLockWaitTimeout = -1L;
        spoolPreviousLockWaitTimeout = readSessionValue(connection, "SELECT @@SESSION.innodb_lock_wait_timeout");
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = 2");
        }
    }

    /** r5: a failure is recorded for {@link #spoolTakeRestoreFailure}; the caller then evicts the connection. */
    @Override
    protected boolean spoolRestoreInitLockBound(Connection connection) {
        return restoreSpoolSessionValue(connection, "innodb_lock_wait_timeout", spoolPreviousLockWaitTimeout);
    }

    /** MAJOR-4 (r2): the advance waits at most 2 s for the position row's lock; restored right after it. */
    @Override
    protected void spoolApplyAdvanceLockBound(Connection connection) throws SQLException {
        spoolPreviousAdvanceLockWaitTimeout = -1L;
        long previous = readSessionValue(connection, "SELECT @@SESSION.innodb_lock_wait_timeout");
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = 2");
        }
        spoolPreviousAdvanceLockWaitTimeout = previous;
    }

    @Override
    protected boolean spoolEndAdvanceLockBound(Connection connection) {
        return spoolRestoreAdvanceLockBound(connection);
    }

    @Override
    protected boolean spoolRestoreAdvanceLockBound(Connection connection) {
        long previous = spoolPreviousAdvanceLockWaitTimeout;
        spoolPreviousAdvanceLockWaitTimeout = -1L;
        return restoreSpoolSessionValue(connection, "innodb_lock_wait_timeout", previous);
    }

    /**
     * r4 (N-1, M-2): the first restore failure since the last call; the caller names it in its WARN. r5: the
     * start-up check's restore records it too. Used by one thread at a time (init, then the writer).
     */
    private SQLException spoolRestoreFailure;

    @Override
    protected SQLException spoolTakeRestoreFailure() {
        SQLException failure = spoolRestoreFailure;
        spoolRestoreFailure = null;
        return failure;
    }

    /**
     * Restores a session value the spool changed ({@code value < 0}: nothing was changed), recording its failure
     * for {@link #spoolTakeRestoreFailure}. Never throws. r5: used by all three restores (start-up and writer).
     */
    private boolean restoreSpoolSessionValue(Connection connection, String variable, long value) {
        if (value < 0) return true;
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION " + variable + " = " + value);
            return true;
        } catch (SQLException failure) {
            if (spoolRestoreFailure == null) spoolRestoreFailure = failure;
            return false;
        } catch (RuntimeException failure) {
            if (spoolRestoreFailure == null) {
                spoolRestoreFailure = new SQLException("MySQL restore of " + variable + " failed", failure);
            }
            return false;
        }
    }

    private static long readSessionValue(Connection connection, String sql) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("MySQL did not report a session value", "ETSP7");
            return result.getLong(1);
        }
    }

    @Override
    protected String createLogTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS eventtrail_logs (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    server_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                    timestamp BIGINT NOT NULL,
                    action_type VARCHAR(64) NOT NULL,
                    player_uuid VARCHAR(64) NOT NULL,
                    player_name VARCHAR(255) NOT NULL,
                    world VARCHAR(255) NOT NULL,
                    pos_x INT NOT NULL,
                    pos_y INT NOT NULL,
                    pos_z INT NOT NULL,
                    block_state TEXT NULL,
                    extra_data LONGTEXT NULL
                ) ENGINE=InnoDB
                """;
    }

    @Override
    protected void validateStorageSchemaContract(Connection connection) throws SQLException {
        validateExternalColumnContract(connection);
        MySqlTableContract contract = readTableContract(connection);
        if (!isTransactionalEngine(contract.engine())) {
            throw new SQLException(
                    "EventTrail MySQL schema requires the transactional InnoDB storage engine.");
        }
        if (!isGeneratedIdColumn(
                contract.idDataType(), contract.idNullable(), contract.idExtra())) {
            throw new SQLException(
                    "EventTrail MySQL schema requires an auto-incrementing integer id primary key.");
        }
        if (!isExactServerIdColumn(readServerIdColumn(connection))) {
            throw new SQLException(
                    "EventTrail MySQL schema requires an exact-case server_id column.");
        }
    }

    private static MySqlTableContract readTableContract(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_CONTRACT_SQL)) {
            statement.setString(1, "eventtrail_logs");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException(
                            "EventTrail MySQL table metadata is unavailable.");
                }
                MySqlTableContract contract = new MySqlTableContract(
                        result.getString("ENGINE"),
                        result.getString("DATA_TYPE"),
                        result.getString("IS_NULLABLE"),
                        result.getString("EXTRA"));
                if (result.next()) {
                    throw new SQLException(
                            "EventTrail MySQL table metadata is ambiguous.");
                }
                return contract;
            }
        }
    }

    static boolean isTransactionalEngine(String engine) {
        return "innodb".equalsIgnoreCase(engine);
    }

    static boolean isGeneratedIdColumn(String dataType, String nullable, String extra) {
        if (dataType == null || nullable == null || extra == null) return false;
        String normalizedType = dataType.toLowerCase(Locale.ROOT);
        boolean integerType = switch (normalizedType) {
            case "tinyint", "smallint", "mediumint", "int", "integer", "bigint" -> true;
            default -> false;
        };
        return integerType
                && "NO".equalsIgnoreCase(nullable)
                && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
    }

    private static ServerIdColumn readServerIdColumn(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SERVER_ID_METADATA_SQL)) {
            statement.setString(1, "eventtrail_logs");
            statement.setString(2, "server_id");
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("EventTrail MySQL server_id column metadata is unavailable.");
                }
                ServerIdColumn column = new ServerIdColumn(
                        result.getString("DATA_TYPE"),
                        result.getString("CHARACTER_SET_NAME"),
                        result.getString("COLLATION_NAME"),
                        result.getLong("CHARACTER_MAXIMUM_LENGTH"),
                        result.getString("IS_NULLABLE"));
                if (result.next()) {
                    throw new SQLException("EventTrail MySQL server_id column metadata is ambiguous.");
                }
                return column;
            }
        }
    }

    static boolean isExactServerIdColumn(
            String dataType, String characterSet, String collation, long maximumLength, String nullable) {
        return isExactServerIdColumn(
                new ServerIdColumn(dataType, characterSet, collation, maximumLength, nullable));
    }

    private static boolean isExactServerIdColumn(ServerIdColumn column) {
        return "varchar".equalsIgnoreCase(column.dataType())
                && "utf8mb4".equalsIgnoreCase(column.characterSet())
                && "utf8mb4_bin".equalsIgnoreCase(column.collation())
                && column.maximumLength() == EventTrailConfig.MAX_EXTERNAL_SERVER_ID_LENGTH
                && "NO".equalsIgnoreCase(column.nullable());
    }

    private record ServerIdColumn(
            String dataType,
            String characterSet,
            String collation,
            long maximumLength,
            String nullable) {}

    private record MySqlTableContract(
            String engine,
            String idDataType,
            String idNullable,
            String idExtra) {}

    @Override
    DeleteParts buildRetentionDeleteBatchQuery(long cutoffTimestamp, int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("Retention batch size must be positive");
        return new DeleteParts(
                "DELETE FROM eventtrail_logs WHERE server_id = ? AND timestamp < ? " +
                        "ORDER BY timestamp ASC, id ASC LIMIT ?",
                List.of(serverId, cutoffTimestamp, batchSize));
    }

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
        return "CAST(CONVERT(SUBSTR(COALESCE(extra_data,''),1,?) USING utf8mb4) AS BINARY)"
                + " = CAST(CONVERT(? USING utf8mb4) AS BINARY)";
    }

    @Override
    protected String asciiNeedleFoldSql(String textExpression) {
        // T31-R4. utf8mb4_bin: a Turkish database collation cannot lower 'I' to dotless i. The binary result is
        // compared byte for byte, so accents and trailing spaces count (no _ai_ci folding, no PAD SPACE).
        return "CAST(REPLACE(LOWER(REPLACE(REPLACE(CONVERT(" + textExpression + " USING utf8mb4) COLLATE utf8mb4_bin,"
                + " CHAR(0xC4B0 USING utf8mb4), 'I'), CHAR(0xE284AA USING utf8mb4), 'K')),"
                + " CHAR(0x69CC87 USING utf8mb4), 'i') AS BINARY)";
    }

    @Override
    protected String asciiTextLowerSql(String textExpression) {
        // T31-R4 fix F-A. The fold above without its REPLACEs (no-ops on pure ASCII text): the same LOWER under
        // utf8mb4_bin and the same BINARY result.
        return "CAST(LOWER(CONVERT(" + textExpression + " USING utf8mb4) COLLATE utf8mb4_bin) AS BINARY)";
    }

    @Override
    protected String searchNeedleSql() {
        return "CAST(CONVERT(? USING utf8mb4) AS BINARY)";
    }

    @Override
    protected String nonAsciiTextSql(String textExpression) {
        return "LENGTH(CONVERT(" + textExpression + " USING utf8mb4)) <> CHAR_LENGTH(CONVERT(" + textExpression
                + " USING utf8mb4))";
    }

    @Override
    protected String exactNameLikeSql(String textExpression) {
        // Lane xn r2 (review-01 MINOR-1). Byte for byte, like the other MySQL text comparisons above: the
        // column's character set and collation no longer matter (extra_data declares neither), so an _ai_ci or
        // latin1 database can neither lose a candidate nor fail with "Illegal mix of collations" for a typed name
        // outside Latin-1; and it is case-sensitive, so it returns fewer false candidates for the Java check.
        return "CAST(CONVERT(" + textExpression + " USING utf8mb4) AS BINARY) LIKE "
                + searchNeedleSql() + " ESCAPE '!'";
    }

    @Override
    protected boolean usesServerIdColumn() {
        return true;
    }

    @Override
    public String getBackendName() {
        return "MySQL";
    }

    @Override
    public StorageMaintenanceSnapshot maintenanceSnapshot() {
        return StorageMaintenanceSnapshot.external(StorageMaintenanceSnapshot.Backend.MYSQL);
    }
}
