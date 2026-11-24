package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * The admin-started table shrink after a purge: SQLite {@code VACUUM}, MySQL {@code OPTIMIZE TABLE}, PostgreSQL
 * {@code VACUUM FULL}. Fixed SQL only, run on the retention worker; never on the server thread and never an
 * operator SQL endpoint. Plain VACUUM keeps the file's vacuum mode and page layout: no conversion of any kind.
 *
 * <p>While the table is locked, the writer's ordinary retries hold new records in memory and then in the disk
 * spool, so the shrink refuses to start unless the spool is ready to take them.</p>
 *
 * <p>Space: on SQLite the free space is measured on the database file's own filesystem and the
 * shrink refuses when it is short or unknown. MySQL and PostgreSQL offer a normal database user no SQL that reports
 * the database server's free disk space, so the need is measured from the database's own size views, a figure with
 * headroom is shown, and the shrink starts only after the admin confirms that the database server has that much
 * free and while the need measured again just before the start still fits it.</p>
 */
public final class TableShrink {
    /** Keeps the spool usable during the shrink: its own free-space floor plus its full size limit. */
    static long spoolReserveBytes(DiskSpool spool) {
        return DiskSpool.FLOOR_BYTES + Math.max(0L, spool.stats().capBytes());
    }

    /** Native memory headroom beyond SQLite's in-memory temporary copy (temp_store=MEMORY). */
    static final long SQLITE_MEMORY_MARGIN_BYTES = 256L * 1024L * 1024L;
    private static final long MIB_BYTES = 1L << 20;
    /** The smallest headroom on the free space an admin confirms for MySQL or PostgreSQL. */
    static final long CONFIRM_HEADROOM_FLOOR_BYTES = 64L * MIB_BYTES;
    /** How long the shrink waits for the table lock before it gives up (the table is left as it was). */
    static final int LOCK_WAIT_SECONDS = 60;
    /** The database connection's own network time limit while the one shrink statement runs. */
    static final int SHRINK_NETWORK_TIMEOUT_MILLIS = (int) TimeUnit.HOURS.toMillis(12);
    private static final long ADMISSION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Tests only: runs inside the shrink's database lock, just before the shrink statement, on its connection. */
    static volatile java.util.function.Consumer<java.sql.Connection> insideLockForTest = connection -> {};
    /** Tests only: replaces the measured free disk space (SQLite) and free memory. */
    static volatile java.util.function.Function<Path, OptionalLong> freeDiskForTest;
    static volatile java.util.function.Supplier<OptionalLong> freeMemoryForTest;

    private TableShrink() {}

    public enum Backend { SQLITE, MYSQL, POSTGRESQL, UNSUPPORTED }

    public enum Space { ENOUGH, SHORT, UNKNOWN }

    /**
     * The one fail-closed space check: ENOUGH only when both numbers are known and the free space is at least the
     * need. An unknown need or unknown free space is UNKNOWN, never ENOUGH.
     */
    public static Space check(long neededBytes, OptionalLong freeBytes) {
        if (neededBytes < 0L || freeBytes == null || freeBytes.isEmpty() || freeBytes.getAsLong() < 0L) return Space.UNKNOWN;
        return freeBytes.getAsLong() >= neededBytes ? Space.ENOUGH : Space.SHORT;
    }

