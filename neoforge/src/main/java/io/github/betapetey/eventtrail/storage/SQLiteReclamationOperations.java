package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.commands.EventTrailQueryDispatcher;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.NativeDB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Fixed, finite storage-internal operations. Episode authority and scheduling live elsewhere. */
final class SQLiteReclamationOperations implements SQLiteReclamationController.Operations {
    static final int MAX_PAGES = 64;
    static final long MIN_HEADROOM_BYTES = 128L << 20;
    static final long MAX_WAL_BYTES = 32L << 20;
    private static final Map<String, String> RUNTIMES = Map.of(
            "3.25.2", "3.25.2", "3.36.0.3", "3.36.0", "3.45.1.0", "3.45.1",
            "3.53.2.1", "3.53.2", "3.53.4.0", "3.53.4");

    enum State { ELIGIBLE, COMPLETED, YIELDED, REUSE_RESERVE_RETAINED, CHECKPOINT_DEFERRED,
        DEFERRED_LOW_HEADROOM, UNSUPPORTED_RUNTIME, UNQUALIFIED_LAYOUT, MIGRATION_REQUIRED,
        FULL_MODE_UNCHANGED, UNRECOGNIZED_OBJECTS }

    record Observation(int mode, long pageSize, long pageCount, long freelistPages,
                       long mainBytes, long walBytes, long shmBytes, long observedAtMillis,
                       boolean recognizedObjects, String jdbcVersion, String nativeVersion,
                       boolean runtimeIdentityValid) {
        Observation {
            if (pageSize <= 0 || pageCount < 0 || freelistPages < 0 || freelistPages > pageCount
                    || mainBytes < 0 || walBytes < 0 || shmBytes < 0 || observedAtMillis < 0)
                throw new IllegalArgumentException("Invalid SQLite allocation observation");
            Math.multiplyExact(pageSize, pageCount);
            Math.multiplyExact(pageSize, freelistPages);
        }
        long freeBytes() { return Math.multiplyExact(pageSize, freelistPages); }
        long allocationBytes() { return Math.multiplyExact(pageSize, pageCount); }
        State capability() {
            if (!runtimeIdentityValid || !qualifiedRuntime(jdbcVersion, nativeVersion)) return State.UNSUPPORTED_RUNTIME;
            if (!recognizedObjects) return State.UNRECOGNIZED_OBJECTS;
            if (mode == 0) return State.MIGRATION_REQUIRED;
            if (mode == 1) return State.FULL_MODE_UNCHANGED;
            if (mode != 2) return State.UNSUPPORTED_RUNTIME;
            return pageSize == 4096 && pageCount > 0 ? State.ELIGIBLE : State.UNQUALIFIED_LAYOUT;
        }
        StorageMaintenanceSnapshot snapshot() {
            var knownMode = switch (mode) {
                case 0 -> StorageMaintenanceSnapshot.SQLiteMode.NONE;
                case 1 -> StorageMaintenanceSnapshot.SQLiteMode.FULL;
                case 2 -> StorageMaintenanceSnapshot.SQLiteMode.INCREMENTAL;
                default -> StorageMaintenanceSnapshot.SQLiteMode.UNKNOWN;
            };
            var base = StorageMaintenanceSnapshot.sqlite(knownMode, observedAtMillis, pageSize, pageCount,
                    freelistPages, mainBytes, walBytes, shmBytes);
            var capability = switch (capability()) {
                case ELIGIBLE -> StorageMaintenanceSnapshot.Capability.INCREMENTAL_CAPABLE;
                case MIGRATION_REQUIRED -> StorageMaintenanceSnapshot.Capability.MIGRATION_REQUIRED;
                case FULL_MODE_UNCHANGED -> StorageMaintenanceSnapshot.Capability.FULL_MODE_UNCHANGED;
                case UNRECOGNIZED_OBJECTS -> StorageMaintenanceSnapshot.Capability.UNRECOGNIZED_OBJECTS;
                case UNQUALIFIED_LAYOUT -> StorageMaintenanceSnapshot.Capability.UNQUALIFIED_LAYOUT;
                default -> StorageMaintenanceSnapshot.Capability.UNSUPPORTED_RUNTIME;
            };
            return new StorageMaintenanceSnapshot(base.backend(), base.scope(), base.sqliteMode(), capability,
                    base.reclamationState(), base.observedAtMillis(), base.observation(), base.allocation(), null);
        }
    }

