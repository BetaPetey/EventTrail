package io.github.betapetey.eventtrail.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.ProductionAuditPayloadCodec;
import io.github.betapetey.eventtrail.commands.EventTrailQueryDispatcher;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/** Default zero-configuration local file backend. */
public class SQLiteStorage extends AbstractJdbcStorage {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final int SCHEMA_LOCK_RETRY_ATTEMPTS = 20;
    private static final long SCHEMA_LOCK_RETRY_DELAY_MILLIS = 50L;
    private static final java.util.Set<String> RECLAMATION_OBJECTS = java.util.Set.of(
            "table:logs:logs", "table:eventtrail_schema_version:eventtrail_schema_version",
            "table:sqlite_sequence:sqlite_sequence", "table:sqlite_stat1:sqlite_stat1", "table:sqlite_stat4:sqlite_stat4",
            "index:sqlite_autoindex_eventtrail_schema_version_1:eventtrail_schema_version",
            "index:idx_pos:logs", "index:idx_player:logs", "index:idx_time:logs", "index:idx_world_time:logs",
            "index:idx_action_time:logs", "index:idx_player_name_time:logs");
    private static final List<IndexSpec> REQUIRED_INDEXES = List.of(
            indexSpec("idx_pos", "world", "pos_x", "pos_y", "pos_z"),
            indexSpec("idx_player", "player_uuid"),
            indexSpec("idx_time", "timestamp"),
            indexSpec("idx_world_time", "world", "timestamp"),
            indexSpec("idx_action_time", "action_type", "timestamp"),
            indexSpec("idx_player_name_time", "player_name", "timestamp"));
    private final Path dbFile;
    private final boolean coordinateMutations;
    private final boolean initializeNewFileReclamation;
    private final SQLiteReclamationOperations reclamationOperations = new SQLiteReclamationOperations(this);
    private volatile SQLiteReclamationController reclamationController;
    private volatile boolean reclamationInitializationFailed;
    private volatile EventTrailConfig.SQLiteSynchronousMode synchronousMode;
    private volatile SQLiteFileOwnership fileOwnership;
    private volatile SQLiteMutationCoordinator mutations;
    private volatile StorageMaintenanceSnapshot maintenanceObservation =
            StorageMaintenanceSnapshot.unavailable(StorageMaintenanceSnapshot.Backend.SQLITE, 0L);

    public SQLiteStorage(Path eventTrailDirectory) {
        this(eventTrailDirectory, true);
    }

    /** Test-only fixed-core comparison arm; not a configuration or runtime option. */
    SQLiteStorage(Path eventTrailDirectory, boolean coordinateMutations) {
        this(eventTrailDirectory, coordinateMutations, true);
    }

    /** Explicit layout fixtures only; production always prepares positively new qualified files. */
    SQLiteStorage(Path eventTrailDirectory, boolean coordinateMutations, boolean initializeNewFileReclamation) {
        super(ProductionAuditPayloadCodec.instance());
        if (eventTrailDirectory == null) {
            throw new IllegalArgumentException("EventTrail storage directory is required");
        }
        this.dbFile = eventTrailDirectory.toAbsolutePath().normalize().resolve("eventtrail.db");
        this.coordinateMutations = coordinateMutations;
        this.initializeNewFileReclamation = initializeNewFileReclamation;
    }

    public Path getDbFile() {
        return ownedJdbcPath();
    }

    private Path ownedJdbcPath() {
        SQLiteFileOwnership owner = fileOwnership;
        return owner == null ? dbFile : owner.databasePath();
    }

    boolean mutationCoordinationEnabled() { return coordinateMutations; }

    SQLiteFileOwnership fileOwnership() { return fileOwnership; }
    SQLiteReclamationOperations reclamationOperations() { return reclamationOperations; }