    /**
     * What a shrink needs, measured just now. {@code refusal} is set when the shrink must not start.
     * {@code tableBytes} is the size the shrink starts from; {@code measuredFrom} names the size source.
     */
    public record Plan(Backend backend, String refusal, long tableBytes, long neededDiskBytes, OptionalLong freeDiskBytes,
                       long neededMemoryBytes, OptionalLong freeMemoryBytes, String measuredFrom) {
        public Plan {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(freeDiskBytes, "freeDiskBytes");
            Objects.requireNonNull(freeMemoryBytes, "freeMemoryBytes");
            Objects.requireNonNull(measuredFrom, "measuredFrom");
        }

        public boolean refused() { return refusal != null; }

        /** MySQL and PostgreSQL: the admin confirms the database server's free space before the shrink starts. */
        public boolean needsAdminSpaceConfirm() {
            return refusal == null && (backend == Backend.MYSQL || backend == Backend.POSTGRESQL);
        }

        /**
         * MySQL and PostgreSQL: the free space the admin confirms, the measured need plus the larger of 64 MiB and
         * 5% of it, rounded up to whole MiB; -1 when the need is unknown. Records written between the measure and
         * the start make the need grow a little; the shrink still starts while the new need is at most this.
         */
        public long confirmSpaceBytes() {
            if (neededDiskBytes < 0L) return -1L;
            long headroom = Math.max(CONFIRM_HEADROOM_FLOOR_BYTES, Math.ceilDiv(neededDiskBytes, 20L));
            return Math.multiplyExact(Math.ceilDiv(Math.addExact(neededDiskBytes, headroom), MIB_BYTES), MIB_BYTES);
        }

        public static Plan unsupported() {
            return refuse(Backend.UNSUPPORTED, "this storage cannot shrink its table");
        }

        public static Plan refuse(Backend backend, String why) {
            return new Plan(backend, why, -1L, -1L, OptionalLong.empty(), 0L, OptionalLong.empty(), "");
        }
    }

    /** {@code ran} only when the shrink statement finished; the sizes are -1 when unknown. */
    public record Result(boolean ran, long beforeBytes, long afterBytes, String failure) {
        public static Result notRun(String why) { return new Result(false, -1L, -1L, Objects.requireNonNull(why)); }
    }

    // ---- plan -------------------------------------------------------------------------------------------------------

    static Plan plan(AbstractJdbcStorage storage) {
        Backend backend = backendOf(storage);
        if (backend == Backend.UNSUPPORTED) return Plan.unsupported();
        if (!storage.isReady()) return Plan.refuse(backend, "EventTrail storage is not ready");
        DiskSpool spool = storage.spoolForShrink();
        if (spool == null) {
            return Plan.refuse(backend, "the disk spool is off or not ready, so new records would have nowhere to "
                    + "wait while the table is locked");
        }
        try {
            return switch (backend) {
                case SQLITE -> planSqlite((SQLiteStorage) storage, spool);
                case MYSQL -> planMySql(storage);
                case POSTGRESQL -> planPostgreSql(storage);
                case UNSUPPORTED -> Plan.unsupported();
            };
        } catch (SQLException | RuntimeException failure) {
            return Plan.refuse(backend, "the table size could not be measured ("
                    + EventTrailDiagnostics.safeFailureSummary(failure) + ")");
        }
    }

    static Backend backendOf(ILogStorage storage) {
        if (storage instanceof SQLiteStorage) return Backend.SQLITE;
        if (storage instanceof MySqlStorage) return Backend.MYSQL;
        if (storage instanceof PostgreSqlStorage) return Backend.POSTGRESQL;
        return Backend.UNSUPPORTED;
    }