    record UnitResult(State state, String reason, int requestedPages, Observation before, Observation after,
                      long nativeNanos, long settledNanos, long releasedPages,
                      long pageCountReduction, long mainFileReduction) {
        UnitResult settled(long nanos) { return new UnitResult(state, reason, requestedPages, before, after,
                nativeNanos, nanos, releasedPages, pageCountReduction, mainFileReduction); }
    }
    record CheckpointResult(State state, String reason, int busy, int logFrames, int checkpointedFrames,
                            Observation before, Observation after, long nativeNanos, long settledNanos) {
        CheckpointResult settled(long nanos) { return new CheckpointResult(state, reason, busy, logFrames,
                checkpointedFrames, before, after, nativeNanos, nanos); }
    }

    private final SQLiteStorage storage;
    SQLiteReclamationOperations(SQLiteStorage storage) { this.storage = Objects.requireNonNull(storage); }

    boolean quiet() {
        var writer = storage.getWriteBufferStats();
        var reads = storage.getReadExecutorStats();
        return storage.isReady() && !Thread.currentThread().isInterrupted() && writer.acceptingWrites()
                && writer.pending() == 0 && writer.pendingPayloadBytes() == 0 && !writer.retrying()
                && reads.queued() == 0 && reads.activeWorkers() == 0 && reads.trackedOperations() == 0
                && !EventTrailQueryDispatcher.hasOutstandingWork()
                && storage.spoolWaitingActions() == 0;
    }

    @Override public Observation observe(BooleanSupplier admission) throws SQLException {
        Objects.requireNonNull(admission);
        if (!admission.getAsBoolean() || !quiet()) return null;
        return withNonwaiting((connection, statement) -> admission.getAsBoolean() && quiet()
                ? observe(connection, statement) : null);
    }

    /** The initialization caller already owns its schema mutation lease and initializing lifetime. */
    Observation observeInitial(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { return observe(connection, statement); }
    }

    @Override public UnitResult vacuum(long reservePages, long remainingPages, long groupPages, int cap,
                      BooleanSupplier admission) throws SQLException {
        if (reservePages < 0 || remainingPages < 0 || groupPages < 0 || cap < 1 || cap > MAX_PAGES)
            throw new IllegalArgumentException("Invalid bounded incremental allowance");
        Objects.requireNonNull(admission);
        long started = System.nanoTime();
        if (!admission.getAsBoolean() || !quiet()) return withheld(State.YIELDED, "ADMISSION_WITHDRAWN", null).settled(elapsed(started));
        UnitResult result = withNonwaiting((connection, statement) -> {
            if (!admission.getAsBoolean() || !quiet()) return withheld(State.YIELDED, "ADMISSION_AFTER_WAIT", null);
            Observation before = observe(connection, statement);
            State gate = eligible(before);
            if (gate != State.ELIGIBLE) return withheld(gate, gate.name(), before);
            // This observation is fresh under the same mutation lease as the finite native unit.
            long available = before.freelistPages() > reservePages ? before.freelistPages() - reservePages : 0;
            int pages = (int) Math.min(cap, Math.min(available, Math.min(remainingPages, groupPages)));
            if (pages <= 0) return withheld(State.REUSE_RESERVE_RETAINED, "RESERVE_OR_ALLOWANCE_RETAINED", before);
            if (!admission.getAsBoolean() || !quiet()) return withheld(State.YIELDED, "ADMISSION_BEFORE_NATIVE", before);
            long nativeStarted = System.nanoTime();
            // Xerial exec drives the zero-column ResultRow loop through SQLITE_DONE on each allowed runtime.
            // Its affected-row count is not a reclamation receipt.
            statement.executeUpdate("PRAGMA main.incremental_vacuum(" + pages + ")");
            long nativeNanos = elapsed(nativeStarted);
            Observation after = observe(connection, statement);
            long released = before.freelistPages() - after.freelistPages();
            if (released != pages || after.freelistPages() < reservePages || after.pageCount() > before.pageCount())
                throw new SQLException("Finite incremental completion could not be proven");
            return new UnitResult(State.COMPLETED, "FINITE_UNIT_COMPLETED", pages, before, after, nativeNanos,
                    0, released, before.pageCount() - after.pageCount(), Math.max(0, before.mainBytes() - after.mainBytes()));
        });
        // Includes busy-policy restoration, statement/result close, connection return, mutation and auxiliary close.
        return result.settled(elapsed(started));
    }