    void verifyReclamationOwnership() throws SQLException {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator == null || !coordinateMutations) throw new SQLException("Coordinated SQLite ownership unavailable");
        coordinator.verifyOwnership();
    }

    <T> T ownedReclamationWork(MaintenanceOwnershipWork<T> work) throws SQLException {
        return withMaintenanceOwnership(work);
    }

    @Override public StorageMaintenanceSnapshot maintenanceSnapshot() {
        SQLiteReclamationController controller = reclamationController;
        return controller == null ? maintenanceObservation : controller.snapshot();
    }

    @Override public void afterRetentionMaintenance(long cutoff, RetentionResult result,
                                                   java.util.function.BooleanSupplier idleAdmission) {
        SQLiteReclamationController controller = reclamationController;
        if (controller == null) { refreshMaintenanceSnapshot(idleAdmission); return; }
        long started = System.nanoTime();
        try {
            ownedReclamationWork(() -> { controller.afterRetention(cutoff, result, idleAdmission); return null; });
        } catch (SQLException | RuntimeException unavailable) {
            LOGGER.warn("EventTrail SQLite optional reclamation could not be admitted/settled ({})",
                    EventTrailDiagnostics.safeFailureSummary(unavailable));
        } finally {
            controller.recordSettledSliceNanos(Math.max(0, System.nanoTime() - started));
        }
    }

    /** Idle-admitted background inspection only; operation admission remains separate. */
    @Override public void refreshMaintenanceSnapshot(java.util.function.BooleanSupplier idleAdmission) {
        java.util.Objects.requireNonNull(idleAdmission, "idleAdmission");
        if (!maintenanceObservationAdmitted(idleAdmission)) return;
        try {
            SQLiteReclamationController controller = reclamationController;
            if (controller != null) {
                controller.updateObservation(reclamationOperations.observe(idleAdmission));
                return;
            }
            StorageMaintenanceSnapshot inspected = withCoordinatedMaintenance(
                    System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100),
                    (connection, statement) -> maintenanceObservationAdmitted(idleAdmission)
                            ? inspectMaintenance(connection, statement) : null);
            // A join, queued investigation or writer while waiting is a skip, not a failed observation.
            if (inspected != null) {
                maintenanceObservation = inspected;
                if (reclamationInitializationFailed) publishReclamationInitializationFailure();
            }
        } catch (SQLException | RuntimeException unavailable) {
            if (!maintenanceObservationAdmitted(idleAdmission)) return;
            maintenanceObservation = StorageMaintenanceSnapshot.unavailable(
                    StorageMaintenanceSnapshot.Backend.SQLITE, System.currentTimeMillis());
            if (reclamationInitializationFailed) publishReclamationInitializationFailure();
        }
    }

    private boolean maintenanceObservationAdmitted(java.util.function.BooleanSupplier idleAdmission) {
        if (!idleAdmission.getAsBoolean() || !isReady() || Thread.currentThread().isInterrupted()) return false;
        var writes = getWriteBufferStats();
        var reads = getReadExecutorStats();
        return writes.acceptingWrites() && writes.pending() == 0 && writes.pendingPayloadBytes() == 0
                && !writes.retrying() && reads.queued() == 0 && reads.activeWorkers() == 0
                && reads.trackedOperations() == 0 && !EventTrailQueryDispatcher.hasOutstandingWork()
                && spoolWaitingActions() == 0;
    }

    private StorageMaintenanceSnapshot inspectMaintenance(Connection connection, Statement statement) throws SQLException {
        boolean ownTransaction = connection.getAutoCommit();
        Throwable primary = null;
        try {
            if (ownTransaction) connection.setAutoCommit(false);
            // Bounded schema metadata establishes the read snapshot; no audit-row/full scan.
            boolean recognizedObjects = recognizedReclamationObjects(statement);
            long mode = maintenancePragma(statement, "auto_vacuum");
            long pageSize = maintenancePragma(statement, "page_size");
            long pages = maintenancePragma(statement, "page_count");
            long free = maintenancePragma(statement, "freelist_count");
            StorageMaintenanceSnapshot.SQLiteMode knownMode = switch (mode <= 2 ? (int) mode : -1) {
                case 0 -> StorageMaintenanceSnapshot.SQLiteMode.NONE;
                case 1 -> StorageMaintenanceSnapshot.SQLiteMode.FULL;
                case 2 -> StorageMaintenanceSnapshot.SQLiteMode.INCREMENTAL;
                default -> StorageMaintenanceSnapshot.SQLiteMode.UNKNOWN;
            };
            var snapshot = StorageMaintenanceSnapshot.sqlite(knownMode, System.currentTimeMillis(), pageSize, pages, free,
                    observedLength(ownedJdbcPath()), observedLength(Path.of(ownedJdbcPath() + "-wal")),
                    observedLength(Path.of(ownedJdbcPath() + "-shm")));
            return recognizedObjects ? snapshot : new StorageMaintenanceSnapshot(snapshot.backend(), snapshot.scope(),
                    snapshot.sqliteMode(), StorageMaintenanceSnapshot.Capability.UNRECOGNIZED_OBJECTS,
                    snapshot.reclamationState(), snapshot.observedAtMillis(), snapshot.observation(), snapshot.allocation(), null);
        } catch (SQLException | RuntimeException | Error failure) { primary = failure; throw failure;
        } finally {
            if (ownTransaction) {
                Throwable cleanupFailure = null;
                try { connection.rollback(); } catch (SQLException | RuntimeException | Error failure) { cleanupFailure = failure; }
                if (cleanupFailure == null) {
                    try { connection.setAutoCommit(true); } catch (SQLException | RuntimeException | Error failure) { cleanupFailure = failure; }
                }
                if (cleanupFailure != null) {
                    try { discardFailedConnection(dataSource, connection); }
                    catch (RuntimeException cleanup) { cleanupFailure.addSuppressed(cleanup); }
                    if (primary != null) { if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure); }
                    else if (cleanupFailure instanceof SQLException sql) throw sql;
                    else if (cleanupFailure instanceof RuntimeException runtime) throw runtime;
                    else throw (Error) cleanupFailure;
                }
            }
        }
    }

    private static long maintenancePragma(Statement statement, String fixedName) throws SQLException {
        try (ResultSet rows = statement.executeQuery("PRAGMA main." + fixedName)) {
            if (!rows.next()) throw new SQLException("SQLite maintenance observation unavailable");
            long value = rows.getLong(1);
            if (rows.wasNull() || value < 0L || rows.next()) throw new SQLException("Invalid SQLite maintenance observation");
            return value;
        }
    }

    private static Long observedLength(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
            return attributes.isRegularFile() && !attributes.isSymbolicLink() ? attributes.size() : null;
        } catch (java.nio.file.NoSuchFileException absent) { return 0L; }
        catch (IOException | SecurityException unavailable) { return null; }
    }

    /** Metadata-only eligibility; it does not widen ordinary schema acceptance or remove foreign objects. */
    static boolean recognizedReclamationObjects(Statement statement) throws SQLException {
        int count = 0; boolean recognized = true;
        try (ResultSet rows = statement.executeQuery("SELECT type,name,tbl_name FROM main.sqlite_master LIMIT 33")) {
            while (rows.next()) {
                count++;
                recognized &= RECLAMATION_OBJECTS.contains(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3));
            }
        }
        return recognized && count > 0 && count <= RECLAMATION_OBJECTS.size();
    }

    @Override
    protected EventTrailFailure backendAccessFailure() {
        SQLiteMutationCoordinator coordinator = mutations;
        return coordinator == null ? null : coordinator.accessFailureSnapshot();
    }

    SQLiteMutationCoordinator.Snapshot mutationSnapshot() {
        SQLiteMutationCoordinator coordinator = mutations;
        return coordinator == null ? null : coordinator.snapshot();
    }

    @Override
    protected void beforePoolCreation() throws Exception {
        synchronousMode = EventTrailConfig.sqliteSynchronousMode();
        SQLiteFileOwnership owner = SQLiteFileOwnership.acquire(dbFile);
        fileOwnership = owner;
        mutations = new SQLiteMutationCoordinator(owner, coordinateMutations, this::storageOperationsQuiescent);
    }

    @Override protected void afterSchemaInitialization() {
        if (!coordinateMutations || !initializeNewFileReclamation) return;
        if (reclamationInitializationFailed) { publishReclamationInitializationFailure(); return; }
        try {
            SQLiteReclamationOperations.Observation baseline;
            try (MutationScope mutation = beginMutation(MutationKind.SCHEMA, Long.MAX_VALUE);
                 Connection connection = dataSource.getConnection()) {
                mutation.enterNative();
                configureConnection(connection);
                baseline = reclamationOperations.observeInitial(connection);
            }
            maintenanceObservation = baseline.snapshot();
            SQLiteFileOwnership owner = fileOwnership;
            SQLiteReclamationController controller = new SQLiteReclamationController(this,
                    new SQLiteReclamationStateFile(owner), owner.stableBinding());
            // State publication is outside the SQLite native interval, still owned by initialization.
            controller.initialize(baseline);
            reclamationController = controller;
        } catch (IOException | SQLException | RuntimeException unavailable) {
            reclamationInitializationFailed = true;
            publishReclamationInitializationFailure();
            LOGGER.warn("EventTrail SQLite automatic reclamation initialization unavailable; normal storage and retention remain enabled ({})",
                    EventTrailDiagnostics.safeFailureSummary(unavailable));
        }
    }

    private void publishReclamationInitializationFailure() {
        StorageMaintenanceSnapshot base = maintenanceObservation;
        maintenanceObservation = new StorageMaintenanceSnapshot(base.backend(), base.scope(), base.sqliteMode(),
                base.capability(), StorageMaintenanceSnapshot.State.FAILED, base.observedAtMillis(),
                base.observation(), base.allocation(), null,
                new StorageMaintenanceSnapshot.PolicyStatus(null, null, "UNAVAILABLE", null, null, null, null,
                        null, null, SQLiteReclamationOperations.MAX_PAGES, null, null, null, null,
                        "RECLAMATION_INITIALIZATION_UNAVAILABLE"));
    }

    @Override
    protected void configurePoolLifecycle(HikariConfig config) {
        if (config.getConnectionInitSql() != null || config.getConnectionTestQuery() != null
                || config.getDataSourceClassName() != null || config.getDataSourceJNDI() != null) {
            throw new IllegalArgumentException("SQLite ownership requires the EventTrail physical factory without " +
                    "Hikari initialization/test SQL, datasource class or JNDI overrides");
        }
        SQLiteMutationCoordinator coordinator = mutations;
        DataSource original = config.getDataSource();
        SQLiteMutationCoordinator.ConnectionFactory factory = original == null
                ? () -> DriverManager.getConnection("jdbc:sqlite:" + ownedJdbcPath())
                : original::getConnection;
        config.setDataSource(coordinator.dataSource(factory, this::configurePhysicalConnection));
    }

    @Override
    protected MutationScope beginMutation(MutationKind kind, long deadlineNanos) throws SQLException {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator == null) throw new SQLException("SQLite physical ownership is not initialized", "08003");
        return coordinator.begin(deadlineNanos);
    }

    @Override
    protected void beforeDataSourceClose() {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator != null) coordinator.seal();
    }

    @Override
    protected void closeBackendResources() {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator == null) return;
        coordinator.requestRelease();
        SQLiteMutationCoordinator.Snapshot snapshot = coordinator.snapshot();
        if (!snapshot.ownershipReleased()) {
            LOGGER.warn("EventTrail SQLite file ownership remains retained: mutation scopes={}, physical handles={}, " +
                            "creations={}, retirement helpers={}, poisoned={}. A successor cannot open this file until actual native quiescence is established.",
                    snapshot.mutationScopes(), snapshot.physicalConnections(), snapshot.creations(),
                    snapshot.retirementHelpers(), snapshot.poisoned());
        }
    }

    @Override
    protected void backendOperationFinished() {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator != null) coordinator.attemptRelease();
    }

    @Override
    protected int poolSize() {
        // A small pool is enough for a single SQLite file. WAL permits readers
        // alongside the single queued writer without creating excessive handles.
        return 3;
    }

    @Override
    protected HikariConfig createPoolConfig() {
        try {
            SQLiteFileOwnership owner = fileOwnership;
            requirePoolPath(ownedJdbcPath(), owner == null ? null : owner::verifyCurrent);
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize SQLite JDBC", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + ownedJdbcPath());
        config.setDriverClassName("org.sqlite.JDBC");
        return config;
    }

    @FunctionalInterface interface OwnedPathVerification { void verify() throws IOException; }

    static void requirePoolPath(Path database, OwnedPathVerification retained) throws IOException {
        // Standalone configuration inspection retains its original behavior.
        // Production has an owner: never recreate a lost directory authority.
        if (retained == null) requireSafeStoragePath(database);
        else retained.verify();
    }

    @Override
    protected void configureConnection(Connection connection) throws SQLException {
        SQLiteMutationCoordinator coordinator = mutations;
        if (coordinator != null) coordinator.verifyOwnership();
        // Existing idle borrowers must not wait for another connection's native
        // mutation. Older Xerial setQueryTimeout persistently changes the
        // timeout property and native handler; its getter may remain a stale
        // constructor snapshot. Restore the effective connection-local policy
        // while the logical borrow is still exclusively
        // owned here; this does not write/checkpoint the database or take either
        // mutation gate. Factory setup owns immutable synchronous/temp settings.
        restoreBusyTimeout(connection);
        verifyConnectionSettings(connection);
    }

    @Override
    protected void discardFailedConnection(HikariDataSource source, Connection connection) {
        // A partially configured handle is not a usable captured-policy handle.
        // Eviction stays Hikari-owned and its physical close remains tracked.
        if (mutations != null) source.evictConnection(connection);
    }

    /**
     * SQLite discards an uncertain write connection by eviction only, as the setup-failure discard above: no abort
     * and no physical close of its own (the physical close runs under the native gate on HikariCP's closer, or
     * inline on the writer thread, which already holds that re-entrant gate). A connection that is not a HikariCP
     * proxy, or a storage without physical ownership, is never counted as discarded: the batch fails closed and
     * nothing is added to the failure (writer-01 2.2 step 4).
     */
    @Override
    protected boolean discardUncertainWriteConnection(Connection connection, SQLException failure) {
        if (connection == null) return true;
        try {
            if (mutations == null || !takesPoolBranch(connection)) return false;
            evictFromPool(connection);
            return true;
        } catch (SQLException | RuntimeException evictFailure) {
            addSuppressedFailure(failure, evictFailure);
            return false;
        }
    }

    /**
     * SQLite's own database-wide refusals, compared only on SQLite: result code 13 SQLITE_FULL and 8
     * SQLITE_READONLY (writer-01 2.7). Replaces the MySQL/PostgreSQL list, so their vendor numbers are never
     * compared with SQLite result codes.
     */
    @Override
    protected boolean isDatabaseWideWriteRefusal(SQLException failure) {
        int code = failure.getErrorCode();
        return code == 13 || code == 8;
    }

    /**
     * SQLite's busy/locked text, compared only on SQLite (writer-01 review-01 MINOR-1). The SQLite driver's
     * messages are SQLite's own error text (plus a batch entry index for a batch) and carry no bound values, so
     * this text cannot come from row data. Result codes 5 SQLITE_BUSY and 6 SQLITE_LOCKED are already shared
     * checks. Replaces the MySQL vendor numbers, which are not SQLite result codes.
     */
    /** MINOR-11 (r2): SQLITE_BUSY (5) and SQLITE_LOCKED (6), with their extended result codes. */
    @Override
    protected boolean isSpoolBusyResultCode(int code) {
        return (code & 0xFF) == 5 || (code & 0xFF) == 6;
    }

    @Override
    protected boolean isLockContentionWriteFailure(SQLException failure) {
        String message = failure.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("database is locked") || lower.contains("database is busy");
    }

    @Override
    protected void abortWriteConnection(Connection connection) {
        try {
            // The owned physical wrapper always dispatches off this caller,
            // even when JDBC is supplied a direct executor. Do not then run
            // Hikari's logical rollback/close concurrently with its borrower.
            connection.abort(Runnable::run);
        } catch (Throwable failure) {
            SQLiteMutationCoordinator coordinator = mutations;
            if (coordinator != null) coordinator.poison(failure);
            LOGGER.warn("EventTrail SQLite physical retirement could not be dispatched; file ownership remains retained ({})",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    private void configurePhysicalConnection(Connection connection) throws SQLException {
        // Update the public driver property and native handler. Historical
        // getters may still expose a cached constructor value; native PRAGMA
        // verification, not that getter, establishes the effective setting.
        restoreBusyTimeout(connection);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=" + synchronousMode.name());
            st.execute("PRAGMA temp_store=MEMORY");
        }
        verifyConnectionSettings(connection);
    }

    private static void restoreBusyTimeout(Connection connection) throws SQLException {
        connection.unwrap(SQLiteConnection.class).setBusyTimeout(5000);
    }

    private void verifyConnectionSettings(Connection connection) throws SQLException {
        EventTrailConfig.SQLiteSynchronousMode policy = synchronousMode;
        if (policy == null) policy = EventTrailConfig.sqliteSynchronousMode();
        int expected = policy == EventTrailConfig.SQLiteSynchronousMode.FULL ? 2 : 1;
        try (Statement statement = connection.createStatement()) {
            for (String pragma : List.of("synchronous", "temp_store", "busy_timeout")) {
                int required = pragma.equals("synchronous") ? expected : pragma.equals("temp_store") ? 2 : 5000;
                try (ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
                    if (!result.next() || result.getInt(1) != required) {
                        throw new SQLException("SQLite borrowed connection does not match its captured " + pragma + " policy");
                    }
                }
            }
        }
    }

    @Override
    protected void prepareSchemaConnection(Connection connection) throws SQLException {
        prepareNewFileReclamation(connection);
        for (int attempt = 1; attempt <= SCHEMA_LOCK_RETRY_ATTEMPTS; attempt++) {
            try (Statement st = connection.createStatement()) {
                try (ResultSet journal = st.executeQuery("PRAGMA journal_mode=WAL")) {
                    if (!journal.next() || !"wal".equalsIgnoreCase(journal.getString(1))) {
                        throw new SQLException("SQLite did not establish the required WAL journal mode");
                    }
                }
                try (ResultSet result = st.executeQuery("SELECT sqlite_version()")) {
                    if (!result.next()) throw new SQLException("SQLite runtime did not report a native core version");
                    LOGGER.info("EventTrail SQLite runtime: JDBC={}, native core={}, synchronous={}, mutation coordination={}. " +
                                    "Support requires an EventTrail-owned trusted file with no uncontrolled external writers/checkpointers; " +
                                    "this is not whole-pack security clearance.",
                            connection.getMetaData().getDriverVersion(), result.getString(1),
                            synchronousMode, coordinateMutations);
                }
                return;
            } catch (SQLException failure) {
                if (!isSqliteBusy(failure) || attempt == SCHEMA_LOCK_RETRY_ATTEMPTS) throw failure;
                try {
                    Thread.sleep(SCHEMA_LOCK_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    SQLException interruptedFailure = new SQLException(
                            "Interrupted while waiting for SQLite schema initialization", interrupted);
                    interruptedFailure.addSuppressed(failure);
                    throw interruptedFailure;
                }
            }
        }
    }

    private void prepareNewFileReclamation(Connection connection) {
        SQLiteFileOwnership owner = fileOwnership;
        if (!initializeNewFileReclamation || !coordinateMutations || reclamationInitializationFailed
                || owner == null || !owner.createdDatabase()) return;
        try (Statement statement = connection.createStatement()) {
            owner.verifyCurrent();
            // CREATE_NEW, not an empty sqlite_master alone, establishes permission to prepare the layout.
            try (ResultSet rows = statement.executeQuery("SELECT name FROM main.sqlite_master LIMIT 1")) {
                if (rows.next()) return; // Schema retry: never alter an already established layout.
            }
            if (!SQLiteReclamationOperations.runtimeIdentity(connection)
                    || !SQLiteReclamationOperations.qualifiedRuntime(connection.getMetaData().getDriverVersion(),
                    SQLiteReclamationOperations.nativeVersion(statement))
                    || SQLiteReclamationOperations.scalar(statement, "PRAGMA main.page_size") != 4096) return;
            statement.executeUpdate("PRAGMA main.auto_vacuum=INCREMENTAL");
            if (SQLiteReclamationOperations.scalar(statement, "PRAGMA main.auto_vacuum") != 2)
                throw new SQLException("New-file incremental layout readback failed");
            owner.verifyCurrent();
        } catch (IOException | SQLException | RuntimeException unavailable) {
            reclamationInitializationFailed = true;
            LOGGER.warn("EventTrail SQLite new-file reclamation capability unavailable; normal storage remains enabled ({})",
                    EventTrailDiagnostics.safeFailureSummary(unavailable));
        }
    }

    private static boolean isSqliteBusy(SQLException failure) {
        return failure.getErrorCode() == 5 || failure.getErrorCode() == 6;
    }

    enum CheckpointMode { PASSIVE, FULL, RESTART, TRUNCATE }

    record CheckpointResult(int busy, int logFrames, int checkpointedFrames) {}

    final CheckpointResult checkpoint(CheckpointMode mode) throws SQLException {
        if (mode == null) throw new IllegalArgumentException("SQLite checkpoint mode is required");
        int[] result = executeCoordinatedCheckpoint("PRAGMA wal_checkpoint(" + mode.name() + ")");
        return new CheckpointResult(result[0], result[1], result[2]);
    }

    /** writer-spool design-02 6.1: at synchronous=NORMAL a WAL commit is not synced; deletes wait for a checkpoint. */
    @Override
    protected boolean spoolCommitNeedsCheckpoint() {
        return synchronousMode == EventTrailConfig.SQLiteSynchronousMode.NORMAL;
    }

    /**
     * design-02 6.1 with the Advisor's m-4: a coordinated PASSIVE checkpoint confirms the writer's last commit
     * before the call only when it was not busy, the target is in WAL ({@code logFrames >= 0}) and every frame was
     * copied ({@code checkpointedFrames == logFrames}). Called by the writer between units, never in a transaction.
     */
    @Override
    protected boolean spoolCheckpointConfirms() throws SQLException {
        CheckpointResult result = checkpoint(CheckpointMode.PASSIVE);
        return checkpointConfirms(result);
    }

    static boolean checkpointConfirms(CheckpointResult result) {
        return result.busy() == 0 && result.logFrames() >= 0 && result.checkpointedFrames() == result.logFrames();
    }

    @Override
    protected boolean prepareSchemaRetry(SQLException failure, int failedAttempts)
            throws SQLException {
        if (!isSqliteBusy(failure) || failedAttempts >= SCHEMA_LOCK_RETRY_ATTEMPTS) {
            return false;
        }
        try {
            Thread.sleep(SCHEMA_LOCK_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SQLException interruptedFailure = new SQLException(
                    "Interrupted while retrying SQLite schema initialization", interrupted);
            interruptedFailure.addSuppressed(failure);
            throw interruptedFailure;
        }
    }

    @Override
    protected String createLogTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS logs (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp   INTEGER NOT NULL,
                    action_type TEXT    NOT NULL,
                    player_uuid TEXT    NOT NULL,
                    player_name TEXT    NOT NULL,
                    world       TEXT    NOT NULL,
                    pos_x       INTEGER NOT NULL,
                    pos_y       INTEGER NOT NULL,
                    pos_z       INTEGER NOT NULL,
                    block_state TEXT,
                    extra_data  TEXT
                )
                """;
    }

    @Override
    protected String schemaVersionUpsertSql() {
        return """
                INSERT INTO eventtrail_schema_version (component, version, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(component) DO UPDATE SET
                    version = MAX(eventtrail_schema_version.version, excluded.version),
                    updated_at = CASE
                        WHEN excluded.version > eventtrail_schema_version.version THEN excluded.updated_at
                        ELSE eventtrail_schema_version.updated_at
                    END
                """;
    }

    @Override
    protected String createIndexSql(String indexName, String table, String columns) {
        return "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (" + columns + ")";
    }

    @Override
    protected void ensureSchemaIndexes(Connection connection) throws SQLException {
        ensureIndexes(connection, "logs", REQUIRED_INDEXES);
        LOGGER.info("EventTrail SQLite storage schema is ready");
    }

    @Override
    protected void validateSchemaIndexes(Connection connection) throws SQLException {
        requireIndexes(connection, "logs", REQUIRED_INDEXES);
        try (Statement statement = connection.createStatement()) {
            maintenanceObservation = inspectMaintenance(connection, statement);
        } catch (SQLException | RuntimeException unavailable) {
            // Reporting is subordinate to normal schema/storage correctness.
            maintenanceObservation = StorageMaintenanceSnapshot.unavailable(
                    StorageMaintenanceSnapshot.Backend.SQLITE, System.currentTimeMillis());
        }
    }

    @Override
    protected int consistentReadTransactionIsolation() {
        // Xerial SQLite supports SERIALIZABLE rather than JDBC REPEATABLE_READ.
        return Connection.TRANSACTION_SERIALIZABLE;
    }

    static void requireSafeStoragePath(Path databaseFile) throws IOException {
        Path normalizedDatabase = databaseFile.toAbsolutePath().normalize();
        Path directory = normalizedDatabase.getParent();
        if (directory == null) {
            throw new IOException("EventTrail SQLite path has no storage directory.");
        }
        Path parent = directory.getParent();
        if (parent == null) {
            throw new IOException("EventTrail storage directory has no parent.");
        }
        requireSafeDirectory(parent, null);
        if (Files.notExists(directory, NO_FOLLOW)) {
            Files.createDirectory(directory);
        }
        requireSafeDirectory(directory, parent);
        if (Files.exists(normalizedDatabase, NO_FOLLOW)) {
            requireSafeRegularFile(normalizedDatabase, directory);
        }
    }

    private static void requireSafeDirectory(Path directory, Path expectedParent) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()
                || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException("EventTrail storage path is not a regular non-symbolic directory.");
        }
        if (expectedParent != null) {
            Path actualParent = directory.toRealPath().getParent();
            Path requiredParent = expectedParent.toRealPath();
            if (actualParent == null || !actualParent.equals(requiredParent)) {
                throw new IOException("EventTrail storage directory resolves outside its expected parent.");
            }
        }
    }

    private static void requireSafeRegularFile(Path file, Path expectedParent) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(file) || attributes.isSymbolicLink()
                || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("EventTrail SQLite target is not a regular non-symbolic file.");
        }
        Path actualParent = file.toRealPath().getParent();
        Path requiredParent = expectedParent.toRealPath();
        if (actualParent == null || !actualParent.equals(requiredParent)) {
            throw new IOException("EventTrail SQLite target resolves outside its storage directory.");
        }
    }

    @Override
    protected String tableName() {
        return "logs";
    }

    @Override
    protected boolean usesServerIdColumn() {
        return false;
    }

    @Override
    public String getBackendName() {
        return "SQLite";
    }

    @Override
    protected String containerAccessPhaseComparisonSql() {
        // SQLite LIKE folds ASCII case regardless of a column's BINARY collation.
        return "CAST(SUBSTR(COALESCE(extra_data,''),1,?) AS BLOB) = CAST(? AS BLOB)";
    }

    @Override
    public StorageTransportSecurity getTransportSecurity() {
        return StorageTransportSecurity.local();
    }

}