    /**
     * SQLite. Plain VACUUM builds the compacted copy in a temporary database, which temp_store=MEMORY keeps in
     * memory, then writes the copy back through the write-ahead log on the database's own disk. Needed: on that
     * disk the compacted size plus the spool's reserve; in memory the compacted size plus a margin.
     */
    private static Plan planSqlite(SQLiteStorage storage, DiskSpool spool) throws SQLException {
        long[] pages = storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS,
                (connection, statement) -> new long[]{pragma(statement, "page_size"), pragma(statement, "page_count"),
                        pragma(statement, "freelist_count")});
        long pageSize = pages[0];
        long fileBytes = Math.multiplyExact(pageSize, pages[1]);
        long compacted = Math.multiplyExact(pageSize, Math.max(0L, pages[1] - pages[2]));
        long neededDisk = Math.addExact(compacted, spoolReserveBytes(spool));
        long neededMemory = Math.addExact(compacted, SQLITE_MEMORY_MARGIN_BYTES);
        Path folder = storage.getDbFile().toAbsolutePath().getParent();
        OptionalLong freeDisk = freeDisk(folder);
        OptionalLong freeMemory = freeMemory();
        String refusal = switch (check(neededDisk, freeDisk)) {
            case ENOUGH -> switch (check(neededMemory, freeMemory)) {
                case ENOUGH -> null;
                case SHORT -> "not enough free memory for SQLite's temporary copy";
                case UNKNOWN -> "the free memory could not be measured";
            };
            case SHORT -> "not enough free disk space next to the database file";
            case UNKNOWN -> "the free disk space next to the database file could not be measured";
        };
        return new Plan(Backend.SQLITE, refusal, fileBytes, neededDisk, freeDisk, neededMemory, freeMemory,
                "PRAGMA page_size, page_count and freelist_count; free space of the database file's own disk");
    }

    /**
     * MySQL. OPTIMIZE TABLE on InnoDB is a table rebuild (recreate + analyze): a new copy of the rows and indexes
     * beside the old one, sort files for the indexes, and a log of the changes made meanwhile, at most
     * innodb_online_alter_log_max_size. Needed: twice the table's data and index size plus that log limit.
     */
    private static Plan planMySql(AbstractJdbcStorage storage) throws SQLException {
        String table = storage.tableName();
        return storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS, (connection, statement) -> {
            // The size columns are otherwise cached for up to a day; a session value needs no privilege.
            statement.execute("SET SESSION information_schema_stats_expiry = 0");
            try {
                String engine;
                long data;
                long index;
                try (PreparedStatement sizes = connection.prepareStatement("SELECT ENGINE, DATA_LENGTH, INDEX_LENGTH "
                        + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                    sizes.setString(1, table);
                    try (ResultSet row = sizes.executeQuery()) {
                        if (!row.next()) return Plan.refuse(Backend.MYSQL, "the EventTrail table was not found");
                        engine = row.getString(1);
                        data = row.getLong(2);
                        index = row.getLong(3);
                    }
                }
                long filePerTable;
                long alterLog;
                try (ResultSet row = statement.executeQuery(
                        "SELECT @@innodb_file_per_table, @@innodb_online_alter_log_max_size")) {
                    if (!row.next()) throw new SQLException("MySQL settings unavailable");
                    filePerTable = row.getLong(1);
                    alterLog = row.getLong(2);
                }
                if (!"InnoDB".equalsIgnoreCase(engine)) {
                    return Plan.refuse(Backend.MYSQL, "the EventTrail table is not an InnoDB table");
                }
                if (filePerTable != 1L) {
                    return Plan.refuse(Backend.MYSQL, "innodb_file_per_table is off on this MySQL server, so a rebuild "
                            + "would move the table into the shared system file and give no space back");
                }
                long tableBytes = Math.addExact(Math.max(0L, data), Math.max(0L, index));
                long needed = Math.addExact(Math.multiplyExact(2L, tableBytes), Math.max(0L, alterLog));
                return new Plan(Backend.MYSQL, null, tableBytes, needed, OptionalLong.empty(), 0L, OptionalLong.empty(),
                        "information_schema.TABLES DATA_LENGTH + INDEX_LENGTH, and innodb_online_alter_log_max_size");
            } finally {
                statement.execute("SET SESSION information_schema_stats_expiry = DEFAULT");
            }
        });
    }

    /**
     * PostgreSQL. VACUUM FULL writes a new copy of the table and its indexes before it removes the old one, and the
     * new copy also passes through the write-ahead log. Needed: twice pg_total_relation_size.
     */
    private static Plan planPostgreSql(AbstractJdbcStorage storage) throws SQLException {
        String table = storage.tableName();
        return storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS, (connection, statement) -> {
            try (PreparedStatement sizes = connection.prepareStatement("SELECT pg_total_relation_size(c.oid), "
                    + "pg_has_role(current_user, c.relowner, 'USAGE') FROM pg_class c WHERE c.oid = to_regclass(?)")) {
                sizes.setString(1, table);
                try (ResultSet row = sizes.executeQuery()) {
                    if (!row.next()) return Plan.refuse(Backend.POSTGRESQL, "the EventTrail table was not found");
                    long total = row.getLong(1);
                    if (!row.getBoolean(2)) {
                        return Plan.refuse(Backend.POSTGRESQL, "the database user EventTrail uses does not own the "
                                + "EventTrail table, so VACUUM FULL would skip it");
                    }
                    long needed = Math.multiplyExact(2L, Math.max(0L, total));
                    return new Plan(Backend.POSTGRESQL, null, total, needed, OptionalLong.empty(), 0L,
                            OptionalLong.empty(), "pg_total_relation_size (table, indexes and TOAST)");
                }
            }
        });
    }

    // ---- run --------------------------------------------------------------------------------------------------------

    /** Measures again, refuses when the space no longer fits what was confirmed, then runs the one statement. */
    static Result run(AbstractJdbcStorage storage, Plan confirmed) {
        Objects.requireNonNull(confirmed, "confirmed");
        if (confirmed.refused()) return Result.notRun(confirmed.refusal());
        Plan now = plan(storage);
        if (now.refused()) return Result.notRun(now.refusal());
        if (now.backend() != confirmed.backend()) return Result.notRun("the database changed since the confirm");
        String grew = grewPastConfirm(now, confirmed);
        if (grew != null) return Result.notRun(grew);
        try {
            switch (now.backend()) {
                case SQLITE -> runSqlite((SQLiteStorage) storage);
                case MYSQL -> runMySql(storage);
                case POSTGRESQL -> runPostgreSql(storage);
                case UNSUPPORTED -> throw new IllegalStateException("unsupported");
            }
        } catch (SQLException | RuntimeException failure) {
            // A stopped VACUUM can leave a long write-ahead log; empty it as after a finished one (best effort).
            if (now.backend() == Backend.SQLITE) truncateLog((SQLiteStorage) storage);
            // Each backend's shrink is one statement that commits whole or not at all: the table is as it was.
            return new Result(false, now.tableBytes(), -1L, "the database stopped it and kept the table as it was ("
                    + EventTrailDiagnostics.safeFailureSummary(failure)
                    + (failure instanceof SQLException sql ? ", error code " + sql.getErrorCode() : "") + ")");
        }
        return new Result(true, now.tableBytes(), sizeAfter(storage, now.backend()), null);
    }

    /**
     * MySQL and PostgreSQL: null while the need measured just now is at most the space the admin confirmed
     * ({@link Plan#confirmSpaceBytes()}), else why the shrink does not start.
     */
    static String grewPastConfirm(Plan now, Plan confirmed) {
        if (!now.needsAdminSpaceConfirm() || now.neededDiskBytes() <= confirmed.confirmSpaceBytes()) return null;
        return "the shrink now needs " + mib(now.neededDiskBytes()) + ", more than the " + mib(confirmed.confirmSpaceBytes())
                + " that was confirmed; run the shrink command again";
    }

    private static void truncateLog(SQLiteStorage storage) {
        try {
            storage.checkpoint(SQLiteStorage.CheckpointMode.TRUNCATE);
        } catch (SQLException | RuntimeException busyOrClosed) {
            // The next checkpoint copies it back instead.
        }
    }

    /** The size after a finished shrink; -1 when it cannot be read (the shrink itself still finished). */
    private static long sizeAfter(AbstractJdbcStorage storage, Backend backend) {
        try {
            return switch (backend) {
                case SQLITE -> {
                    SQLiteStorage sqlite = (SQLiteStorage) storage;
                    // The file shrinks when the write-ahead log is copied back; a busy reader leaves that to the
                    // next checkpoint.
                    sqlite.checkpoint(SQLiteStorage.CheckpointMode.TRUNCATE);
                    yield Files.size(sqlite.getDbFile());
                }
                case MYSQL -> planMySql(storage).tableBytes();
                case POSTGRESQL -> planPostgreSql(storage).tableBytes();
                case UNSUPPORTED -> -1L;
            };
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return -1L;
        }
    }

    private static void runSqlite(SQLiteStorage storage) throws SQLException {
        storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS, (connection, statement) -> {
            insideLockForTest.accept(connection);
            statement.execute("VACUUM");
            return null;
        });
    }

    private static void runMySql(AbstractJdbcStorage storage) throws SQLException {
        String table = storage.tableName();
        storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS, (connection, statement) -> {
            insideLockForTest.accept(connection);
            int network = connection.getNetworkTimeout();
            statement.execute("SET SESSION lock_wait_timeout = " + LOCK_WAIT_SECONDS);
            connection.setNetworkTimeout(Runnable::run, SHRINK_NETWORK_TIMEOUT_MILLIS);
            try {
                String status = null;
                String error = null;
                try (ResultSet rows = statement.executeQuery("OPTIMIZE TABLE " + table)) {
                    while (rows.next()) {
                        String type = rows.getString("Msg_type");
                        if ("error".equalsIgnoreCase(type) && error == null) error = rows.getString("Msg_text");
                        if ("status".equalsIgnoreCase(type)) status = rows.getString("Msg_text");
                    }
                }
                if (error != null || !"OK".equalsIgnoreCase(status)) {
                    throw new SQLException("OPTIMIZE TABLE did not finish: " + (error != null ? error : status));
                }
            } finally {
                restore(connection, network, statement, "SET SESSION lock_wait_timeout = DEFAULT");
            }
            return null;
        });
    }

    private static void runPostgreSql(AbstractJdbcStorage storage) throws SQLException {
        String table = storage.tableName();
        storage.withCoordinatedMaintenance(System.nanoTime() + ADMISSION_WAIT_NANOS, (connection, statement) -> {
            insideLockForTest.accept(connection);
            int network = connection.getNetworkTimeout();
            statement.execute("SET lock_timeout = '" + LOCK_WAIT_SECONDS + "s'");
            connection.setNetworkTimeout(Runnable::run, SHRINK_NETWORK_TIMEOUT_MILLIS);
            try {
                statement.execute("VACUUM FULL " + table);
                SQLWarning warning = statement.getWarnings();
                if (warning != null) {
                    // For example "skipping ... only table or database owner can vacuum it": nothing was rebuilt.
                    throw new SQLException("VACUUM FULL reported a warning (" + warning.getSQLState() + ")");
                }
            } finally {
                restore(connection, network, statement, "RESET lock_timeout");
            }
            return null;
        });
    }

    /**
     * Puts the borrowed connection's time limits back. Best effort after the shrink statement, so a failure here
     * cannot turn a finished shrink into a reported failure. Both calls fail only on a broken connection, which the
     * pool's own validation then drops; a kept 60-second lock limit would only make a later lock wait give up
     * sooner, and the writer retries that.
     */
    private static void restore(java.sql.Connection connection, int networkTimeoutMillis, Statement statement,
                                String resetSql) {
        try {
            connection.setNetworkTimeout(Runnable::run, networkTimeoutMillis);
        } catch (SQLException | RuntimeException brokenConnection) {
            // See above.
        }
        try {
            statement.execute(resetSql);
        } catch (SQLException | RuntimeException brokenConnection) {
            // See above.
        }
    }

    // ---- measuring ----------------------------------------------------------------------------------------------------

    private static long pragma(Statement statement, String fixedName) throws SQLException {
        try (ResultSet row = statement.executeQuery("PRAGMA main." + fixedName)) {
            if (!row.next()) throw new SQLException("SQLite size unavailable");
            long value = row.getLong(1);
            if (row.wasNull() || value < 0L) throw new SQLException("Invalid SQLite size");
            return value;
        }
    }

    private static OptionalLong freeDisk(Path folder) {
        var override = freeDiskForTest;
        if (override != null) return override.apply(folder);
        try {
            long usable = Files.getFileStore(folder).getUsableSpace();
            return usable >= 0L ? OptionalLong.of(usable) : OptionalLong.empty();
        } catch (IOException | RuntimeException unavailable) {
            return OptionalLong.empty();
        }
    }

    private static OptionalLong freeMemory() {
        var override = freeMemoryForTest;
        if (override != null) return override.get();
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                long free = os.getFreeMemorySize();
                return free >= 0L ? OptionalLong.of(free) : OptionalLong.empty();
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    /** For the confirm text and the log: bytes as MiB, rounded up. */
    public static String mib(long bytes) {
        if (bytes < 0L) return "unknown";
        return ((bytes + (1L << 20) - 1L) >> 20) + " MiB";
    }
}