    @Override public CheckpointResult passive(BooleanSupplier admission) throws SQLException {
        Objects.requireNonNull(admission);
        long started = System.nanoTime();
        if (!admission.getAsBoolean() || !quiet()) return checkpointWithheld(State.YIELDED, "ADMISSION_WITHDRAWN", null).settled(elapsed(started));
        CheckpointResult result = withNonwaiting((connection, statement) -> {
            if (!admission.getAsBoolean() || !quiet()) return checkpointWithheld(State.YIELDED, "ADMISSION_AFTER_WAIT", null);
            Observation before = observe(connection, statement);
            State gate = eligible(before);
            if (gate != State.ELIGIBLE) return checkpointWithheld(gate, gate.name(), before);
            if (!admission.getAsBoolean() || !quiet()) return checkpointWithheld(State.YIELDED, "ADMISSION_BEFORE_NATIVE", before);
            long nativeStarted = System.nanoTime();
            int busy, log, done;
            try (ResultSet rows = statement.executeQuery("PRAGMA main.wal_checkpoint(PASSIVE)")) {
                if (!rows.next()) throw new SQLException("Missing PASSIVE acknowledgement");
                busy = exactInt(rows, 1); log = exactInt(rows, 2); done = exactInt(rows, 3);
                if (done > log || rows.next()) throw new SQLException("Invalid PASSIVE acknowledgement");
            }
            long nativeNanos = elapsed(nativeStarted);
            Observation after = observe(connection, statement);
            boolean complete = busy == 0 && done == log;
            return new CheckpointResult(complete ? State.COMPLETED : State.CHECKPOINT_DEFERRED,
                    complete ? "PASSIVE_COMPLETED" : "PASSIVE_BACKFILL_INCOMPLETE", busy, log, done, before, after, nativeNanos, 0);
        });
        return result.settled(elapsed(started));
    }

    private State eligible(Observation observation) throws SQLException {
        if (observation.capability() != State.ELIGIBLE) return observation.capability();
        storage.verifyReclamationOwnership();
        long usable;
        try { usable = Files.getFileStore(storage.getDbFile()).getUsableSpace(); }
        catch (IOException | SecurityException unavailable) { usable = -1; }
        return envelope(usable, observation.walBytes());
    }

    static State envelope(long usableBytes, long walBytes) {
        if (usableBytes < MIN_HEADROOM_BYTES) return State.DEFERRED_LOW_HEADROOM;
        if (walBytes < 0 || walBytes > MAX_WAL_BYTES) return State.CHECKPOINT_DEFERRED;
        return State.ELIGIBLE;
    }
    static boolean qualifiedRuntime(String jdbc, String nativeCore) {
        return jdbc != null && nativeCore != null && nativeCore.equals(RUNTIMES.get(jdbc));
    }
    static boolean runtimeIdentity(Connection connection) {
        try {
            SQLiteConnection physical = connection.unwrap(SQLiteConnection.class);
            Class<?> nativeType = physical.getDatabase().getClass();
            return nativeType == NativeDB.class && sameOrigin(SQLiteConnection.class, JDBC.class)
                    && sameOrigin(physical.getClass(), JDBC.class) && sameOrigin(nativeType, JDBC.class);
        } catch (SQLException | RuntimeException | LinkageError unavailable) { return false; }
    }
    private static boolean sameOrigin(Class<?> type, Class<?> authority) {
        var source = authority.getProtectionDomain().getCodeSource();
        var candidate = type.getProtectionDomain().getCodeSource();
        return type.getClassLoader() == authority.getClassLoader() && source != null && candidate != null
                && source.getLocation() != null && candidate.getLocation() != null
                && source.getLocation().toExternalForm().equals(candidate.getLocation().toExternalForm());
    }
    static String nativeVersion(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT sqlite_version()")) {
            if (!rows.next()) throw new SQLException("Missing SQLite native runtime");
            String version = rows.getString(1);
            if (version == null || rows.next()) throw new SQLException("Invalid SQLite native runtime");
            return version;
        }
    }

    private <T> T withNonwaiting(AbstractJdbcStorage.MaintenanceWork<T> work) throws SQLException {
        return storage.withCoordinatedMaintenance(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100),
                (connection, statement) -> {
                    long captured = scalar(statement, "PRAGMA busy_timeout");
                    if (captured > Integer.MAX_VALUE) throw new SQLException("Invalid captured busy policy");
                    Throwable primary = null;
                    try {
                        connection.unwrap(SQLiteConnection.class).setBusyTimeout(0);
                        if (scalar(statement, "PRAGMA busy_timeout") != 0) throw new SQLException("Nonwaiting policy not established");
                        return work.run(connection, statement);
                    } catch (SQLException | RuntimeException | Error failure) { primary = failure; throw failure;
                    } finally {
                        try {
                            connection.unwrap(SQLiteConnection.class).setBusyTimeout((int) captured);
                            if (scalar(statement, "PRAGMA busy_timeout") != captured) throw new SQLException("Captured busy policy not restored");
                        } catch (SQLException | RuntimeException | Error cleanup) {
                            retire(connection, cleanup);
                            if (primary == null) throw cleanup;
                            if (cleanup != primary) primary.addSuppressed(cleanup);
                        }
                    }
                });
    }

    private Observation observe(Connection connection, Statement statement) throws SQLException {
        storage.verifyReclamationOwnership();
        boolean own = connection.getAutoCommit();
        Throwable primary = null;
        try {
            if (own) connection.setAutoCommit(false);
            boolean objects = SQLiteStorage.recognizedReclamationObjects(statement);
            long mode = scalar(statement, "PRAGMA main.auto_vacuum");
            long size = scalar(statement, "PRAGMA main.page_size"), pages = scalar(statement, "PRAGMA main.page_count");
            long free = scalar(statement, "PRAGMA main.freelist_count");
            Path file = storage.getDbFile();
            Observation observation = new Observation(mode > Integer.MAX_VALUE ? -1 : (int) mode, size, pages, free,
                    length(file, false), length(Path.of(file + "-wal"), true), length(Path.of(file + "-shm"), true),
                    System.currentTimeMillis(), objects, connection.getMetaData().getDriverVersion(),
                    nativeVersion(statement), runtimeIdentity(connection));
            storage.verifyReclamationOwnership();
            return observation;
        } catch (SQLException | RuntimeException | Error failure) { primary = failure; throw failure;
        } finally {
            if (own) {
                Throwable cleanup = null;
                try { connection.rollback(); } catch (SQLException | RuntimeException | Error failure) { cleanup = failure; }
                // Never turn a failed rollback into a commit-producing auto-commit transition.
                if (cleanup == null) try { connection.setAutoCommit(true); }
                catch (SQLException | RuntimeException | Error failure) { cleanup = failure; }
                if (cleanup != null) {
                    retire(connection, cleanup);
                    if (primary != null) { if (cleanup != primary) primary.addSuppressed(cleanup); }
                    else if (cleanup instanceof SQLException sql) throw sql;
                    else if (cleanup instanceof RuntimeException runtime) throw runtime;
                    else throw (Error) cleanup;
                }
            }
        }
    }

    private void retire(Connection connection, Throwable failure) {
        try { storage.discardFailedConnection(storage.dataSource, connection); }
        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }
    static long scalar(Statement statement, String fixedSql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(fixedSql)) {
            if (!rows.next()) throw new SQLException("Missing fixed SQLite observation");
            long value = rows.getLong(1);
            if (rows.wasNull() || value < 0 || rows.next()) throw new SQLException("Invalid fixed SQLite observation");
            return value;
        }
    }
    private static int exactInt(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull() || value < 0 || value > Integer.MAX_VALUE) throw new SQLException("Invalid checkpoint field");
        return (int) value;
    }
    private static long length(Path path, boolean absentAllowed) throws SQLException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unexpected owned file member");
            return attributes.size();
        } catch (java.nio.file.NoSuchFileException absent) {
            if (absentAllowed) return 0;
            throw new SQLException("Owned database is missing", absent);
        } catch (IOException | SecurityException unavailable) { throw new SQLException("Owned file size unavailable", unavailable); }
    }
    private static long elapsed(long started) { return Math.max(0, System.nanoTime() - started); }
    private static UnitResult withheld(State state, String reason, Observation observation) {
        return new UnitResult(state, reason, 0, observation, observation, 0, 0, 0, 0, 0);
    }
    private static CheckpointResult checkpointWithheld(State state, String reason, Observation observation) {
        return new CheckpointResult(state, reason, -1, -1, -1, observation, observation, 0, 0);
    }
}
