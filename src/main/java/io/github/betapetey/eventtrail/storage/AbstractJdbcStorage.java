package io.github.betapetey.eventtrail.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditPayloadCodec;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.SchemaV1Row;
import io.github.betapetey.eventtrail.audit.SchemaV1RowCodec;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPhase;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLRecoverableException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.sql.Types;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared JDBC implementation for EventTrail storage backends.
 *
 * Writes remain asynchronous. Reads use a dedicated executor so command code
 * keeps the same storage API while all three database backends share one query
 * implementation.
 */
public abstract class AbstractJdbcStorage implements AdvancedLogStorage {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final int CURRENT_SCHEMA_VERSION = 1;
    static final String SCHEMA_VERSION_TABLE = "eventtrail_schema_version";
    static final String SCHEMA_COMPONENT = "logs";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final long DATABASE_OUTAGE_LOG_INTERVAL_MS = 60_000L;
    static final int WRITE_BUFFER_CAPACITY = 4096;
    static final long MAX_PENDING_PAYLOAD_BYTES = 32L * 1024L * 1024L;
    static final long MAX_SINGLE_ENTRY_RETAINED_BYTES = 256L * 1024L;
    private static final long MIN_CAPACITY_HINT_BYTES = 512L;
    static final int WRITE_BATCH_SIZE = 100;
    /**
     * Most rows one action may admit through {@link #tryLogAll(List)}: above the 512 transfers of one
     * authoritative transaction with room for chunk rows, and below {@link #WRITE_BUFFER_CAPACITY}, so a group
     * always fits an empty buffer (writer-01 2.5; adapter design-r3 3.3 B).
     */
    static final int MAX_ROWS_PER_ACTION = 1024;
    static final int ADVANCED_SEARCH_NEEDLE_LIMIT = 128;
    private static final long WRITE_QUEUE_POLL_MS = 100L;
    private static final long INITIAL_RETRY_DELAY_MS = 250L;
    private static final long MAX_RETRY_DELAY_MS = 5_000L;
    private static final int MAX_PERMANENT_ERROR_ATTEMPTS = 3;
    private static final long BUFFER_FULL_LOG_INTERVAL_MS = 60_000L;
    private static final long WRITER_ABORT_GRACE_MS = 5_000L;
    static final int READ_WORKERS = 2;
    static final int READ_QUEUE_CAPACITY = 32;
    /**
     * 6 b: a read that has waited this long for a free read worker is given up and never starts later. It equals
     * the command queue's EventTrailQueryDispatcher.REQUEST_DEADLINE_MILLIS (pinned by a test). Public as a
     * read-only value so that the commands-package admission test can compare the two.
     */
    public static final long READ_QUEUE_WAIT_LIMIT_MILLIS = 30_000L;
    private static final long READ_QUEUE_WAIT_NOTICE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long READ_SHUTDOWN_TIMEOUT_SECONDS = 2L;
    static final int RETENTION_DELETE_BATCH_SIZE = 1_000;
    static final long RETENTION_WORK_BUDGET_MILLIS = 30_000L;
    private static final long RETENTION_BATCH_PAUSE_MILLIS = 10L;

    protected final String serverId;
    private final SchemaV1RowCodec rowCodec;
    /** The read path's payload codec; the exact-name check decodes candidate rows with it. */
    private final AuditPayloadCodec payloadCodec;
    protected volatile boolean ready;
    protected HikariDataSource dataSource;

    private final ThreadPoolExecutor readExecutor = new ThreadPoolExecutor(
            READ_WORKERS,
            READ_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(READ_QUEUE_CAPACITY),
            threadFactory("EventTrail-DB-Read-"),
            new ThreadPoolExecutor.AbortPolicy());
    private final Object readLifecycleLock = new Object();
    private final Set<ReadOperation> activeReadOperations = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<ReadOperation> currentReadOperation = new ThreadLocal<>();
    private volatile boolean acceptingReads;
    // 6 b: at most one "waited" WARN every 60 s, carrying the number of reads given up since the last one.
    private final Object readQueueWaitNoticeLock = new Object();
    private boolean readQueueWaitNoticeWritten;
    private long readQueueWaitNoticeNanos;
    private long readQueueWaitGivenUpSinceNotice;
    private long readQueueWaitNoticesWritten;

    /*
     * Count slots and retained-payload reservations cover every accepted write,
     * including the batch currently held by the writer. The queue alone would
     * not cover that in-flight batch after it has been drained for a retry.
     * One queue element is one admitted action (a group of 1 to MAX_ROWS_PER_ACTION rows); every element holds
     * at least one of the row permits, so the queue cannot fill before the permits run out.
     */
    private final ArrayBlockingQueue<PendingGroup> writeQueue = new ArrayBlockingQueue<>(WRITE_BUFFER_CAPACITY);
    private final Semaphore pendingWriteSlots = new Semaphore(WRITE_BUFFER_CAPACITY);
    private final Object writeLifecycleLock = new Object();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicLong pendingPayloadBytes = new AtomicLong();
    private final AtomicLong acceptedWrites = new AtomicLong();
    private final AtomicLong successfulWrites = new AtomicLong();
    private final AtomicLong rejectedFullWrites = new AtomicLong();
    private final AtomicLong rejectedPayloadWrites = new AtomicLong();
    private final AtomicLong rejectedOversizedWrites = new AtomicLong();
    private final AtomicLong rejectedClosedWrites = new AtomicLong();
    private final AtomicLong permanentFailedWrites = new AtomicLong();
    private final AtomicLong shutdownLostWrites = new AtomicLong();
    private final AtomicLong successfulWriteBatches = new AtomicLong();
    private final AtomicLong bufferFullErrorLogs = new AtomicLong();
    private final AtomicBoolean retryingWrites = new AtomicBoolean();
    private volatile boolean acceptingWrites;
    private long writerStateVersion;
    private boolean writerTerminated;
    private EventTrailFailure writerFailure;
    private volatile EventTrailFailure backendRestriction;
    private volatile EventTrailFailure initializationFailure;
    private volatile EventTrailFailure.Backend failureBackend = EventTrailFailure.Backend.STORAGE;
    private WriteAdmissionSnapshot latestWriterObservation = WriteAdmissionSnapshot.unknown();
    private final CopyOnWriteArrayList<Consumer<WriteAdmissionSnapshot>> writerObservers =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean observerFailureReported = new AtomicBoolean();
    private final AtomicLong lastPublishedWriterVersion = new AtomicLong(-1L);
    private final AtomicReference<List<EventTrailFailure>> cleanupFailureFacts =
            new AtomicReference<>(List.of());
    private volatile boolean writeShutdownRequested;
    private volatile boolean forceWriterStop;
    private volatile boolean closeStarted;
    private volatile long writerShutdownDeadlineNanos = Long.MAX_VALUE;
    private volatile Thread writerThread;
    private volatile Connection activeWriteConnection;
    private final WriteOperation activeWriteOperation = new WriteOperation();
    private final Set<AuxiliaryMutationOperation> auxiliaryMutations = ConcurrentHashMap.newKeySet();
    private volatile boolean initializing;
    private boolean initializationStarted;
    private volatile boolean writerNativeWorkAcknowledged;
    /** S2 (writer-spool design-02 6.8): false while the active unit's last attempt may have reached the database. */
    private boolean activeUnitProvenAbsent = true;

    // ---- disk spool (writer-spool design-02); null fields = no spool, today's behaviour byte for byte ----
    /** design-02 5.1 step 2 / L13 and 5.2: the bounded waits for the spool thread. */
    static final long SPOOL_CALL_MILLIS = 5_000L;
    private static final long SPOOL_PAUSE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long SPOOL_SETTLE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long SPOOL_CHECKPOINT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** design-01 7.1: after this many memory units in a row while frames wait, one spool unit. */
    static final int SPOOL_STARVATION_MEMORY_UNITS = 8;
    /** design-02 6.6 (M-4): the age test's margin beyond retention.days. */
    static final long SPOOL_EXPIRY_MARGIN_MILLIS = 3_600_000L;
    private volatile Path spoolBaseDirectory;
    private volatile DiskSpool.Settings spoolSettingsOverride;
    private volatile DiskSpool spool;
    private volatile DiskSpool.OpenOutcome spoolOpenOutcome;
    // Writer-thread replay state (and init before the writer starts).
    private long spoolKnownPosition = -1L;
    private long spoolCommittedPosition = -1L;
    private long spoolCheckpointedPosition = -1L;
    private final Set<Long> spoolOwnInDoubtEnds = new HashSet<>();
    /** MINOR-2 (r2): each in-doubt attempt's expired frames, by its end, for an own late commit that landed. */
    private final Map<Long, Set<Long>> spoolOwnInDoubtExpired = new HashMap<>();
    /** R-16 (r9): spool attempts whose commit was sent and then reported a failure (in-doubt ends), for tests. */
    final AtomicLong spoolInDoubtEnds = new AtomicLong();
    /** R-16 (r9): in-doubt ends the re-read found landed (resolved as OWN_COMMIT_LANDED), for tests. */
    final AtomicLong spoolOwnCommitLanded = new AtomicLong();
    private final Set<Long> spoolKnownUnreadable = new HashSet<>();
    private int memoryUnitsWhileSpoolWaits;
    /**
     * MAJOR-1 (design-01 7.1 / 2.1): memory groups now pending that were admitted while the latch was set (the order
     * exception row, and tryLogAllWhenRoom parts admitted while latched). Only while this is above 0, and (N-3, r3)
     * the group at the head of the queue is itself one of them, does the starvation bound let a spool unit go ahead
     * of memory; otherwise memory (the older actions) drains first.
     */
    final java.util.concurrent.atomic.AtomicInteger pendingAdmittedPastGroups = new java.util.concurrent.atomic.AtomicInteger();
    private long spoolRetryNotBeforeNanos;
    private long spoolSettleRetryNotBeforeNanos;
    /** The held sets/actions/unknown-sets the last held line reported (init thread, then the writer thread). */
    private volatile String spoolLoggedHeld = "0/0/0";
    private long spoolLastCheckpointNanos;
    private volatile boolean spoolSessionLimitRefused;
    private boolean spoolSessionLimitWarned;
    /** R-10 (r9): a connection failure while the session limit was being set was warned once (writer thread). */
    private boolean spoolSessionLimitConnectionWarned;
    /** MAJOR-4 (r2): the database refused the advance's lock bound once; the spool runs without it (writer thread). */
    private volatile boolean spoolAdvanceLockBoundRefused;
    private boolean spoolAdvanceLockBoundWarned;
    /** N-2 (r3): a connection failure while the bound was being set was warned once (writer thread). */
    private boolean spoolAdvanceLockBoundConnectionWarned;
    /** N-2 (r3): how long the connection check after a failed bound may take. */
    private static final int SPOOL_BOUND_VALID_CHECK_SECONDS = 2;
    private boolean spoolExpiredWarned;

    private final Object writePressureLogLock = new Object();
    private boolean bufferSaturated;
    private final Set<OperationalDiagnostic> diagnosticClaims = EnumSet.noneOf(OperationalDiagnostic.class);

    private final Object outageLogLock = new Object();
    private long outageStartedAt = -1L;
    private int suppressedOutageErrors;

    private final Object writeRetryLogLock = new Object();
    private long writeRetryStartedAt = -1L;
    /** M-4: the clock at the latest retryable failure of the current stall; guarded by {@code writeRetryLogLock}. */
    private long writeRetryLastSeenAt;
    private int suppressedWriteRetryErrors;

    private enum OperationalDiagnostic {
        CONNECTION_OUTAGE, CONNECTION_AVAILABLE, WRITE_RETRY, WRITE_PROGRESS,
        PRESSURE_COUNT, PRESSURE_PAYLOAD, PRESSURE_OVERSIZED, PRESSURE_ACTION_ROWS, PRESSURE_ACTION_PAYLOAD,
        BOUNDED_BATCH_RETRY,
        /**
         * Claimed by the first warning of a lasting retry stall and released when a commit ends it (writer-01 2.8);
         * the release prints the recovery line. The warning itself repeats every 5 minutes of the stall (af-1 F8).
         */
        WRITE_STALL
    }

    /**
     * A retry stall this long without a commit gets its own warning, repeated after every further period of this
     * length while the same stall lasts (writer-01 2.8; af-1 F8).
     */
    static final long WRITE_STALL_WARN_MILLIS = 300_000L;
    /** Full stall periods already warned for in the current stall; guarded by {@code writeRetryLogLock} (af-1 F8). */
    private long stallWarningsThisStall;

    /**
     * af-1 F7: every batch the writer discards without commit confirmation (an in-doubt commit, an unproven
     * connection discard, or 3 bounded tries) is reported by an ERROR line, at most one per this interval; the
     * line carries every drop since the previous line, and a pending count is flushed when the writer ends.
     */
    static final long DROPPED_BATCH_LOG_INTERVAL_MILLIS = 60_000L;
    /** Distinct failure diagnostics one drop line names; batches with any further one are only counted. */
    private static final int MAX_DROP_LINE_DIAGNOSTICS = 4;

    /** Why the writer discarded a batch without commit confirmation (af-1 F7). */
    private enum DropReason {
        COMMIT_OUTCOME_UNKNOWN("commit outcome unknown"),
        CONNECTION_NOT_PROVEN_DISCARDED("uncertain connection not proven discarded"),
        BOUNDED_TRIES_USED("refused " + MAX_PERMANENT_ERROR_ATTEMPTS + " times on the bounded path");

        private final String text;

        DropReason(String text) {
            this.text = text;
        }
    }

    /** af-1 F7: the drops not yet reported by an ERROR line. Every field is guarded by the instance itself. */
    private static final class DroppedBatchLog {
        private boolean lineWritten;
        private long lastLineMillis;
        private long rows;
        private long batches;
        private final long[] batchesByReason = new long[DropReason.values().length];
        private final LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        /** Batches whose diagnostic is not among the named ones (the list is full). */
        private long diagnosticsOmitted;
        /** The attempts of the latest drop: the one-batch line keeps the original text with them. */
        private int lastAttempts;
    }

    /** One drop line's figures, taken under the {@link DroppedBatchLog} lock and logged outside it. */
    private record DroppedBatchLine(long rows, long batches, long[] batchesByReason, List<String> diagnostics,
                                    long diagnosticsOmitted, int lastAttempts) {}

    private final DroppedBatchLog droppedBatchLog = new DroppedBatchLog();

    private boolean claimDiagnostic(OperationalDiagnostic diagnostic) {
        synchronized (diagnosticClaims) { return diagnosticClaims.add(diagnostic); }
    }

    private boolean releaseDiagnostic(OperationalDiagnostic diagnostic) {
        synchronized (diagnosticClaims) { return diagnosticClaims.remove(diagnostic); }
    }

    /** Seam: wall-clock time for write diagnostics, so a unit test can pass the stall threshold without waiting. */
    long diagnosticNowMillis() {
        return System.currentTimeMillis();
    }

    /** Creates local storage, which has no persisted server-isolation key. */
    protected AbstractJdbcStorage(AuditPayloadCodec payloadCodec) {
        this.serverId = "";
        this.rowCodec = new SchemaV1RowCodec(payloadCodec);
        this.payloadCodec = payloadCodec;
    }

    /** Creates external storage with an explicit validated server-isolation key. */
    protected AbstractJdbcStorage(String serverId, AuditPayloadCodec payloadCodec) {
        this.serverId = EventTrailConfig.normalizeServerIdForBackend("mysql", serverId);
        this.rowCodec = new SchemaV1RowCodec(payloadCodec);
        this.payloadCodec = payloadCodec;
    }

    private static ThreadFactory threadFactory(String prefix) {
        ThreadFactory namedFactory = r -> {
            Thread t = new Thread(r, prefix + THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return EventTrailJdbcLogScope.wrap(namedFactory);
    }

    @Override
    public final void init() throws Exception {
        try (EventTrailJdbcLogScope.Scope ignored = EventTrailJdbcLogScope.open()) {
            initScoped();
        }
    }

    private void initScoped() throws Exception {
        // Resolve the virtual backend label before writer/cancellation ownership.
        failureBackend = EventTrailFailure.Backend.fromLabel(getBackendName());
        synchronized (writeLifecycleLock) {
            if (initializationStarted || closeStarted) {
                throw new IllegalStateException("EventTrail storage cannot be initialized twice or after close");
            }
            initializationStarted = true;
            initializing = true;
            writerStateVersion++;
        }
        publishWriteObservation();
        EventTrailJdbcLogScope.installSensitiveJdbcFilter();
        LOGGER.info("Initializing {} storage (server id: {})...", getBackendName(),
                EventTrailDiagnostics.sanitizeLogValue(serverId, EventTrailConfig.MAX_EXTERNAL_SERVER_ID_LENGTH));
        EventTrailFailure.Phase failurePhase = EventTrailFailure.Phase.STORAGE_INITIALIZATION;
        try {
            beforePoolCreation();
            if (closeStarted) throw new SQLException("EventTrail storage stopped during initialization", "08003");
            HikariConfig config = createPoolConfig();
            configurePoolLifecycle(config);
            config.setThreadFactory(threadFactory(
                    "EventTrail-Hikari-" + getBackendName().replace(' ', '-').replace('/', '-') + "-"));
            config.setPoolName("EventTrail-" + getBackendName().replace(' ', '-'));
            config.setMaximumPoolSize(Math.max(1, poolSize()));
            config.setMinimumIdle(1);
            config.setConnectionTimeout(EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get());
            config.setValidationTimeout(Math.min(5000L, EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get()));
            config.setAutoCommit(true);
            config.setInitializationFailTimeout(EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get());

            dataSource = new HikariDataSource(config);
            failurePhase = EventTrailFailure.Phase.SCHEMA_INITIALIZATION;
            try (MutationScope mutation = beginMutation(MutationKind.SCHEMA, Long.MAX_VALUE);
                 Connection connection = dataSource.getConnection()) {
                mutation.enterNative();
                configureConnection(connection);
                SchemaState schemaState = establishSchema(connection);
                logSchemaState(schemaState);
            }
            afterSchemaInitialization();
            verifySearchTextSql();
            // writer-spool design-02 5.1: after the schema is validated, before acceptingWrites (never fails the
            // start except for a real target connection failure, L4/S-8).
            openAndSettleSpool();

            failurePhase = EventTrailFailure.Phase.STORAGE_INITIALIZATION;
            synchronized (readLifecycleLock) {
                if (readExecutor.isShutdown()) {
                    throw new IllegalStateException(
                            "A closed EventTrail storage instance cannot be initialized again");
                }
                acceptingReads = true;
            }
            synchronized (writeLifecycleLock) {
                if (closeStarted) throw new SQLException("EventTrail storage stopped during initialization", "08003");
                acceptingWrites = true;
                ready = true;
                writerStateVersion++;
                writerThread = threadFactory("EventTrail-DB-Write-").newThread(this::runWriter);
                DiskSpool openSpool = spool;
                if (openSpool != null) openSpool.bindWriterThread(writerThread);
                writerThread.start();
            }
            publishWriteObservation();
        } catch (Exception | Error e) {
            // design-02 5.1 step 7: a spool opened before the failure accepted nothing; closed with the clean mark.
            DiskSpool openSpool = spool;
            if (openSpool != null) openSpool.close(true, SPOOL_CALL_MILLIS);
            initializationFailure = EventTrailFailure.from(e, failurePhase, failureBackend);
            synchronized (writeLifecycleLock) { writerStateVersion++; }
            initializing = false;
            publishWriteObservation();
            closeDataSourceQuietly();
            throw e;
        } finally {
            initializing = false;
            backendOperationFinished();
        }
        LOGGER.info("{} investigation storage initialization finished; recording availability is tracked separately",
                getBackendName());
    }

    protected int poolSize() {
        return EventTrailConfig.DATABASE_POOL_SIZE.get();
    }

    protected abstract HikariConfig createPoolConfig();

    /** SQLite acquires file-lifetime ownership before any pool or schema access. */
    protected void beforePoolCreation() throws Exception {}

    /** Optional backend initialization after schema/native settlement, before runtime admission. */
    protected void afterSchemaInitialization() throws Exception {}

    /** Pure cached backend policy fact; implementations must not probe or invoke listeners. */
    protected EventTrailFailure backendAccessFailure() { return null; }

    private EventTrailFailure refreshBackendRestriction() {
        EventTrailFailure observed = backendAccessFailure();
        if (observed != null && backendRestriction == null) {
            synchronized (writeLifecycleLock) {
                if (backendRestriction == null) {
                    backendRestriction = observed;
                    acceptingWrites = false;
                    writerStateVersion++;
                }
            }
        }
        return backendRestriction;
    }

    /** Wrap a test/custom physical factory without bypassing backend ownership. */
    protected void configurePoolLifecycle(HikariConfig config) throws Exception {}

    protected enum MutationKind { SCHEMA, AUDIT, DIRECT_INSERT, RETENTION, CHECKPOINT }

    /** Admission precedes borrowing; native ownership begins only after the borrow. */
    protected interface MutationScope extends AutoCloseable {
        MutationScope NONE = new MutationScope() {};
        default void enterNative() throws SQLException {}
        @Override default void close() {}
    }

    protected MutationScope beginMutation(MutationKind kind, long deadlineNanos) throws SQLException {
        return MutationScope.NONE;
    }

    /** Must not hold a gate needed by Hikari's creation/close callbacks while joining it. */
    protected void beforeDataSourceClose() throws Exception {}

    protected void backendOperationFinished() {}

    /** A canceled Future is not an acknowledgement from its still-running worker. */
    protected final boolean storageOperationsQuiescent() {
        Thread writer = writerThread;
        return !initializing && (writer == null || writerNativeWorkAcknowledged)
                && auxiliaryMutations.isEmpty()
                && activeReadOperations.stream().noneMatch(operation ->
                        operation.workerStarted() && !operation.workerFinished());
    }

    /** Releases backend-owned resources after Hikari has stopped using them. */
    protected void closeBackendResources() throws Exception {
        // Most backends own no resources outside Hikari.
    }

    protected void prepareSchemaConnection(Connection connection) throws SQLException {
        // Optional backend setup that must run before the schema transaction.
    }

    protected abstract String createLogTableSql();

    protected abstract String schemaVersionUpsertSql();

    protected String createIndexSql(String indexName, String table, String columns) {
        return "CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")";
    }

    protected abstract void ensureSchemaIndexes(Connection connection) throws SQLException;

    protected abstract void validateSchemaIndexes(Connection connection) throws SQLException;

    protected void validateStorageSchemaContract(Connection connection) throws SQLException {
        // External backends validate their stronger physical storage contract.
    }

    protected abstract String tableName();

    protected abstract boolean usesServerIdColumn();

    /** Two bound values: canonical prefix character count, then the exact ASCII prefix. */
    protected String containerAccessPhaseComparisonSql() {
        throw new IllegalStateException("Storage backend must provide exact container-phase comparison");
    }

    // ------------------------------------------------------------------ T31-R4 search text (SearchFold)

    /** False only after the startup self-test found this backend's search SQL unusable. */
    private volatile boolean searchSqlUsable = true;
    private volatile String searchTextLimitation;

    /**
     * T31-R4. SQL for {@link SearchFold#asciiNeedleView(String)} of a stored text expression, compared exactly
     * with one folded ASCII needle bound through {@link #searchNeedleSql()}. It may repeat the expression. Its
     * lower case must map ASCII letters to their ASCII lower case and must never map a non-ASCII character to
     * ASCII; it may change other non-ASCII characters (MySQL's LOWER under utf8mb4_bin does), because their
     * bytes can never match an ASCII needle. U+0130 and U+212A, the only characters whose simple lower case is
     * ASCII, are replaced before the lower case. The default is SQLite's, whose lower() folds ASCII letters only
     * (recheck-01 NIT-1).
     */
    protected String asciiNeedleFoldSql(String textExpression) {
        return "replace(lower(replace(replace(" + textExpression + ", char(304), 'I'), char(8490), 'K')),"
                + " 'i' || char(775), 'i')";
    }

    /**
     * T31-R4 (fix F-A). SQL for the ASCII lower case of a stored text expression that {@link #nonAsciiTextSql}
     * found to hold only ASCII characters. On such text it must give exactly what {@link #asciiNeedleFoldSql}
     * gives (same characters, same collation and type for the comparison), only more cheaply: the fold's
     * replacements are no-ops there because each replaced sequence holds a non-ASCII character. The default is
     * SQLite's lower(), which folds ASCII letters only (HEAD's form).
     */
    protected String asciiTextLowerSql(String textExpression) {
        return "lower(" + textExpression + ")";
    }

    /** Placeholder for one folded ASCII needle or LIKE pattern compared with {@link #asciiNeedleFoldSql}. */
    protected String searchNeedleSql() {
        return "?";
    }

    /**
     * T31-R4. SQL that is true when a stored text expression holds a non-ASCII character; may repeat it. It
     * must hold no placeholder. Fix F-A also uses it as the guard of {@link #appendAsciiNeedleTerms}: wherever
     * it is false the text must be pure ASCII (it may be true for some ASCII text, which only costs time).
     */
    protected String nonAsciiTextSql(String textExpression) {
        // SQLite: more bytes than characters. A NUL also counts, which only sends the row to the Java check
        // (and, as the F-A guard, to the fold branch).
        return "length(CAST(" + textExpression + " AS BLOB)) <> length(" + textExpression + ")";
    }

    /**
     * T31-R4 fix F-A: the one SQL form in which ASCII needles meet one stored text expression. Lookups
     * ({@link #appendTextPredicate}) and the startup self-test ({@link #searchSelfTestStatement()}) both build
     * it here, so the self-test runs exactly what lookups run. The guard {@link #nonAsciiTextSql} is evaluated
     * once per row: text holding a non-ASCII character is compared through {@link #asciiNeedleFoldSql}, pure
     * ASCII text through the cheaper {@link #asciiTextLowerSql}, which gives the same value there. Appends
     * {@code CASE WHEN guard THEN (fold OP ? OR ...) ELSE (lower OP ? OR ...) END} and binds every needle
     * twice: all needles in order for the THEN branch, then all needles in the same order for the ELSE branch.
     * OP is {@code =} for equality, otherwise {@code LIKE ? ESCAPE '!'} with {@link #literalLikePattern}.
     */
    final void appendAsciiNeedleTerms(StringBuilder sql, List<Object> params, String textExpression,
                                      List<String> asciiNeedles, boolean equality) {
        if (asciiNeedles.isEmpty()) throw new IllegalArgumentException("no ASCII needle");
        sql.append("CASE WHEN ").append(nonAsciiTextSql(textExpression)).append(" THEN (");
        appendNeedleComparisons(sql, asciiNeedleFoldSql(textExpression), asciiNeedles.size(), equality);
        sql.append(") ELSE (");
        appendNeedleComparisons(sql, asciiTextLowerSql(textExpression), asciiNeedles.size(), equality);
        sql.append(") END");
        for (int branch = 0; branch < 2; branch++) {
            for (String needle : asciiNeedles) params.add(equality ? needle : literalLikePattern(needle));
        }
    }

    private void appendNeedleComparisons(StringBuilder sql, String textView, int needles, boolean equality) {
        for (int index = 0; index < needles; index++) {
            if (index > 0) sql.append(" OR ");
            sql.append(textView).append(equality ? " = " : " LIKE ").append(searchNeedleSql());
            if (!equality) sql.append(" ESCAPE '!'");
        }
    }

    /** How the search self-test binds one text value. */
    protected String searchSelfTestTextSql() {
        return "?";
    }

    /**
     * Lane xn (review-01 MINOR-1). SQL that is true when a stored text expression contains one literal
     * {@link #literalLikePattern} pattern, bound through its one placeholder: the {@code name:} prefilter term.
     * It may answer true for more texts than a byte search would (SQLite's LIKE folds ASCII case), never for
     * fewer, because the Java check decides every candidate. Lookups ({@link #appendExactNamePredicate}) and the
     * startup self-test ({@link #exactNameSelfTestStatement()}) both build it here, so the self-test runs the
     * lookup's SQL form; a backend on which that form misses a literal match sends every text filter to the Java
     * check. The self-test runs the form over bound text ({@link #searchSelfTestTextSql()}), not over
     * {@code extra_data}, so it does not exercise a collation the operator set on that column (lane xn r3,
     * re-check NIT-C). That matters on PostgreSQL only: MySQL's form is byte-wise whatever the column's
     * collation; on PostgreSQL a nondeterministic column collation makes LIKE fail loudly before version 18 and
     * match a superset (more candidates only) from 18. The default is the plain LIKE (SQLite; PostgreSQL, whose
     * deterministic collations compare LIKE exactly).
     */
    protected String exactNameLikeSql(String textExpression) {
        return textExpression + " LIKE ? ESCAPE '!'";
    }

    /** Null when lookup text filters run as designed; otherwise why every text filter is checked in Java. */
    public final String searchTextLimitation() {
        return searchTextLimitation;
    }

    /** From now on every text filter is decided by the Java check: still exact, but it reads more rows. */
    final void disableSearchSql(String reason) {
        searchSqlUsable = false;
        searchTextLimitation = "lookup text filters are checked in Java on this " + getBackendName()
                + " database because " + reason;
        LOGGER.warn("EventTrail {}. Lookup results stay exact; text-filtered lookups read more rows.",
                searchTextLimitation);
    }

    /**
     * Fix F-A: positive cases on pure ASCII text, run after {@link SearchFold#SELF_TESTS} (as cases 8 and 9),
     * so the ELSE branch of {@link #appendAsciiNeedleTerms} is proven to lower 'I' and 'K' to ASCII and to
     * match literal '_' and '%' (SELF_TESTS' ASCII texts only expect "no match").
     */
    static final List<SearchFold.SelfTest> ASCII_BRANCH_SELF_TESTS = List.of(
            new SearchFold.SelfTest("PLAIN Item KIT", SearchFold.SelfTestForm.EQUALS, "plain item kit", true),
            new SearchFold.SelfTest("x MINE_ITEM%1 IK y", SearchFold.SelfTestForm.CONTAINS, "mine_item%1 ik", true));
    static final List<SearchFold.SelfTest> FOLD_BRANCH_SELF_TESTS = List.of(
            new SearchFold.SelfTest("\u0130tem ", SearchFold.SelfTestForm.EQUALS, "item", false),
            new SearchFold.SelfTest("\u0130 mineitemX1", SearchFold.SelfTestForm.CONTAINS, "mineitem_1", false));

    /** The startup self-test statement; {@code tests.get(n)} is answered by result column n + 1. */
    record SearchSelfTestStatement(String sql, List<Object> params, List<SearchFold.SelfTest> tests) {
        SearchSelfTestStatement {
            params = Collections.unmodifiableList(new ArrayList<>(params));
            tests = List.copyOf(tests);
        }
    }

    /**
     * Builds the startup self-test over {@link SearchFold#SELF_TESTS} then {@link #ASCII_BRANCH_SELF_TESTS}:
     * EQUALS and CONTAINS cases through {@link #appendAsciiNeedleTerms} (the production form, both branches),
     * NON_ASCII cases through {@link #nonAsciiTextSql} (the production guard and candidate term).
     */
    final SearchSelfTestStatement searchSelfTestStatement() {
        List<SearchFold.SelfTest> tests = new ArrayList<>(SearchFold.SELF_TESTS);
        tests.addAll(ASCII_BRANCH_SELF_TESTS);
        tests.addAll(FOLD_BRANCH_SELF_TESTS);
        StringBuilder sql = new StringBuilder("SELECT ");
        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();
        List<Object> texts = new ArrayList<>();
        for (int index = 0; index < tests.size(); index++) {
            SearchFold.SelfTest test = tests.get(index);
            String text = "v.t" + index;
            if (index > 0) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append("CASE WHEN ");
            switch (test.form()) {
                case EQUALS -> appendAsciiNeedleTerms(sql, params, text, List.of(test.needle()), true);
                case CONTAINS -> appendAsciiNeedleTerms(sql, params, text, List.of(test.needle()), false);
                case NON_ASCII -> sql.append(nonAsciiTextSql(text));
            }
            sql.append(" THEN 1 ELSE 0 END");
            values.append(searchSelfTestTextSql()).append(" AS t").append(index);
            texts.add(test.text());
        }
        sql.append(" FROM (SELECT ").append(values).append(") v");
        params.addAll(texts);
        return new SearchSelfTestStatement(sql.toString(), params, tests);
    }

    /** Lane xn r2: an access-payload SNBT name with both quote kinds, '\', '!', '%', '_' and a 4-byte character. */
    static final String EXACT_NAME_SELF_TEST_SNBT_NAME = "Dogs' \"Loot\" \\ !%_ " + Character.toString(0x1F4E6);
    /** Lane xn r2: a binary-payload name one of whose base64url fragments holds both '_' and '-'. */
    static final String EXACT_NAME_SELF_TEST_BINARY_NAME =
            "Xn Self-Test " + Character.toString(0xFF) + "?" + Character.toString(0xFF) + ">";

    /** Lane xn r2: one exact-name self-test case: a stored text and the literal it contains. */
    record ExactNameSelfTest(String text, String literal) {
        ExactNameSelfTest {
            if (!text.contains(literal)) {
                throw new IllegalArgumentException("An exact-name self-test case must contain its literal");
            }
        }
    }

    /**
     * Lane xn r2 (review-01 MINOR-1): the {@code name:} prefilter's needles as lookups build them
     * ({@link LoggedNameFilter}) inside stored-looking text. Positive cases only: the prefilter is a necessary
     * condition, so a backend that answers "contains" for more texts only costs time, while one that misses a
     * literal match would lose rows. Built on each call (not at class load), so loading this class never needs
     * the Minecraft NBT classes.
     */
    static List<ExactNameSelfTest> exactNameSelfTests() {
        List<ExactNameSelfTest> tests = new ArrayList<>();
        String needle = new LoggedNameFilter(EXACT_NAME_SELF_TEST_SNBT_NAME).accessPayloadNeedle();
        tests.add(new ExactNameSelfTest("{actor:\"Xn\",display:" + needle + ",phase:\"open\"}", needle));
        for (String fragment : LoggedNameFilter.fragmentsOf(EXACT_NAME_SELF_TEST_BINARY_NAME)) {
            tests.add(new ExactNameSelfTest("AbC" + fragment + "Q", fragment));
        }
        return List.copyOf(tests);
    }

    /** The exact-name self-test statement; {@code tests.get(n)} is answered by result column n + 1. */
    record ExactNameSelfTestStatement(String sql, List<Object> params, List<ExactNameSelfTest> tests) {
        ExactNameSelfTestStatement {
            params = Collections.unmodifiableList(new ArrayList<>(params));
            tests = List.copyOf(tests);
        }
    }

    /**
     * Lane xn r2: {@code CASE WHEN} {@link #exactNameLikeSql} {@code THEN 1 ELSE 0 END} per case over the texts
     * bound as {@link #searchSelfTestTextSql()}; binds every LIKE pattern in case order, then every text.
     */
    final ExactNameSelfTestStatement exactNameSelfTestStatement() {
        List<ExactNameSelfTest> tests = exactNameSelfTests();
        StringBuilder sql = new StringBuilder("SELECT ");
        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();
        List<Object> texts = new ArrayList<>();
        for (int index = 0; index < tests.size(); index++) {
            if (index > 0) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append("CASE WHEN ").append(exactNameLikeSql("v.t" + index)).append(" THEN 1 ELSE 0 END");
            params.add(literalLikePattern(tests.get(index).literal()));
            values.append(searchSelfTestTextSql()).append(" AS t").append(index);
            texts.add(tests.get(index).text());
        }
        sql.append(" FROM (SELECT ").append(values).append(") v");
        params.addAll(texts);
        return new ExactNameSelfTestStatement(sql.toString(), params, tests);
    }

    /**
     * T31-R4 startup self-test, after the schema and before reads are admitted: runs this backend's exact
     * production forms ({@link #searchSelfTestStatement()}), then the exact-name prefilter's form (lane xn r2,
     * {@link #exactNameSelfTestStatement()}). Never throws. A failure or a wrong answer sends every text filter,
     * {@code name:} included, to the Java check ({@link #disableSearchSql(String)}).
     */
    final void verifySearchTextSql() {
        String problem = null;
        try {
            SearchSelfTestStatement selfTest = searchSelfTestStatement();
            ExactNameSelfTestStatement nameTest = exactNameSelfTestStatement();
            try (Connection connection = connection()) {
                problem = selfTestProblem(connection, selfTest.sql(), selfTest.params(),
                        selfTest.tests().stream().map(SearchFold.SelfTest::expected).toList(), "");
                if (problem == null) {
                    problem = selfTestProblem(connection, nameTest.sql(), nameTest.params(),
                            Collections.nCopies(nameTest.tests().size(), Boolean.TRUE), "exact-name ");
                }
            }
        } catch (SQLException | RuntimeException failure) {
            problem = "its self-test failed (" + EventTrailDiagnostics.safeFailureSummary(failure) + ")";
        }
        if (problem != null) disableSearchSql(problem);
    }

    /** Runs one self-test statement; null when every result column gives its expected answer. */
    private static String selfTestProblem(Connection connection, String sql, List<Object> params,
                                          List<Boolean> expected, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return "its " + kind + "self-test returned no row";
                for (int index = 0; index < expected.size(); index++) {
                    if ((result.getInt(index + 1) == 1) != expected.get(index)) {
                        return kind + "self-test case " + index + " answered wrongly";
                    }
                }
            }
        }
        return null;
    }

    private SchemaState establishSchema(Connection connection) throws SQLException {
        for (int failedAttempts = 0; ; failedAttempts++) {
            try {
                return establishSchemaAttempt(connection);
            } catch (SQLException failure) {
                if (!prepareSchemaRetry(failure, failedAttempts + 1)) {
                    throw failure;
                }
            }
        }
    }

    /**
     * Gives a backend bounded opportunities to retry an entire rolled-back
     * schema attempt. The default remains fail-fast because external backends
     * serialize initialization with advisory locks.
     */
    protected boolean prepareSchemaRetry(SQLException failure, int failedAttempts)
            throws SQLException {
        return false;
    }

    private SchemaState establishSchemaAttempt(Connection connection) throws SQLException {
        prepareSchemaConnection(connection);
        boolean originalAutoCommit = connection.getAutoCommit();
        boolean schemaLockAcquired = false;
        Throwable failure = null;
        try {
            if (originalAutoCommit) connection.setAutoCommit(false);
            acquireSchemaLock(connection);
            schemaLockAcquired = true;
            boolean versionTableExists = findTable(connection, SCHEMA_VERSION_TABLE) != null;
            boolean logTableExists = findTable(connection, tableName()) != null;

            if (versionTableExists != logTableExists) {
                throw new SQLException(
                        "EventTrail database schema is partial: its schema-version and log tables must either both exist or both be absent.");
            }

            SchemaState state;
            if (!versionTableExists) {
                createSchemaVersionTable(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(createLogTableSql());
                }
                validateSchemaVersionTable(connection);
                validateCurrentSchema(connection);
                ensureSchemaIndexes(connection);
                validateSchemaIndexes(connection);
                recordSchemaVersion(connection, CURRENT_SCHEMA_VERSION);
                state = SchemaState.FRESH;
            } else {
                validateSchemaVersionTable(connection);
                validateRecordedSchemaVersion(readSchemaVersion(connection));
                validateCurrentSchema(connection);
                validateSchemaIndexes(connection);
                state = SchemaState.CURRENT;
            }

            connection.commit();
            return state;
        } catch (SQLException | RuntimeException schemaFailure) {
            failure = schemaFailure;
            try {
                if (!connection.getAutoCommit()) connection.rollback();
            } catch (SQLException rollbackFailure) {
                schemaFailure.addSuppressed(rollbackFailure);
            }
            throw schemaFailure;
        } finally {
            SQLException cleanupFailure = null;
            if (schemaLockAcquired) {
                try {
                    releaseSchemaLock(connection);
                } catch (SQLException releaseFailure) {
                    cleanupFailure = releaseFailure;
                }
            }
            try {
                if (connection.getAutoCommit() != originalAutoCommit) {
                    connection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException restorationFailure) {
                if (cleanupFailure == null) cleanupFailure = restorationFailure;
                else cleanupFailure.addSuppressed(restorationFailure);
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Optional schema serialization for backends whose catalog DDL can race. */
    protected void acquireSchemaLock(Connection connection) throws SQLException {
        // External backends use advisory locks. SQLite relies on its file lock
        // and a bounded retry of the complete rolled-back schema attempt.
    }

    /** Releases a session-scoped schema lock; implementations without one are no-ops. */
    protected void releaseSchemaLock(Connection connection) throws SQLException {
        // MySQL releases its session advisory lock here.
    }

    private void createSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(schemaVersionTableSql());
        }
    }

    private void validateSchemaVersionTable(Connection connection) throws SQLException {
        TableIdentity table = findTable(connection, SCHEMA_VERSION_TABLE);
        if (table == null) {
            throw new SQLException("EventTrail database schema-version table is missing.");
        }

        Map<String, Integer> columns = readColumnTypes(connection, SCHEMA_VERSION_TABLE);
        Set<String> required = Set.of("component", "version", "updated_at");
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            throw new SQLException(
                    "EventTrail database schema-version table is missing required column(s): "
                            + String.join(", ", missing) + ".");
        }
        if (!isNumericJdbcType(columns.get("version"))
                || !isNumericJdbcType(columns.get("updated_at"))) {
            throw new SQLException(
                    "EventTrail database schema-version columns 'version' and 'updated_at' must be numeric.");
        }
        if (!hasSingleColumnUniqueKey(connection, table, "component")) {
            throw new SQLException(
                    "EventTrail database schema-version column 'component' must be a primary or unique key.");
        }
    }

    static String schemaVersionTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS eventtrail_schema_version (
                    component  VARCHAR(64) PRIMARY KEY,
                    version    INTEGER NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """;
    }

    private Integer readSchemaVersion(Connection connection) throws SQLException {
        String sql = "SELECT version FROM " + SCHEMA_VERSION_TABLE + " WHERE component = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA_COMPONENT);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                int version = result.getInt(1);
                if (result.wasNull()) {
                    throw new SQLException("EventTrail database schema version is NULL.");
                }
                if (result.next()) {
                    throw new SQLException("EventTrail database contains duplicate log schema version rows.");
                }
                return version;
            }
        }
    }

    static void validateRecordedSchemaVersion(Integer version) throws SQLException {
        if (version == null) {
            throw new SQLException("EventTrail database schema marker for component 'logs' is missing.");
        }
        if (version < CURRENT_SCHEMA_VERSION) {
            throw new SQLException("EventTrail database schema version " + version + " is invalid.");
        }
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new SQLException(
                    "EventTrail database schema version " + version
                            + " is newer than this build supports (" + CURRENT_SCHEMA_VERSION
                            + "). Update EventTrail before using this database.");
        }
    }

    final void recordSchemaVersion(Connection connection, int version) throws SQLException {
        long updatedAt = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(schemaVersionUpsertSql())) {
            statement.setString(1, SCHEMA_COMPONENT);
            statement.setInt(2, version);
            statement.setLong(3, updatedAt);
            statement.executeUpdate();
        }

        Integer recorded = readSchemaVersion(connection);
        validateRecordedSchemaVersion(recorded);
        if (recorded == null || recorded < version) {
            throw new SQLException(
                    "EventTrail database schema version could not be recorded as " + version + ".");
        }
    }

    private void validateCurrentSchema(Connection connection) throws SQLException {
        TableIdentity table = findTable(connection, tableName());
        if (table == null) {
            throw new SQLException(
                    "EventTrail database schema is incompatible: required table '" + tableName() + "' is missing.");
        }

        Map<String, Integer> columns = readColumnTypes(connection, tableName());
        Set<String> missing = new LinkedHashSet<>(requiredSchemaColumns(usesServerIdColumn()));
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            throw new SQLException(
                    "EventTrail database schema is incompatible: table '" + tableName()
                            + "' is missing required column(s): " + String.join(", ", missing) + ".");
        }

        Integer idType = columns.get("id");
        if (idType == null || !isNumericJdbcType(idType)) {
            throw new SQLException(
                    "EventTrail database schema is incompatible: column 'id' must be numeric.");
        }
        if (!hasSingleColumnUniqueKey(connection, table, "id")) {
            throw new SQLException(
                    "EventTrail database schema is incompatible: column 'id' must be a single-column primary or unique key.");
        }
        validateStorageSchemaContract(connection);
    }

    static Set<String> requiredSchemaColumns(boolean external) {
        LinkedHashSet<String> columns = new LinkedHashSet<>(List.of(
                "id", "timestamp", "action_type", "player_uuid", "player_name",
                "world", "pos_x", "pos_y", "pos_z", "block_state", "extra_data"));
        if (external) columns.add("server_id");
        return Collections.unmodifiableSet(columns);
    }

    private Map<String, Integer> readColumnTypes(Connection connection, String table) throws SQLException {
        Map<String, Integer> columns = new HashMap<>();
        String sql = "SELECT * FROM " + table + " WHERE 1 = 0";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                columns.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT), metadata.getColumnType(i));
            }
        }
        return columns;
    }

    private static boolean isNumericJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    protected final void validateExternalColumnContract(Connection connection) throws SQLException {
        TableIdentity table = findTable(connection, tableName());
        if (table == null) {
            throw new SQLException(
                    "EventTrail external database schema is incompatible: required log table is missing.");
        }
        Map<String, SchemaColumn> columns = readSchemaColumns(connection, table);
        validateExternalColumnContract(columns);
        if (!hasSingleColumnPrimaryKey(connection, table, "id")) {
            throw new SQLException(
                    "EventTrail external database schema is incompatible: id must be the single-column primary key.");
        }
    }

    static void validateExternalColumnContract(Map<String, SchemaColumn> columns) throws SQLException {
        Set<String> missing = new LinkedHashSet<>(requiredSchemaColumns(true));
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            throw new SQLException(
                    "EventTrail external database schema is missing required column(s): "
                            + String.join(", ", missing) + ".");
        }

        requireColumn(columns, "id", ColumnFamily.NUMERIC, false);
        requireColumn(columns, "server_id", ColumnFamily.TEXT, false);
        requireColumn(columns, "timestamp", ColumnFamily.NUMERIC, false);
        requireColumn(columns, "action_type", ColumnFamily.TEXT, false);
        requireColumn(columns, "player_uuid", ColumnFamily.TEXT, false);
        requireColumn(columns, "player_name", ColumnFamily.TEXT, false);
        requireColumn(columns, "world", ColumnFamily.TEXT, false);
        requireColumn(columns, "pos_x", ColumnFamily.NUMERIC, false);
        requireColumn(columns, "pos_y", ColumnFamily.NUMERIC, false);
        requireColumn(columns, "pos_z", ColumnFamily.NUMERIC, false);
        requireColumn(columns, "block_state", ColumnFamily.TEXT, true);
        requireColumn(columns, "extra_data", ColumnFamily.TEXT, true);
    }

    private static void requireColumn(
            Map<String, SchemaColumn> columns,
            String name,
            ColumnFamily family,
            boolean nullable) throws SQLException {
        SchemaColumn column = columns.get(name);
        if (column == null || !family.accepts(column.jdbcType())) {
            throw new SQLException(
                    "EventTrail external database schema column '" + name
                            + "' has an incompatible SQL type.");
        }
        int expectedNullability = nullable
                ? DatabaseMetaData.columnNullable
                : DatabaseMetaData.columnNoNulls;
        if (column.nullability() != expectedNullability) {
            throw new SQLException(
                    "EventTrail external database schema column '" + name
                            + "' has incompatible nullability.");
        }
    }

    private Map<String, SchemaColumn> readSchemaColumns(
            Connection connection, TableIdentity table) throws SQLException {
        Map<String, SchemaColumn> columns = new HashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(
                table.catalog(), table.schema(), table.name(), null)) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                if (name == null) continue;
                columns.put(name.toLowerCase(Locale.ROOT), new SchemaColumn(
                        result.getInt("DATA_TYPE"),
                        result.getInt("NULLABLE"),
                        result.getString("TYPE_NAME"),
                        result.getString("COLUMN_DEF"),
                        result.getString("IS_AUTOINCREMENT"),
                        result.getString("IS_GENERATEDCOLUMN")));
            }
        }
        return columns;
    }

    private boolean hasSingleColumnPrimaryKey(
            Connection connection, TableIdentity table, String requiredColumn) throws SQLException {
        TreeMap<Short, String> primaryColumns = new TreeMap<>();
        try (ResultSet result = connection.getMetaData().getPrimaryKeys(
                table.catalog(), table.schema(), table.name())) {
            while (result.next()) {
                String column = result.getString("COLUMN_NAME");
                if (column == null) continue;
                primaryColumns.put(
                        result.getShort("KEY_SEQ"), column.toLowerCase(Locale.ROOT));
            }
        }
        return isSingleColumnPrimaryKey(List.copyOf(primaryColumns.values()), requiredColumn);
    }

    static boolean isSingleColumnPrimaryKey(List<String> orderedColumns, String requiredColumn) {
        return orderedColumns.size() == 1
                && requiredColumn.equalsIgnoreCase(orderedColumns.getFirst());
    }

    private boolean hasSingleColumnUniqueKey(
            Connection connection, TableIdentity table, String requiredColumn) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> primaryColumns = new ArrayList<>();
        try (ResultSet result = metadata.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (result.next()) {
                String column = result.getString("COLUMN_NAME");
                if (column != null) primaryColumns.add(column.toLowerCase(Locale.ROOT));
            }
        }
        if (primaryColumns.size() == 1 && requiredColumn.equals(primaryColumns.getFirst())) return true;

        Map<String, Set<String>> uniqueIndexes = new HashMap<>();
        try (ResultSet result = metadata.getIndexInfo(
                table.catalog(), table.schema(), table.name(), true, false)) {
            while (result.next()) {
                String index = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (index == null || column == null) continue;
                uniqueIndexes.computeIfAbsent(index, ignored -> new HashSet<>())
                        .add(column.toLowerCase(Locale.ROOT));
            }
        }
        return uniqueIndexes.values().stream().anyMatch(columns -> columns.equals(Set.of(requiredColumn)));
    }

    private static TableIdentity findTable(Connection connection, String expectedName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = currentSchema(connection);
        TableIdentity found = findTable(metadata, catalog, schema, expectedName);
        if (found == null && schema != null) found = findTable(metadata, catalog, null, expectedName);
        return found;
    }

    private static TableIdentity findTable(
            DatabaseMetaData metadata, String catalog, String schema, String expectedName) throws SQLException {
        for (String pattern : List.of(
                expectedName, expectedName.toUpperCase(Locale.ROOT), expectedName.toLowerCase(Locale.ROOT))) {
            try (ResultSet result = metadata.getTables(catalog, schema, pattern, new String[]{"TABLE"})) {
                while (result.next()) {
                    String name = result.getString("TABLE_NAME");
                    if (name != null && name.equalsIgnoreCase(expectedName)) {
                        return new TableIdentity(
                                result.getString("TABLE_CAT"), result.getString("TABLE_SCHEM"), name);
                    }
                }
            }
        }
        return null;
    }

    private static String currentSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private void logSchemaState(SchemaState state) {
        switch (state) {
            case FRESH -> LOGGER.info("EventTrail schema initialized at version {}.", CURRENT_SCHEMA_VERSION);
            case CURRENT -> LOGGER.info(
                    "EventTrail database schema version {} is current.", CURRENT_SCHEMA_VERSION);
        }
    }

    private enum SchemaState {
        FRESH,
        CURRENT
    }

    private record TableIdentity(String catalog, String schema, String name) {}

    record SchemaColumn(
            int jdbcType,
            int nullability,
            String typeName,
            String defaultValue,
            String autoIncrement,
            String generated) {}

    protected record IndexSpec(String name, List<String> columns) {
        protected IndexSpec {
            name = name.toLowerCase(Locale.ROOT);
            columns = columns.stream()
                    .map(column -> column.toLowerCase(Locale.ROOT))
                    .toList();
        }

        String normalizedName() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    private enum ColumnFamily {
        NUMERIC {
            @Override
            boolean accepts(int jdbcType) {
                return isNumericJdbcType(jdbcType);
            }
        },
        TEXT {
            @Override
            boolean accepts(int jdbcType) {
                return switch (jdbcType) {
                    case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                            Types.CLOB, Types.NCLOB -> true;
                    default -> false;
                };
            }
        };

        abstract boolean accepts(int jdbcType);
    }

    protected void configureConnection(Connection connection) throws SQLException {
        // Optional backend-specific connection tuning.
    }

    protected void discardFailedConnection(HikariDataSource source, Connection connection) throws SQLException {}

    protected int consistentReadTransactionIsolation() {
        // MySQL and PostgreSQL both provide repeatable-read snapshots.
        return Connection.TRANSACTION_REPEATABLE_READ;
    }

    protected void afterAdvancedCount(Connection connection, long total) throws Exception {
        // Optional instrumentation hook invoked inside the consistent read transaction.
    }

    protected void onRetentionBatchCommitted(int deleted) {
        // Deterministic instrumentation hook; no row data is loaded into memory.
    }

    protected long retentionNanoTime() {
        return System.nanoTime();
    }

    protected void afterRetentionMaintenance(long deleted) throws Exception {
        // Optional backend maintenance must not change the committed delete count.
    }

    protected final Connection connection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) throw new SQLException("EventTrail datasource is not initialized");
        Connection connection = source.getConnection();
        try {
            configureConnection(connection);
            if (connection.isClosed()) {
                throw new SQLException("EventTrail connection closed during configuration", "08003");
            }
            return connection;
        } catch (SQLException | RuntimeException | Error setupFailure) {
            // Ownership transfers only after setup and the open-handle check.
            // Closing the logical handle keeps all physical cleanup pool-owned.
            try {
                discardFailedConnection(source, connection);
            } catch (Throwable evictionFailure) {
                if (evictionFailure != setupFailure) setupFailure.addSuppressed(evictionFailure);
            }
            try {
                connection.close();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != setupFailure) setupFailure.addSuppressed(cleanupFailure);
            }
            throw setupFailure;
        }
    }

    /** Performs the live database ping used by /eventtrail status. */
    public final void verifyConnection(int queryTimeoutSeconds) throws SQLException {
        try {
            runRead(() -> {
                try (Connection connection = connection();
                     Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
                    return withActiveReadStatement(statement, () -> {
                        try (ResultSet result = statement.executeQuery("SELECT 1")) {
                            if (!result.next()) {
                                throw new SQLException("Database health query returned no result");
                            }
                            return null;
                        }
                    });
                }
            });
        } catch (EventTrailQueryException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sqlFailure) throw sqlFailure;
            throw new SQLException("EventTrail database health query failed", failure);
        }
    }

    private <T> T withActiveReadStatement(Statement statement, CheckedSupplier<T> action) throws Exception {
        ReadOperation operation = currentReadOperation.get();
        if (operation == null) return action.get();

        operation.register(statement);
        try {
            JdbcShutdownDiagnostics.Execution execution = operation.diagnosticExecution(statement);
            if (execution == null) return action.get();
            // Reads carry only their enclosing scope, so they can be excluded as
            // different physical cancellation targets; they cannot earn write proof.
            JdbcShutdownDiagnostics.enter(execution, 0);
            try {
                T value = action.get();
                JdbcShutdownDiagnostics.returned(execution, null);
                return value;
            } catch (Exception | Error failure) {
                JdbcShutdownDiagnostics.returned(execution, failure);
                throw failure;
            }
        } finally {
            operation.clear(statement);
        }
    }

    private void cancelActiveReads() {
        List<ReadOperation> operations;
        synchronized (readLifecycleLock) {
            acceptingReads = false;
            operations = List.copyOf(activeReadOperations);
        }
        operations.forEach(ReadOperation::cancel);
        readExecutor.shutdownNow();
    }

    private boolean awaitReadExecutorShutdown() {
        try {
            return readExecutor.awaitTermination(READ_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finishReadOperation(ReadOperation operation) {
        activeReadOperations.remove(operation);
        if (currentReadOperation.get() == operation) {
            currentReadOperation.remove();
        }
        backendOperationFinished();
    }

    private void logReadShutdownResult(boolean terminated) {
        if (!terminated) {
            LOGGER.warn(
                    "EventTrail {} database read workers did not terminate within {} seconds; " +
                            "the old storage datasource will be closed and late results cannot be published.",
                    getBackendName(), READ_SHUTDOWN_TIMEOUT_SECONDS);
        }
        int remaining = activeReadOperations.size();
        if (remaining > 0) {
            LOGGER.warn("EventTrail {} storage closed with {} read operation(s) still terminating",
                    getBackendName(), remaining);
        }
    }

    private boolean admitReadOperation(ReadOperation operation) {
        synchronized (readLifecycleLock) {
            if (!acceptingReads || !isReady() || readExecutor.isShutdown()) return false;
            activeReadOperations.add(operation);
            return true;
        }
    }

    private <T> Future<T> submitReadOperation(ReadOperation operation, CheckedSupplier<T> supplier) {
        synchronized (readLifecycleLock) {
            if (!acceptingReads || !isReady() || readExecutor.isShutdown()) {
                activeReadOperations.remove(operation);
                throw new RejectedExecutionException("EventTrail JDBC read executor is unavailable");
            }
            FutureTask<T> future = new FutureTask<>(() -> {
                if (!isReady() || operation.isCanceled()) {
                    throw EventTrailQueryException.storageUnavailable(
                            new SQLException("EventTrail storage stopped before the query began", "08003"));
                }
                return supplier.get();
            });
            operation.attach(future);
            Runnable queuedTask = () -> {
                // 6 b: QUEUED -> STARTED. If the waiting caller already gave this read up (QUEUED -> EXPIRED), it
                // has removed the tracking and released the backend itself, so the worker drops it untouched.
                if (!operation.tryStart()) return;
                operation.markWorkerStarted();
                currentReadOperation.set(operation);
                try {
                    future.run();
                } finally {
                    operation.markWorkerFinished();
                    finishReadOperation(operation);
                }
            };
            operation.attachQueuedTask(queuedTask);
            try {
                readExecutor.execute(queuedTask);
            } catch (RejectedExecutionException rejected) {
                future.cancel(false);
                activeReadOperations.remove(operation);
                throw rejected;
            }
            return future;
        }
    }

    @Override
    public final void log(AuditRecord record) {
        tryLog(record);
    }

    @Override
    public final boolean tryLog(AuditRecord record) {
        if (record == null) return false;
        // A single row is a group of one (writer-01 2.5).
        return tryLogAll(List.of(record));
    }

    /**
     * Admits every row of one action to the write queue, or none (writer-01 2.5; adapter design-r3 3.3 B). Every
     * row is encoded first; one malformed row rejects the group. Under {@code writeLifecycleLock}: the open check,
     * every row within the single-entry limit, the group within {@link #MAX_ROWS_PER_ACTION} rows and within the
     * whole pending-payload limit (both size refusals), all n row permits or none, and the summed payload within
     * the room left; then the group is offered as ONE queue element,
     * so the writer can only ever take it whole. Accounting stays per row; a rejected group counts its n rows in
     * the matching rejection counter and leaves every reservation as it was. A null or empty list, or a null
     * element, admits nothing and returns false.
     */
    @Override
    public final boolean tryLogAll(List<AuditRecord> records) {
        if (records == null || records.isEmpty()) return false;
        refreshBackendRestriction();

        int rowCount = records.size();
        List<SchemaV1Row> rows = new ArrayList<>(rowCount);
        long largestRowBytes = 0L;
        long groupBytes = 0L;
        for (AuditRecord record : records) {
            if (record == null) return false;
            final SchemaV1Row row;
            try {
                row = SchemaV1RowCodec.encode(record);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            long rowBytes = SchemaV1RowCodec.retainedBytes(row);
            largestRowBytes = Math.max(largestRowBytes, rowBytes);
            groupBytes = saturatedAdd(groupBytes, rowBytes);
            rows.add(row);
        }

        WritePressureReason pressure = null;
        DiskSpool spoolLines = null;
        try {
        synchronized (writeLifecycleLock) {
            if (!acceptingWrites) {
                rejectedClosedWrites.addAndGet(rowCount);
                return false;
            }
            if (largestRowBytes > maxSingleEntryRetainedBytes()) {
                rejectedOversizedWrites.addAndGet(rowCount);
                pressure = WritePressureReason.OVERSIZED_ENTRY;
                return false;
            }
            if (rowCount > MAX_ROWS_PER_ACTION) {
                rejectedOversizedWrites.addAndGet(rowCount);
                pressure = WritePressureReason.OVERSIZED_ACTION;
                return false;
            }
            // An action above the whole pending-payload limit can never fit, even into an empty buffer: a size
            // refusal like the row limit above, never buffer saturation (writer-01 Advisor review AD-1).
            if (groupBytes > maxPendingPayloadBytes()) {
                rejectedOversizedWrites.addAndGet(rowCount);
                pressure = WritePressureReason.OVERSIZED_ACTION_PAYLOAD;
                return false;
            }
            // writer-spool design-02 4.3: while actions wait on disk or in staging (the latch), a new action is
            // staged behind them when the spool can take it; no SQLite call and no file I/O here (S-4).
            DiskSpool diskSpool = spool;
            boolean latched = diskSpool != null && spoolLatchedLocked(diskSpool);
            if (latched && spoolStageLocked(diskSpool, rows, groupBytes)) {
                spoolLines = diskSpool;
                return true;
            }
            // Built before any permit is taken, so a constructor failure cannot leak permits (review-01 NIT-5).
            PendingGroup pending = new PendingGroup(rows, groupBytes, latched);
            if (!pendingWriteSlots.tryAcquire(rowCount)) {
                // design-02 4.3: memory has no room; the spool may start an episode instead of refusing.
                if (!latched && diskSpool != null && spoolStageLocked(diskSpool, rows, groupBytes)) {
                    spoolLines = diskSpool;
                    return true;
                }
                rejectedFullWrites.addAndGet(rowCount);
                spoolLines = countSpoolRefusalLocked(diskSpool, groupBytes, rowCount);
                pressure = WritePressureReason.COUNT_CAPACITY;
                return false;
            }

            long currentPayload = pendingPayloadBytes.get();
            long payloadLimit = maxPendingPayloadBytes();
            if (groupBytes > payloadLimit - currentPayload) {
                pendingWriteSlots.release(rowCount);
                if (!latched && diskSpool != null && spoolStageLocked(diskSpool, rows, groupBytes)) {
                    spoolLines = diskSpool;
                    return true;
                }
                rejectedPayloadWrites.addAndGet(rowCount);
                spoolLines = countSpoolRefusalLocked(diskSpool, groupBytes, rowCount);
                pressure = WritePressureReason.PAYLOAD_CAPACITY;
                return false;
            }

            pendingPayloadBytes.addAndGet(groupBytes);
            pendingWrites.addAndGet(rowCount);
            acceptedWrites.addAndGet(rowCount);
            // design-02 4.3 row 4: the spool could not take it while actions wait, memory could (the order exception).
            if (latched) {
                diskSpool.admittedPastActions.incrementAndGet();
                diskSpool.markFullIfFull(groupBytes);
                spoolLines = diskSpool;
                pendingAdmittedPastGroups.incrementAndGet();
            }
            if (!offerPendingWrite(pending)) {
                // The row permits bound the element count below the queue's capacity. This path is
                // defensive in case their state ever becomes inconsistent: the group is rejected whole.
                if (latched) pendingAdmittedPastGroups.decrementAndGet();
                acceptedWrites.addAndGet(-rowCount);
                pendingWrites.addAndGet(-rowCount);
                pendingPayloadBytes.addAndGet(-groupBytes);
                pendingWriteSlots.release(rowCount);
                rejectedFullWrites.addAndGet(rowCount);
                pressure = WritePressureReason.COUNT_CAPACITY;
                return false;
            }
            return true;
        }
        } finally {
            // Do not call listeners or logging while admission/accounting is locked.
            try {
                if (spoolLines != null) spoolLines.flushAdmissionLines();
                if (pressure != null) logWritePressure(pressure);
            } finally { publishWriteObservation(); }
        }
    }

    /** Under writeLifecycleLock; m-8: a Throwable from spool code turns the spool off and today's path applies. */
    private boolean spoolLatchedLocked(DiskSpool diskSpool) {
        try {
            return diskSpool.latched();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            diskSpool.failInAdmission(failure);
            return false;
        }
    }

    /** Under writeLifecycleLock: decided from the atomic mirrors, then a pointer move into staging (design-02 4.3). */
    private boolean spoolStageLocked(DiskSpool diskSpool, List<SchemaV1Row> rows, long groupBytes) {
        try {
            if (!diskSpool.canAccept(groupBytes)) return false;
            if (!diskSpool.stage(rows, groupBytes, System.currentTimeMillis())) return false;
            acceptedWrites.addAndGet(rows.size());
            return true;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            diskSpool.failInAdmission(failure);
            return false;
        }
    }

    /**
     * Under writeLifecycleLock: the refused rows are already in today's counter; the spool's reason is a breakdown
     * of those same rows, never added on top (design-01 11). Returns the spool when it has a line to write.
     */
    private DiskSpool countSpoolRefusalLocked(DiskSpool diskSpool, long groupBytes, int rowCount) {
        if (diskSpool == null) return null;
        try {
            String reason = diskSpool.refusal(groupBytes);
            if (reason == null || "off".equals(reason)) diskSpool.refusedOffRows.addAndGet(rowCount);
            else if ("full".equals(reason)) diskSpool.refusedFullRows.addAndGet(rowCount);
            else diskSpool.refusedUnwritableRows.addAndGet(rowCount);
            diskSpool.markFullIfFull(groupBytes);
            return diskSpool;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            diskSpool.failInAdmission(failure);
            return null;
        }
    }

    private boolean spoolHintLocked() {
        DiskSpool diskSpool = spool;
        if (diskSpool == null) return false;
        try {
            return diskSpool.canAcceptHint();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            diskSpool.failInAdmission(failure);
            return false;
        }
    }

    /**
     * Explosion split (design-02 2.1; Advisor M-3, M-5 b, m-3 and answer 2): the non-counting admission of one part.
     * Decided under {@code writeLifecycleLock}; room for the rows AND the payload, plus the headroom, is tested before
     * any permit is taken. NO_ROOM changes nothing (no counter, no saturation flag, no WARN) and is answered from the
     * caller's exact estimate {@code retainedBytes} before any row is encoded, so it does no per-row work. REFUSED
     * (closed, more than {@link #MAX_ROWS_PER_ACTION} rows, a part that could not fit even an empty buffer with the
     * headroom, a null or unencodable row, a row above the single-row limit, the defensive offer-failed branch) counts
     * nothing either: the publisher counts every part not stored once, in its own figure (condition 4).
     * {@link #tryLogAll} is unchanged. Row permits and payload are only ever taken under the lock held here and only
     * given back elsewhere, so room tested under the lock stays room until the permits are taken.
     */
    @Override
    public final OfferResult tryLogAllWhenRoom(
            List<AuditRecord> records, long retainedBytes, int headroomRows, long headroomBytes) {
        if (records == null || records.isEmpty() || retainedBytes < 0L || headroomRows < 0 || headroomBytes < 0L) {
            return OfferResult.REFUSED;
        }
        refreshBackendRestriction();
        int rowCount = records.size();
        try {
            synchronized (writeLifecycleLock) {
                OfferResult early = whenRoomLocked(rowCount, retainedBytes, headroomRows, headroomBytes);
                if (early != null) return early;
            }
            List<SchemaV1Row> rows = new ArrayList<>(rowCount);
            long largestRowBytes = 0L;
            long groupBytes = 0L;
            for (AuditRecord record : records) {
                if (record == null) return OfferResult.REFUSED;
                final SchemaV1Row row;
                try {
                    row = SchemaV1RowCodec.encode(record);
                } catch (IllegalArgumentException invalid) {
                    return OfferResult.REFUSED;
                }
                long rowBytes = SchemaV1RowCodec.retainedBytes(row);
                largestRowBytes = Math.max(largestRowBytes, rowBytes);
                groupBytes = saturatedAdd(groupBytes, rowBytes);
                rows.add(row);
            }
            synchronized (writeLifecycleLock) {
                if (largestRowBytes > maxSingleEntryRetainedBytes()) return OfferResult.REFUSED;
                // Re-tested with the encoded size: another producer may have taken room since the first test.
                OfferResult now = whenRoomLocked(rowCount, groupBytes, headroomRows, headroomBytes);
                if (now != null) return now;
                // MAJOR-1 / MINOR-9: a part admitted to memory while actions wait on disk overtakes them (S3, M-7,
                // decides its spool path); it counts toward the starvation bound like the exception row.
                DiskSpool diskSpool = spool;
                boolean latched = diskSpool != null && spoolLatchedLocked(diskSpool);
                PendingGroup pending = new PendingGroup(rows, groupBytes, latched);
                if (!pendingWriteSlots.tryAcquire(rowCount)) return OfferResult.NO_ROOM;
                pendingPayloadBytes.addAndGet(groupBytes);
                pendingWrites.addAndGet(rowCount);
                acceptedWrites.addAndGet(rowCount);
                if (latched) pendingAdmittedPastGroups.incrementAndGet();
                if (!offerPendingWrite(pending)) {
                    if (latched) pendingAdmittedPastGroups.decrementAndGet();
                    // Defensive, as in tryLogAll: the part is refused whole and every reservation given back.
                    acceptedWrites.addAndGet(-rowCount);
                    pendingWrites.addAndGet(-rowCount);
                    pendingPayloadBytes.addAndGet(-groupBytes);
                    pendingWriteSlots.release(rowCount);
                    return OfferResult.REFUSED;
                }
                return OfferResult.ADMITTED;
            }
        } finally {
            publishWriteObservation();
        }
    }

    /**
     * Under {@code writeLifecycleLock}: REFUSED when the part can never be admitted, NO_ROOM when there is no room
     * for it plus the headroom now, null when there is. Reads only; takes nothing.
     */
    private OfferResult whenRoomLocked(int rowCount, long groupBytes, int headroomRows, long headroomBytes) {
        if (!acceptingWrites) return OfferResult.REFUSED;
        if (rowCount > MAX_ROWS_PER_ACTION) return OfferResult.REFUSED;
        long payloadLimit = maxPendingPayloadBytes();
        if ((long) rowCount + headroomRows > WRITE_BUFFER_CAPACITY
                || headroomBytes > payloadLimit
                || groupBytes > payloadLimit - headroomBytes) {
            return OfferResult.REFUSED;
        }
        if (pendingWriteSlots.availablePermits() - rowCount < headroomRows) return OfferResult.NO_ROOM;
        long room = payloadLimit - pendingPayloadBytes.get();
        if (groupBytes > room - headroomBytes) return OfferResult.NO_ROOM;
        return null;
    }

    /**
     * af-2 R-M2: rows the READY runtime refused because this storage reported not ready (for example after an
     * in-run SQLite ownership failure) are counted with the rows this storage refused itself after its writer
     * stopped, so the status and the shutdown summary state them with that cause. Count only.
     */
    @Override
    public final boolean recordRefusedWhileUnavailable(int rows) {
        if (rows > 0) rejectedClosedWrites.addAndGet(rows);
        return true;
    }

    @Override
    public final boolean hasWriteCapacityHint() {
        refreshBackendRestriction();
        synchronized (writeLifecycleLock) {
            // L8 / design-01 2.2: spool-aware; the spool's part is atomic mirrors only (no I/O).
            return acceptingWrites && (hasBufferCapacityHintLocked() || spoolHintLocked());
        }
    }

    private boolean hasBufferCapacityHintLocked() {
        return pendingWriteSlots.availablePermits() > 0
                && pendingPayloadBytes.get() <= maxPendingPayloadBytes() - MIN_CAPACITY_HINT_BYTES;
    }

    protected long maxPendingPayloadBytes() {
        return MAX_PENDING_PAYLOAD_BYTES;
    }

    protected long maxSingleEntryRetainedBytes() {
        return MAX_SINGLE_ENTRY_RETAINED_BYTES;
    }

    /** Non-negative sum that stops at Long.MAX_VALUE instead of wrapping (an over-limit group is then refused). */
    private static long saturatedAdd(long total, long addend) {
        return addend > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + addend;
    }

    /** Seam: the single offer of one admitted group, called under {@code writeLifecycleLock}. */
    boolean offerPendingWrite(PendingGroup pending) {
        return writeQueue.offer(pending);
    }

    /**
     * Seam (no-op): called by the writer thread with each assembled batch (whole groups, admission order) just
     * before it is persisted. The list is the live batch and is emptied as its groups resolve; copy it to keep it.
     */
    void batchAssembled(List<PendingGroup> batch) {
    }

    // ==== disk spool (writer-spool design-02) ================================================================

    /**
     * Gives this storage its disk spool folder {@code <eventTrailDirectory>/spool/} (design-02 2.1; all three
     * backends; L10 keys read at init). Called by the storage factory before {@link #init()}; without it the
     * storage has no spool and behaves exactly as before.
     */
    public final void useDiskSpool(Path eventTrailDirectory) {
        useDiskSpool(eventTrailDirectory, null);
    }

    /** Test seam: the spool with explicit settings (clock, probes, connection factory). */
    final void useDiskSpool(Path eventTrailDirectory, DiskSpool.Settings settings) {
        synchronized (writeLifecycleLock) {
            if (initializationStarted) throw new IllegalStateException("EventTrail disk spool must be set before init");
            spoolBaseDirectory = java.util.Objects.requireNonNull(eventTrailDirectory, "eventTrailDirectory");
            spoolSettingsOverride = settings;
        }
    }

    /** Status lines of design-02 11.2 (plain text); empty without a spool. */
    public final List<String> spoolStatusLines() {
        DiskSpool diskSpool = spool;
        return diskSpool == null ? List.of() : DiskSpool.statusLines(diskSpool.stats());
    }

    /** Actions waiting on disk or in staging (the "Audit logging: Backlogged" line, design-01 11); 0 without a spool. */
    public final long spoolWaitingActions() {
        DiskSpool diskSpool = spool;
        if (diskSpool == null) return 0L;
        return diskSpool.waitingFrames() + diskSpool.stagedActions();
    }

    final DiskSpool diskSpoolForTest() {
        return spool;
    }

    /** R-10/R-15 (r9), tests only: the database refused the M-2 session limit for this run. */
    final boolean spoolSessionLimitRefusedForTest() {
        return spoolSessionLimitRefused;
    }

    /** R-15 (r9), tests only: the database refused the advance's short lock wait for this run. */
    final boolean spoolAdvanceLockBoundRefusedForTest() {
        return spoolAdvanceLockBoundRefused;
    }

    final DiskSpool.SpoolStats spoolStatsOrNull() {
        DiskSpool diskSpool = spool;
        return diskSpool == null ? null : diskSpool.stats();
    }

    /** The backend code bound into the spool's meta row (1 SQLite, 2 MySQL, 3 PostgreSQL). */
    protected int spoolBackendCode() {
        return DiskSpool.BACKEND_SQLITE;
    }

    /** M-1 (design-02 6.3): the version table must be transactional. Only MySQL/MariaDB can answer false. */
    protected boolean spoolVersionTableTransactional(Connection connection) throws SQLException {
        return true;
    }

    /** M-2 step 1: set right after the switch to transaction mode of a spool transaction. */
    protected void spoolApplySessionLimit(Connection connection) throws SQLException {}

    /** M-2 step 1: restores what {@link #spoolApplySessionLimit} changed; false when it could not. Never throws. */
    protected boolean spoolRestoreSessionLimit(Connection connection) {
        return true;
    }

    /** M-2 step 2: a short lock bound for the start-up position check, inside its transaction. */
    protected void spoolApplyInitLockBound(Connection connection) throws SQLException {}

    /**
     * M-2 step 2: restores what {@link #spoolApplyInitLockBound} changed; false when it could not (r5: the connection
     * is then evicted and the check step fails, see {@link #spoolInitTransaction}). Never throws.
     */
    protected boolean spoolRestoreInitLockBound(Connection connection) {
        return true;
    }

    /**
     * MAJOR-4 (r2), design-02 6.4 step 3: the same 2 s lock bound as the start-up check, for the advance statement
     * only, so a lock held on the position row ends as a lock wait (replay paused) well before the write query
     * timeout. Inside the spool transaction, right before the advance.
     */
    protected void spoolApplyAdvanceLockBound(Connection connection) throws SQLException {}

    /**
     * MAJOR-4 (r2): right after the advance, before the rows, so the row INSERT keeps the backend's own lock
     * behaviour. False when a session value could not be restored (the connection is then evicted).
     */
    protected boolean spoolEndAdvanceLockBound(Connection connection) throws SQLException {
        return true;
    }

    /** MAJOR-4 (r2): after a failed advance, restores what the bound changed; false when it could not. Never throws. */
    protected boolean spoolRestoreAdvanceLockBound(Connection connection) {
        return true;
    }

    /**
     * r4 (N-1, M-2): the first failure met by {@link #spoolRestoreSessionLimit}, {@link #spoolEndAdvanceLockBound} or
     * {@link #spoolRestoreAdvanceLockBound} (r5: or {@link #spoolRestoreInitLockBound}) since the last call, which
     * clears it; null when none was recorded. Called by the thread that ran the restore, right after it failed (the
     * writer, or the start-up check). Named in the WARN of {@link #evictAfterFailedSpoolRestore}.
     */
    protected SQLException spoolTakeRestoreFailure() {
        return null;
    }

    /** design-02 6.1: true for an SQLite target at synchronous=NORMAL (deletes wait for a full checkpoint). */
    protected boolean spoolCommitNeedsCheckpoint() {
        return false;
    }

    /** design-02 6.1 with m-4: one coordinated PASSIVE checkpoint; true only when it confirmed every frame. */
    protected boolean spoolCheckpointConfirms() throws SQLException {
        return true;
    }

    /** A lock wait, lock timeout or deadlock: on the position row this is "replay paused" (M-2). */
    private boolean isSpoolLockWait(SQLException failure) {
        for (Throwable current : EventTrailDiagnostics.failureChain(failure)) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.equals("40001") || state.equals("40P01") || state.equals("55P03"))) return true;
                int code = sql.getErrorCode();
                if (code == 1205 || code == 1213 || isSpoolBusyResultCode(code)) return true;
                if (isLockContentionWriteFailure(sql)) return true;
            }
        }
        return false;
    }

    /**
     * MINOR-11 (r2): a backend's own busy/locked result code for the spool's lock-wait rule. The default (MySQL,
     * PostgreSQL) has none: a low-byte rule would also match MySQL codes such as 1286, 1541, 1542, 1797 and 1798.
     * SQLite overrides it with SQLITE_BUSY (5) and SQLITE_LOCKED (6) and their extended codes.
     */
    protected boolean isSpoolBusyResultCode(int code) {
        return false;
    }

    /** design-02 5.1: open the spool (bounded) and settle its position; only a target connection failure throws. */
    private void openAndSettleSpool() throws SQLException {
        Path base = spoolBaseDirectory;
        if (base == null) return;
        DiskSpool.Settings settings = spoolSettingsOverride;
        if (settings == null) {
            settings = DiskSpool.Settings.production(EventTrailConfig.DATABASE_SPOOL_ENABLED.get(),
                    EventTrailConfig.DATABASE_SPOOL_MAX_MEGABYTES.get(), EventTrailConfig.LOG_RETENTION_DAYS.get());
        }
        DiskSpool created;
        try {
            created = new DiskSpool(base, new SpoolFile.Binding(spoolBackendCode(), serverId), settings,
                    writeLifecycleLock, getBackendName());
            created.start();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            LOGGER.warn("EventTrail disk spool is off for this run (unexpected-failure); actions the database writer "
                    + "cannot take are refused and counted as before.");
            return;
        }
        spool = created;
        DiskSpool.OpenOutcome outcome = created.awaitOpen(DiskSpool.OPEN_WAIT_MILLIS);
        spoolOpenOutcome = outcome;
        if (outcome.disabled()) {
            created.disabledWaiting = Math.max(0L, outcome.disabledWaiting());
            if (outcome.disabledWaiting() > 0) {
                LOGGER.warn("EventTrail disk spool is disabled in the configuration; {}{} actions from an earlier run "
                        + "wait in spool/spool.db and are not written while it stays disabled.",
                        created.disabledWaitingExact ? "" : "at least ", outcome.disabledWaiting());
            } else if (outcome.disabledWaiting() == 0 && !created.disabledWaitingExact) {
                LOGGER.warn("EventTrail disk spool is disabled in the configuration; spool/spool.db was not closed "
                        + "cleanly, so actions from an earlier run may wait in it (the count is known once the spool "
                        + "is enabled). They are not written while it stays disabled.");
            } else {
                LOGGER.info("EventTrail disk spool is disabled in the configuration.");
            }
            return;
        }
        if (!outcome.usable()) return;
        // MAJOR-3 (r2): the open lines come first, whatever the position check does; MINOR-7 (r2): so do the
        // retention deletion of kept sets and failed frames (file side, no target needed) and the kept-set counts.
        try {
            deleteKeptAfterRetention(created);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            LOGGER.warn("EventTrail disk spool could not delete kept data older than retention.days ({}); it is tried "
                    + "again at the next start.", EventTrailDiagnostics.safeFailureSummary(failure));
        }
        countKeptSetsAtOpen(created);
        logSpoolOpenLines(created, outcome);
        try {
            if (settleSpool()) {
                logSpoolReadyLine(created);
                logHeldIfChanged(created);
            }
        } catch (SQLException failure) {
            if (isSpoolLockWait(failure)) {
                // M-2 step 2: replay paused; the writer retries the check every 30 s; init never fails for it.
                spoolSettleRetryNotBeforeNanos = System.nanoTime() + SPOOL_SETTLE_RETRY_NANOS;
                created.markReadyUnsettled();
                LOGGER.warn("EventTrail disk spool: the database position row is locked; replay is paused and new "
                        + "actions use memory until the check succeeds.");
                return;
            }
            if (isConnectionFailure(failure) && !isDefiniteRowRefusal(failure)) throw failure;
            created.turnOff(isDefiniteRowRefusal(failure) || isDatabaseWideWriteRefusal(failure)
                    ? "position-check-refused" : "unexpected-failure", failure);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            created.turnOff(failure instanceof SpoolFile.SpoolOff off ? off.reason : "unexpected-failure", failure);
        }
    }

    @FunctionalInterface
    private interface SpoolSqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * r5: the start-up position check's connection. Once {@link #spoolInitTransaction} found its lock bound not
     * restored, the connection is not used again; when it was then evicted from the pool (HikariCP's owner eviction),
     * it is not closed either, because after that a logical close can only fail (see
     * {@link #evictAfterFailedSpoolRestore}). Otherwise {@link #close} closes it as before. One thread at a time.
     */
    private static final class SpoolCheckConnection implements AutoCloseable {
        final Connection connection;
        boolean unusable;
        boolean evicted;

        SpoolCheckConnection(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() throws SQLException {
            if (!evicted) connection.close();
        }
    }

    /**
     * One short start-up transaction under the M-2 lock bound (design-02 6.4 step 2). r5: a failed restore of that
     * bound is never ignored. The connection is evicted while still held, with one WARN
     * ({@link #evictAfterFailedSpoolRestore}), and is not used or closed again ({@link SpoolCheckConnection}). The
     * step then fails: with its own failure when it had one (the restore failure added as suppressed), otherwise with
     * the restore failure (its SQLState and code), so the caller's existing rules decide (start-up: a connection
     * failure fails the start, a lock wait pauses replay, anything else turns the spool off; writer retry: retried
     * when retryable, else the spool is turned off). Nothing is retried on that connection.
     */
    private <T> T spoolInitTransaction(SpoolCheckConnection check, SpoolSqlWork<T> work) throws SQLException {
        if (check.unusable) {
            throw new SQLException("EventTrail disk spool: the start-up position check's connection was not usable "
                    + "after a failed restore", "ETSPA");
        }
        Connection connection = check.connection;
        connection.setAutoCommit(false);
        boolean committed = false;
        Throwable primary = null;
        try {
            spoolApplyInitLockBound(connection);
            T result = work.run(connection);
            connection.commit();
            committed = true;
            return result;
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!committed) {
                try { connection.rollback(); } catch (SQLException | RuntimeException ignored) { /* reported by the caller */ }
            }
            if (spoolRestoreInitLockBound(connection)) {
                connection.setAutoCommit(true);
            } else {
                check.unusable = true;
                SQLException restoreFailure = spoolTakeRestoreFailure();
                check.evicted = evictAfterFailedSpoolRestore(connection, "short lock wait of the start-up position check",
                        restoreFailure, "a step of that check", committed
                                ? "That step was committed; the check stops here and is handled as failed."
                                : "That step did not commit; the check stops here and is handled as failed.");
                SQLException failed = restoreFailure == null
                        ? new SQLException("EventTrail disk spool: the start-up position check's connection did not "
                                + "restore its short lock wait", "ETSPA")
                        : new SQLException("EventTrail disk spool: the start-up position check's connection did not "
                                + "restore its short lock wait", restoreFailure.getSQLState(),
                                restoreFailure.getErrorCode(), restoreFailure);
                if (primary != null) {
                    primary.addSuppressed(failed);
                } else {
                    throw failed;
                }
            }
        }
    }

    /**
     * design-02 5.1 step 5 and 6.2-6.4: M-1, the restore of a held set (L18), the 6.2 classification with the m-5
     * hold and the m-2 flag, the exact M-5 count, next = max(H, P) + 1 (S-5), the delete through P (NORMAL: after a
     * checkpoint) and the 2^30 retirement; the Owner's retention deletion (L11 answered) runs at open, before it
     * (MINOR-7, r2). Runs at init or, after a locked check, on the writer thread. Returns false when M-1 turned the
     * spool off (MINOR-8, r2): the callers then log no "ready" or "succeeded" line.
     */
    private boolean settleSpool() throws Exception {
        DiskSpool diskSpool = spool;
        SpoolFile.Meta meta = diskSpool.call(diskSpool::metaOnSpoolThread, SPOOL_CALL_MILLIS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.max(5_000L, EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get()));
        try (MutationScope mutation = beginMutation(MutationKind.AUDIT, deadline);
             SpoolCheckConnection check = new SpoolCheckConnection(connection())) {
            Connection connection = check.connection;
            mutation.enterNative();
            if (!spoolVersionTableTransactional(connection)) {
                diskSpool.turnOff("version-table-not-innodb", null);
                return false;
            }
            // L18: restore a held set when its binding and its position row match and the live spool is empty.
            if (meta.waitingFrames() == 0 && meta.unreadableFrames() == 0) {
                // R-8 (r9): the held sets as they are now, after the retention deletion at open, not the open's list.
                for (SpoolFile.KeptSet held : SpoolFile.listSets(diskSpool.directory(), SpoolFile.HELD)) {
                    if (!held.metaReadable() || !bindingMatches(held, diskSpool.binding())) continue;
                    String heldComponent = SpoolPosition.component(held.spoolIdHex());
                    Long heldPosition = spoolInitTransaction(check, c -> SpoolPosition.read(c, heldComponent));
                    if (heldPosition == null) continue;
                    String folder = held.folder().getFileName().toString();
                    DiskSpool.HoldOutcome hold = diskSpool.call(
                            () -> diskSpool.holdLiveAndOpenOnSpoolThread("replaced-by-restore", folder),
                            SPOOL_CALL_MILLIS);
                    meta = hold.meta();
                    if (hold.restored()) {
                        LOGGER.info("EventTrail disk spool restored a held set for this server id and database; its "
                                + "actions will be written.");
                    }
                    break;
                }
            }
            String component = SpoolPosition.component(meta.spoolIdHex());
            Long position = spoolInitTransaction(check, c -> SpoolPosition.read(c, component));
            SpoolPosition.StartCase start = SpoolPosition.classifyStart(position, meta.replayedThrough(),
                    meta.highWater(), meta.positionCreated(), meta.waitingFrames());
            if (start == SpoolPosition.StartCase.HOLD) {
                // m-5: the database was deleted or swapped while actions wait: hold them, start a fresh spool.
                meta = diskSpool.call(() -> diskSpool.holdLiveAndOpenOnSpoolThread("database-changed", null),
                        SPOOL_CALL_MILLIS).meta();
                position = null;
                start = SpoolPosition.StartCase.CREATE;
            }
            // 2^30 retirement (design-01 5): a fresh id at an empty start, the numbers restart.
            long top = Math.max(meta.highWater(), position == null ? 0L : position);
            if (meta.waitingFrames() == 0 && meta.unreadableFrames() == 0 && top > DiskSpool.RETIRE_SEQUENCE) {
                diskSpool.call(() -> { diskSpool.rebindOnSpoolThread(true); return null; }, SPOOL_CALL_MILLIS);
                meta = diskSpool.call(diskSpool::metaOnSpoolThread, SPOOL_CALL_MILLIS);
                position = null;
                start = SpoolPosition.StartCase.CREATE;
            }
            String live = SpoolPosition.component(meta.spoolIdHex());
            long r = meta.replayedThrough();
            long h = meta.highWater();
            long now = System.currentTimeMillis();
            if (start == SpoolPosition.StartCase.CREATE || start == SpoolPosition.StartCase.RECREATE) {
                spoolInitTransaction(check, c -> {
                    SpoolPosition.create(c, schemaVersionUpsertSql(), live, r, now);
                    SpoolPosition.touch(c, live, now);
                    if (SpoolPosition.read(c, live) == null) throw new SQLException("spool position row not created", "ETSP8");
                    return null;
                });
                if (start == SpoolPosition.StartCase.RECREATE) {
                    LOGGER.info("EventTrail disk spool: its position row was missing from the database with nothing "
                            + "waiting; it was created again.");
                }
                position = r;
            } else {
                // design-01 7.2: an UPDATE that keeps the version proves the right to advance it.
                spoolInitTransaction(check, c -> { SpoolPosition.touch(c, live, now); return null; });
            }
            // m-2: only now that the row exists in the database (and whenever it is found present).
            diskSpool.call(() -> { diskSpool.setPositionCreatedOnSpoolThread(); return null; }, SPOOL_CALL_MILLIS);
            long p = position;
            if (p < r) {
                long lost = r - p;
                diskSpool.positionBehindLostActions.addAndGet(lost);
                LOGGER.warn("EventTrail disk spool: the database no longer has {} action(s) the spool had already handed "
                        + "to it (database restored, failed over, or its last commits were lost).", lost);
                long from = p;
                int updated = spoolInitTransaction(check, c -> SpoolPosition.advance(c, live, from, r, now));
                if (updated != 1) throw new SQLException("spool position moved during the start-up check", "ETSP9");
                p = r;
            }
            if (p > h) {
                // n-5: skipped, no WARN; S-5: next = P + 1.
                diskSpool.skippedPastPositionActions.addAndGet(meta.waitingFrames());
                long raise = p;
                diskSpool.call(() -> { diskSpool.raiseHighWaterOnSpoolThread(raise); return null; }, SPOOL_CALL_MILLIS);
                h = p;
            }
            spoolKnownPosition = p;
            spoolCommittedPosition = p;
            if (!spoolCommitNeedsCheckpoint()) {
                long through = p;
                diskSpool.call(() -> { diskSpool.deleteThroughOnSpoolThread(through); return null; }, SPOOL_CALL_MILLIS);
                spoolCheckpointedPosition = p;
            } else {
                spoolCheckpointedPosition = r;
            }
            countKeptSets(diskSpool, check);
            diskSpool.markSettled(Math.max(h, p));
            return true;
        }
    }

    /** MAJOR-3 (r2): the kept-set counts for the open lines; the position lookups are best effort. */
    private void countKeptSetsAtOpen(DiskSpool diskSpool) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.max(5_000L, EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get()));
        try (MutationScope mutation = beginMutation(MutationKind.AUDIT, deadline);
             SpoolCheckConnection check = new SpoolCheckConnection(connection())) {
            mutation.enterNative();
            countKeptSets(diskSpool, check);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unavailable) {
            countKeptSets(diskSpool, null);
        }
    }

    private static boolean bindingMatches(SpoolFile.KeptSet held, SpoolFile.Binding binding) {
        return Long.toString(binding.backend()).equals(held.props().getProperty("backend"))
                && binding.serverId().equals(held.props().getProperty("server_id", ""));
    }

    /** The Owner's answer to L11 (design-02 18): delete kept data older than retention.days; nothing at 0 days. */
    private void deleteKeptAfterRetention(DiskSpool diskSpool) throws Exception {
        int days = diskSpool.settings().retentionDays();
        if (!diskSpool.settings().deleteKeptAfterRetention() || days <= 0) return;
        long cutoff = diskSpool.settings().clock().getAsLong() - TimeUnit.DAYS.toMillis(days);
        long[] deleted = diskSpool.call(() -> diskSpool.deleteKeptOnSpoolThread(cutoff), SPOOL_CALL_MILLIS);
        if (deleted[3] > 0) {
            // spf1 MINOR-1, ADDENDUM-01: folders without spool.db (a lone -wal is a spool file) in the same WARN.
            LOGGER.warn("EventTrail disk spool deleted {} set-aside or held set(s), {} folder(s) in spool/aside or "
                    + "spool/held without spool.db, and {} stored action(s) that failed their check ({} rows), all "
                    + "older than retention.days ({} days).", deleted[0], deleted[3], deleted[1], deleted[2], days);
        } else if (deleted[0] > 0 || deleted[1] > 0) {
            LOGGER.warn("EventTrail disk spool deleted {} set-aside or held set(s) and {} stored action(s) that failed "
                    + "their check ({} rows), all older than retention.days ({} days).", deleted[0], deleted[1],
                    deleted[2], days);
        }
    }

    /** design-02 7.2 and 7.4: the counts of kept sets (actions = H - max(R, P of the old row) when readable). */
    private void countKeptSets(DiskSpool diskSpool, SpoolCheckConnection check) {
        long sets = 0, bytes = 0, actions = 0, unknown = 0, old = 0;
        int days = diskSpool.settings().retentionDays();
        long cutoff = days > 0 ? diskSpool.settings().clock().getAsLong() - TimeUnit.DAYS.toMillis(days) : Long.MIN_VALUE;
        int lookups = 0;
        for (SpoolFile.KeptSet set : SpoolFile.listSets(diskSpool.directory(), SpoolFile.ASIDE)) {
            sets++;
            bytes += set.bytes();
            if (set.newestWriteMillis() < cutoff) old++;
            long h = set.longValue("high_water");
            long r = set.longValue("replayed_through");
            if (!set.metaReadable() || h < 0 || r < 0) {
                unknown++;
                continue;
            }
            long p = -1L;
            if (check != null && lookups++ < 64) {
                try {
                    // r5: after a failed restore the connection is unusable; the rest are counted from their meta.
                    Long found = spoolInitTransaction(check, c -> SpoolPosition.read(c,
                            SpoolPosition.component(set.spoolIdHex())));
                    if (found != null) p = found;
                } catch (SQLException | RuntimeException unavailable) {
                    // Counted from the set's own meta.
                }
            }
            actions += Math.max(0L, h - Math.max(r, p));
        }
        diskSpool.asideSets = sets;
        diskSpool.asideBytes = bytes;
        diskSpool.asideActions = actions;
        diskSpool.asideUnknownSets = unknown;
        diskSpool.asideOldSets = old;
        long heldSets = 0, heldActions = 0, heldUnknown = 0, heldEmpty = 0;
        for (SpoolFile.KeptSet set : SpoolFile.listSets(diskSpool.directory(), SpoolFile.HELD)) {
            heldSets++;
            // spf1 MINOR-2: unreadable meta (a crash between the move and set.properties) is counted apart, as aside.
            long waiting = set.longValue("waiting_frames");
            if (!set.metaReadable() || waiting < 0) {
                heldUnknown++;
                continue;
            }
            if (waiting == 0) heldEmpty++;
            heldActions += waiting;
        }
        diskSpool.heldSets = heldSets;
        diskSpool.heldActions = heldActions;
        diskSpool.heldUnknownSets = heldUnknown;
        diskSpool.heldEmptySets = heldEmpty;
        // R-9 (r9): folders left by an interrupted move or restore hold no spool.db; they are counted apart. spf1 r3:
        // one scan per kind; links and unreadable entries are kept apart for the start's WARNs.
        SpoolFile.KeptFolderScan asideFolders = SpoolFile.scanKeptFolders(diskSpool.directory(), SpoolFile.ASIDE);
        SpoolFile.KeptFolderScan heldFolders = SpoolFile.scanKeptFolders(diskSpool.directory(), SpoolFile.HELD);
        diskSpool.foldersWithoutDatabase = asideFolders.withoutDatabase().size() + heldFolders.withoutDatabase().size();
        diskSpool.keptFolderScans = List.of(asideFolders, heldFolders);
    }

    /**
     * design-02 11.1, the not-closed-cleanly WARN, with the true most a crash, kill or power loss loses on every
     * backend alike (r8): the spool's staging ({@link DiskSpool#STAGING_MAX_ACTIONS} actions and
     * {@link DiskSpool#STAGING_MAX_RETAINED_BYTES}, the batch being inserted included) and the writer's memory
     * ({@link #WRITE_BUFFER_CAPACITY} rows and {@link #MAX_PENDING_PAYLOAD_BYTES}, held until each group resolves).
     * Every action in memory has at least one row, so the two together are at most 8,192 actions (64 MiB). R-12 (r9):
     * that bound is for actions the database writer accepted; explosion parts still in the explosion part publisher's
     * backlog ({@code ExplosionSplitLimits.BACKLOG_MAX_ROWS} rows, {@code BACKLOG_MAX_BYTES}) had not reached it.
     */
    static final String SPOOL_NOT_CLOSED_CLEANLY_WARN = "EventTrail disk spool was not closed cleanly (crash, kill or "
            + "power loss). Every action it had stored on disk is kept and will be written. Actions the database "
            + "writer had accepted before the stop that had reached neither the disk nor the database are lost and "
            + "cannot be counted: at most 4,096 actions (32 MiB by the writer's estimate) waiting to be stored on disk, "
            + "plus at most 4,096 rows (32 MiB) in the database writer's memory; at most 8,192 actions (64 MiB) in "
            + "all. Explosion parts still waiting in the explosion part backlog (at most 32,768 rows, 96 MiB) had not "
            + "reached the database writer; they are lost too and cannot be counted.";

    /**
     * design-02 11.1, the WAL-missing variant, with R-12 (r9): another program that opened spool.db after the stop
     * folds the WAL into the file (then nothing stored is missing), and the crash window sentence of
     * {@link #SPOOL_NOT_CLOSED_CLEANLY_WARN} applies as well.
     */
    static final String SPOOL_WAL_MISSING_WARN = "EventTrail disk spool was not closed cleanly (crash, kill or power "
            + "loss); its WAL file is missing, so actions stored since its last checkpoint may be lost (count "
            + "unknown). If another program opened spool.db after the stop, it folded the WAL into the file and "
            + "nothing stored is missing. Actions the database writer had accepted before the stop that had reached "
            + "neither the disk nor the database are lost and cannot be counted: at most 8,192 actions (64 MiB) in "
            + "all, plus explosion parts still waiting in the explosion part backlog.";

    /**
     * design-02 11.1 with MAJOR-3 (r2): the open lines, each once per start, logged right after the open and before
     * the position check, so a paused or refused check never loses them. The "ready" line follows a settled check.
     */
    private void logSpoolOpenLines(DiskSpool diskSpool, DiskSpool.OpenOutcome outcome) {
        for (SpoolFile.MovedSet moved : outcome.movedNow()) {
            if (!SpoolFile.ASIDE.equals(moved.kind())) continue;
            SpoolFile.Meta m = moved.metaOrNull();
            LOGGER.error("EventTrail disk spool file was {} and was moved to spool/aside/{}; {} waiting actions in it will "
                    + "not be written. A new spool was started.", moved.reason(), moved.folder(),
                    m == null ? "an unknown number of" : Long.toString(m.waitingFrames()));
        }
        if (outcome.notClosedCleanly()) {
            if (outcome.closedUnmarked()) {
                // MINOR-5 (r2): the last run closed it on purpose without the clean mark (its own WARN or ERROR said
                // why); the close checkpointed the -wal, so nothing stored on disk is missing.
                LOGGER.warn("EventTrail disk spool was closed without its clean mark in the last run (the spool had "
                        + "stopped early). Every action it had stored on disk is kept and will be written; the actions "
                        + "it could not store were counted in that run.");
            } else if (outcome.walMissing()) {
                LOGGER.warn(SPOOL_WAL_MISSING_WARN);
            } else {
                LOGGER.warn(SPOOL_NOT_CLOSED_CLEANLY_WARN);
            }
        }
        if (diskSpool.asideSets > 0) {
            String counted = diskSpool.asideActions + " actions"
                    + (diskSpool.asideUnknownSets > 0 ? ", plus " + diskSpool.asideUnknownSets + " set(s) with an unknown count" : "");
            int days = diskSpool.settings().retentionDays();
            String keep = diskSpool.settings().deleteKeptAfterRetention() && days > 0
                    ? " They are deleted once older than retention.days (" + days + " days)."
                    : " They are kept until you delete them" + (days <= 0 ? " (retention is off)." : ".");
            String old = diskSpool.asideOldSets > 0 && !(diskSpool.settings().deleteKeptAfterRetention() && days > 0)
                    ? " " + diskSpool.asideOldSets + " of them are older than retention.days (" + days
                    + " days) and hold player data past it." : "";
            LOGGER.warn("EventTrail disk spool: {} set(s) in spool/aside ({} MiB, {}) will never be written.{}{}",
                    diskSpool.asideSets, diskSpool.asideBytes / (1024L * 1024L), counted, keep, old);
        }
        if (diskSpool.foldersWithoutDatabase > 0) {
            // R-9 (r9): not sets: never written or restored. spf1 MINOR-1, ADDENDUM-01: deleted after retention.days
            // with the kept sets when retention is on; else left for the operator.
            // spf1 r3: all are plain folders in a plain spool/<kind>; links and unreadable entries are WARNed below.
            int days = diskSpool.settings().retentionDays();
            String fate = diskSpool.settings().deleteKeptAfterRetention() && days > 0
                    ? "they are not sets and are never written or restored. They are deleted once older than "
                            + "retention.days (" + days + " days)."
                    : "they are not sets, are never written or restored, and are left in place"
                            + (days <= 0 ? " (retention is off)." : ".");
            LOGGER.warn("EventTrail disk spool: {} folder(s) in spool/aside or spool/held hold no spool.db (a move or "
                    + "restore was interrupted); {}", diskSpool.foldersWithoutDatabase, fate);
        }
        // spf1 r3 rulings 1-3: never silent, whatever retention is: one WARN for each spool/<kind> that is not a plain
        // folder or cannot be read, and one for its links and one for its unreadable entries. None is searched for
        // folders without spool.db or deleted by retention; r4: a held set behind a junction can still be restored.
        for (SpoolFile.KeptFolderScan scan : diskSpool.keptFolderScans) {
            if (scan.parentNotPlain()) LOGGER.warn(DiskSpool.KIND_NOT_PLAIN_WARN, scan.kind());
            if (scan.parentUnreadable()) LOGGER.warn(DiskSpool.KIND_UNREADABLE_WARN, scan.kind());
            if (scan.links() > 0) LOGGER.warn(DiskSpool.ENTRY_LINK_WARN, scan.links(), scan.kind());
            if (scan.unreadable() > 0) LOGGER.warn(DiskSpool.ENTRY_UNREADABLE_WARN, scan.unreadable(), scan.kind());
        }
        logHeldIfChanged(diskSpool);
        if (diskSpool.heldEmptySets > 0) {
            // spf1 r2 MINOR-1: the held WARN is for actions to write; held sets with nothing waiting (such as the
            // spool a restore replaced) are counted once per start, so they are never silent either.
            int days = diskSpool.settings().retentionDays();
            String keep = diskSpool.settings().deleteKeptAfterRetention() && days > 0
                    ? " They are deleted once older than retention.days (" + days + " days)."
                    : " They are kept until you delete them" + (days <= 0 ? " (retention is off)." : ".");
            LOGGER.info("EventTrail disk spool: {} held set(s) in spool/held have no waiting actions (such as the "
                    + "spool a restore replaced); nothing in them is written.{}", diskSpool.heldEmptySets, keep);
        }
    }

    /** The held line: at open, and again after a check that held the live spool (m-5) or restored a held set. */
    private void logHeldIfChanged(DiskSpool diskSpool) {
        long sets = diskSpool.heldSets;
        long held = diskSpool.heldActions;
        long unknown = diskSpool.heldUnknownSets;
        // spf1 MINOR-2: keyed on all three counts, so a held set whose count is unknown is never silent. r2 MINOR-1:
        // logged only when there are actions to write or an unknown count; empty held sets get the INFO at open.
        String key = sets + "/" + held + "/" + unknown;
        if (key.equals(spoolLoggedHeld)) return;
        spoolLoggedHeld = key;
        if (held > 0 || unknown > 0) {
            // R-12 (r9): ADDENDUM-01: held sets are deleted after retention.days when retention is on.
            int days = diskSpool.settings().retentionDays();
            String keep = diskSpool.settings().deleteKeptAfterRetention() && days > 0
                    ? " Held sets are deleted once older than retention.days (" + days + " days), written or not."
                    : " They are kept until written or deleted" + (days <= 0 ? " (retention is off)." : ".");
            if (unknown == 0) {
                LOGGER.warn("EventTrail disk spool: {} action(s) captured for another server id or database are held in "
                        + "spool/held; start with those settings against that database to write them.{}", held, keep);
            } else {
                LOGGER.warn("EventTrail disk spool: {} action(s), plus {} set(s) with an unknown count, captured for "
                        + "another server id or database are held in spool/held; start with those settings against "
                        + "that database to write them. A set with an unknown count has no readable set.properties (a "
                        + "move was interrupted) and is never restored.{}", held, unknown, keep);
            }
        }
    }

    /** design-02 11.1: the "ready" line, once the position check has settled (at init or on a later check). */
    private void logSpoolReadyLine(DiskSpool diskSpool) {
        long limit = diskSpool.settings().capBytes() / (1024L * 1024L);
        DiskSpool.SpoolStats stats = diskSpool.stats();
        if (stats.waitingFrames() > 0) {
            LOGGER.info("EventTrail disk spool ready (limit {} MiB); {} actions ({} MiB) from an earlier run are waiting "
                    + "and will be written.", limit, stats.waitingFrames(), stats.waitingBytes() / (1024L * 1024L));
        } else {
            LOGGER.info("EventTrail disk spool ready (limit {} MiB).", limit);
        }
    }

    /**
     * Writer thread, each loop: the paused-check retry (30 s), the NORMAL checkpoint gate (5 s), the paused-replay
     * retry (5 s). Returns true when a spool unit is due. Never throws.
     */
    private boolean spoolWriterHousekeeping() {
        DiskSpool diskSpool = spool;
        try {
            if (diskSpool.state() != DiskSpool.State.READY) return false;
            long now = System.nanoTime();
            if (!diskSpool.positionSettled()) {
                if (now - spoolSettleRetryNotBeforeNanos >= 0) {
                    spoolSettleRetryNotBeforeNanos = now + SPOOL_SETTLE_RETRY_NANOS;
                    try {
                        if (settleSpool()) {
                            LOGGER.info("EventTrail disk spool: the database position check succeeded; replay resumes.");
                            logSpoolReadyLine(diskSpool);
                            logHeldIfChanged(diskSpool);
                        }
                    } catch (SQLException failure) {
                        if (!isSpoolLockWait(failure) && !isRetryableWriteFailure(failure)) {
                            diskSpool.turnOff("position-check-refused", failure);
                        }
                    } catch (Exception failure) {
                        diskSpool.turnOff("unexpected-failure", failure);
                    }
                }
                return false;
            }
            // design-02 6.1: SQLite target at NORMAL: confirm by a full checkpoint, then the spool may delete.
            if (spoolCommitNeedsCheckpoint() && spoolCommittedPosition > spoolCheckpointedPosition
                    && (now - spoolLastCheckpointNanos >= SPOOL_CHECKPOINT_INTERVAL_NANOS
                    || spoolCommittedPosition >= diskSpool.publishedHighWater())) {
                spoolLastCheckpointNanos = now;
                long x = spoolCommittedPosition;
                try {
                    if (spoolCheckpointConfirms()) {
                        spoolCheckpointedPosition = x;
                        diskSpool.requestDeleteThrough(x);
                    }
                } catch (SQLException | RuntimeException notConfirmed) {
                    // Tried again in 5 s; frames stay.
                }
            }
            if (diskSpool.replayStoppedReason() != null) return false;
            if (diskSpool.replayPaused() && now - spoolRetryNotBeforeNanos < 0) return false;
            return diskSpool.publishedHighWater() > spoolKnownPosition;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException unexpected) {
            diskSpool.stopReplay("unexpected-failure");
            return false;
        }
    }

    /** At stop: staged actions the spool thread has not committed yet (they will be written by the next start). */
    private boolean spoolStagedAwaitingCommit() {
        DiskSpool diskSpool = spool;
        return diskSpool != null && diskSpool.state() == DiskSpool.State.READY && diskSpool.positionSettled()
                && diskSpool.replayStoppedReason() == null && !diskSpool.replayPaused()
                && diskSpool.damagedReason() == null && diskSpool.stagedActions() > 0;
    }

    /** One frame of a spool unit: decoded rows, or null rows when it is unreadable and only advanced past. */
    private record SpoolFrame(long seq, int rowCount, long minRowTs, List<SchemaV1Row> rows, long retainedBytes,
                              boolean thisRun) {}

    /** A spool unit: frames m+1..through (some may have been skipped as unreadable), advanced m -> through. */
    private record SpoolUnit(long from, long through, List<SpoolFrame> frames) {}

    /** Tests only (MAJOR-2): runs on the writer between its one high-water read and the frame read. */
    void beforeSpoolFrameRead() {
    }

    /**
     * Tests only (r2, the S2 in-doubt active unit): runs once the commit counts as sent, right before it; a failure
     * thrown here is a commit whose outcome is unknown.
     */
    void beforeWriteCommit(boolean spoolUnit) throws SQLException {
    }

    /**
     * Tests only (R-16, r9): runs right after the commit returned, before it counts as confirmed; a failure thrown here
     * is a commit that landed but whose caller saw a failure, as when the connection dies after the server committed.
     */
    void afterWriteCommit(boolean spoolUnit) throws SQLException {
    }

    /** design-02 4.5: the next unit (at most 100 rows, or one larger frame alone); null when nothing is readable. */
    private SpoolUnit nextSpoolUnit(DiskSpool diskSpool) throws SQLException {
        long m = spoolKnownPosition;
        // MAJOR-2 (r2): the high-water is read once, before the frame read, and bounds both the read and the
        // advance-only unit below; a frame published in between is read by the next unit, never advanced past.
        long published = diskSpool.publishedHighWater();
        beforeSpoolFrameRead();
        List<SpoolFile.StoredFrame> stored = diskSpool.readFrames(m, published, WRITE_BATCH_SIZE + 1);
        if (stored.isEmpty()) {
            // Every frame in (m, published] is marked unreadable (or none is left): an advance-only transaction moves
            // past them.
            return published > m ? new SpoolUnit(m, published, List.of()) : null;
        }
        List<SpoolFrame> frames = new ArrayList<>();
        int rows = 0;
        long through = m;
        for (SpoolFile.StoredFrame frame : stored) {
            if (spoolKnownUnreadable.contains(frame.seq())) {
                through = frame.seq();
                continue;
            }
            List<SchemaV1Row> decoded = null;
            if (SpoolFrameCodec.crc(frame.seq(), frame.rowCount(), frame.payload()) == frame.crc32c()) {
                try {
                    decoded = SpoolFrameCodec.decode(frame.payload(), frame.rowCount());
                } catch (SpoolFrameCodec.UnreadableFrame unreadable) {
                    decoded = null;
                }
            }
            boolean thisRun = frame.seq() > diskSpool.carriedInThrough;
            if (decoded == null) {
                if (diskSpool.damagedReason() != null) {
                    // L14 with m-5: the first CRC failure ends read-only replay after an in-run failure. R-4 (r9): the
                    // good frames before it are written first; replay stops when the bad frame comes first, i.e. at
                    // once when there are none, or at the next unit once this partial one has resolved.
                    if (frames.isEmpty()) {
                        diskSpool.stopReplay("read-only-check-failed");
                        return null;
                    }
                    return new SpoolUnit(m, through, frames);
                }
                spoolKnownUnreadable.add(frame.seq());
                diskSpool.reportUnreadable(frame.seq(), frame.rowCount(), thisRun);
                through = frame.seq();
                break;
            }
            if (!frames.isEmpty() && rows + decoded.size() > WRITE_BATCH_SIZE) break;
            long retained = 0L;
            for (SchemaV1Row row : decoded) retained += SchemaV1RowCodec.retainedBytes(row);
            // R-5 (r9): the age test uses the decoded rows' own earliest timestamp (inside the CRC), never the stored
            // min_row_ts column, which the CRC does not cover.
            frames.add(new SpoolFrame(frame.seq(), frame.rowCount(), SpoolFrameCodec.minTimestamp(decoded), decoded,
                    retained, thisRun));
            rows += decoded.size();
            through = frame.seq();
            if (rows >= WRITE_BATCH_SIZE) break;
        }
        return through > m ? new SpoolUnit(m, through, frames) : null;
    }

    /** Returns false only when the writer must stop (deadline, forced stop, identified ownership refusal). */
    private boolean replaySpoolUnit() {
        DiskSpool diskSpool = spool;
        SpoolUnit unit;
        try {
            unit = nextSpoolUnit(diskSpool);
        } catch (SQLException readFailure) {
            // SQLITE_CORRUPT, NOTADB, IOERR on a read: an in-run failure (7.3); the read-only replay ends too.
            diskSpool.requestDamage("read-failed");
            diskSpool.stopReplay("read-failed");
            return true;
        }
        if (unit == null) return true;
        return attemptSpoolUnit(unit, unit.frames().size() > 1);
    }

    private long spoolExpiryCutoff(DiskSpool diskSpool) {
        int days = diskSpool.settings().retentionDays();
        if (days <= 0) return Long.MIN_VALUE;
        return diskSpool.settings().clock().getAsLong() - TimeUnit.DAYS.toMillis(days) - SPOOL_EXPIRY_MARGIN_MILLIS;
    }

    /**
     * design-02 6.4 to 6.7 and design-01 7.2: one spool unit until it is resolved. The age test runs before every
     * attempt (M-4). After any failure or a 0-row advance the position is re-read and classified (M-3): P = m
     * retries exactly the same range, an own late commit resolves through P, anything else is out of step. A lock
     * wait on the advance pauses replay (M-2); a definite refusal of the position statement stops it (m-4); a row
     * refusal isolates the frames and drops a poison frame after the bounded tries (L9).
     */
    private boolean attemptSpoolUnit(SpoolUnit unit, boolean mayIsolate) {
        DiskSpool diskSpool = spool;
        String component = SpoolPosition.component(diskSpool.metaIdHexForWriter());
        int attempts = 0;
        int boundedAttempts = 0;
        long retryDelay = initialRetryDelayMillis();
        int unitRows = 0;
        for (SpoolFrame frame : unit.frames()) unitRows += frame.rowCount();
        while (!forceWriterStop && !shutdownDeadlineExpired()) {
            // M-2/M-3: a stopped replay never keeps the writer from memory units; the frames stay for the next start.
            if (diskSpool.replayStoppedReason() != null) return true;
            attempts++;
            flushDroppedBatchLog(false);
            long cutoff = spoolExpiryCutoff(diskSpool);
            List<PendingGroup> groups = new ArrayList<>();
            Set<Long> expired = new HashSet<>();
            for (SpoolFrame frame : unit.frames()) {
                if (frame.minRowTs() < cutoff) expired.add(frame.seq());
                else groups.add(new PendingGroup(frame.rows(), frame.retainedBytes()));
            }
            try {
                writeBatchTransaction(groups, new SpoolAdvance(component, unit.from(), unit.through()));
                markDatabaseSuccess();
                logWriteRecoveryIfNeeded(attempts, unitRows);
                retryingWrites.set(false);
                publishWriteObservation();
                SpoolKillPoint.reach(SpoolKillPoint.AFTER_TARGET_COMMIT);
                resolveSpoolUnit(diskSpool, unit, unit.through(), expired);
                return true;
            } catch (BatchWriteFailure failure) {
                if (forceWriterStop || shutdownDeadlineExpired()) return false;
                var identified = EventTrailFailure.find(failure);
                if (identified.isPresent()) {
                    synchronized (writeLifecycleLock) { writerFailure = identified.get(); }
                    return false;
                }
                SQLException cause = failure.getCause();
                if (failure.commitSent()) {
                    spoolInDoubtEnds.incrementAndGet();
                    spoolOwnInDoubtEnds.add(unit.through());
                    spoolOwnInDoubtExpired.put(unit.through(), Set.copyOf(expired));
                }
                if (cause instanceof SpoolSessionLimitConnectionFailed) {
                    // R-10 (r9): the connection failed while the session limit was being set: an outage, not a
                    // refusal (the limit stays on for the run). The connection was evicted. Only the auto-commit
                    // switch and the limit's own read or SET were sent; no advance, row or commit, so the unit is
                    // retried, with the limit, like any outage.
                    if (!spoolSessionLimitConnectionWarned) {
                        spoolSessionLimitConnectionWarned = true;
                        LOGGER.warn("EventTrail disk spool: the database connection failed while the session time "
                                + "limit for spool writes was being set ({}); the connection is dropped and the spool "
                                + "write is retried. Recording continues.", sqlStateOf(cause.getCause()));
                    }
                    retryingWrites.set(true);
                    publishWriteObservation();
                    logRetryableWriteFailure(unitRows, writeFailureDiagnostic(failure));
                    if (!sleepBeforeRetry(retryDelay)) return false;
                    retryDelay = Math.min(maxRetryDelayMillis(), Math.max(retryDelay + 1L, retryDelay * 2L));
                    continue;
                }
                if (cause instanceof SpoolAdvanceLockBoundConnectionFailed) {
                    // N-2 (r3): the connection failed while the short lock wait was being set: an outage, not a
                    // refusal. The connection was evicted. What the unit had sent by then: the auto-commit switch;
                    // unless the session limit was already refused, the session limit's read and SET (its restore
                    // in the finally can add one more WARN); and the lock wait's own read or SET that failed. No
                    // advance, no row and no commit was sent, so the unit is retried, with the bound, like any
                    // outage.
                    if (!spoolAdvanceLockBoundConnectionWarned) {
                        spoolAdvanceLockBoundConnectionWarned = true;
                        LOGGER.warn("EventTrail disk spool: the database connection failed while the short lock wait "
                                + "for the spool position was being set ({}); the connection is dropped and the "
                                + "spool write is retried. Recording continues.", sqlStateOf(cause.getCause()));
                    }
                    retryingWrites.set(true);
                    publishWriteObservation();
                    logRetryableWriteFailure(unitRows, writeFailureDiagnostic(failure));
                    if (!sleepBeforeRetry(retryDelay)) return false;
                    retryDelay = Math.min(maxRetryDelayMillis(), Math.max(retryDelay + 1L, retryDelay * 2L));
                    continue;
                }
                if (cause instanceof SpoolAdvanceLockBoundRefused) {
                    // N-2 (r3): reached only for a refusal by the database (the connection was still valid).
                    spoolAdvanceLockBoundRefused = true;
                    if (!spoolAdvanceLockBoundWarned) {
                        spoolAdvanceLockBoundWarned = true;
                        LOGGER.warn("EventTrail disk spool: the database did not accept a short lock wait for the spool "
                                + "position ({}); a lock held on it pauses replay only after the write query timeout. "
                                + "Recording continues.", sqlStateOf(cause.getCause()));
                    }
                    continue;
                }
                if (cause instanceof SpoolSessionLimitRefused) {
                    spoolSessionLimitRefused = true;
                    if (!spoolSessionLimitWarned) {
                        spoolSessionLimitWarned = true;
                        LOGGER.warn("EventTrail disk spool: the database did not accept a session time limit ({}); a "
                                + "database session that dies during a spool write can pause replay until the database "
                                + "ends it. Recording continues.", sqlStateOf(cause.getCause()));
                    }
                    continue;
                }
                boolean zeroRows = cause instanceof SpoolZeroRowAdvance;
                // MAJOR-4 (r2): a query timeout on the advance is a lock wait too (a server or proxy that refused the
                // 2 s bound, or a bound longer than it): paused, never retried in place for ever.
                if (failure.inAdvance() && !zeroRows
                        && (isSpoolLockWait(cause) || EventTrailDiagnostics.isTimeout(cause))) {
                    // M-2 step 3: paused; memory units flow first; this unit is retried at most every 5 s.
                    diskSpool.setReplayPaused(true);
                    spoolRetryNotBeforeNanos = System.nanoTime() + SPOOL_PAUSE_RETRY_NANOS;
                    return true;
                }
                // m-4: the position statement itself was refused (a permission refusal on MySQL/PostgreSQL, any other
                // non-retryable refusal on its own row): replay stops; the frames are never charged as poison.
                if (failure.inAdvance() && !zeroRows && !isRetryableWriteFailure(cause)
                        && (isDefiniteRowRefusal(cause) || failure.connectionLostBy() == null)) {
                    diskSpool.stopReplay("position-refused");
                    return true;
                }
                SpoolPosition.Reread reread = rereadSpoolPosition(component);
                if (reread.kind() == SpoolPosition.RereadKind.ABSENT) {
                    // R-1 (r9): the row is gone (a failover to a lagging copy, a restore, the table cleared): not
                    // P = m and not an own end, so out of step (6.5). Replay stops; the frames stay, and the next
                    // start holds them (m-5).
                    diskSpool.stopReplay("out-of-step");
                    return true;
                }
                if (reread.kind() == SpoolPosition.RereadKind.PRESENT) {
                    long p = reread.position();
                    SpoolPosition.Resync resync = SpoolPosition.classifyResync(p, unit.from(), spoolOwnInDoubtEnds);
                    if (resync == SpoolPosition.Resync.OWN_COMMIT_LANDED) {
                        spoolOwnCommitLanded.incrementAndGet();
                        // MINOR-2 (r2): the landed attempt's own expired frames are counted as expired, not written.
                        resolveSpoolUnit(diskSpool, unit, p, spoolOwnInDoubtExpired.getOrDefault(p, Set.of()));
                        return true;
                    }
                    if (resync == SpoolPosition.Resync.OUT_OF_STEP) {
                        diskSpool.stopReplay("out-of-step");
                        return true;
                    }
                }
                String diagnostic = writeFailureDiagnostic(failure);
                // R-1 (r9): only a re-read that did not answer keeps the bounded retry.
                boolean retryable = !failure.retrySafe() || zeroRows
                        || reread.kind() == SpoolPosition.RereadKind.READ_FAILED
                        || isRetryableWriteFailure(cause)
                        || (failure.connectionLostBy() != null && !isDefiniteRowRefusal(cause));
                if (retryable) {
                    retryingWrites.set(true);
                    publishWriteObservation();
                    logRetryableWriteFailure(unitRows, diagnostic);
                } else if (mayIsolate && unit.frames().size() > 1) {
                    return isolateSpoolUnit(unit);
                } else if (++boundedAttempts < MAX_PERMANENT_ERROR_ATTEMPTS) {
                    retryingWrites.set(true);
                    publishWriteObservation();
                } else if (unit.frames().isEmpty()) {
                    // An advance-only transaction that keeps being refused: nothing to drop; replay stops (m-4).
                    diskSpool.stopReplay("position-refused");
                    return true;
                } else {
                    // L9: a poison action from the spool is dropped and counted as from memory; the position moves
                    // past it (advance-only), so its frame is deleted once X passes it (n-8). MINOR-3 (r2): it is
                    // charged only once that advance has committed; if the advance stops instead (out of step,
                    // refused, the deadline), the frame stays, uncharged, for the next start.
                    resetWriteRetryClock();
                    retryingWrites.set(false);
                    publishWriteObservation();
                    boolean keepGoing = attemptSpoolUnit(new SpoolUnit(unit.from(), unit.through(), List.of()), false);
                    if (spoolKnownPosition >= unit.through()) {
                        for (SpoolFrame frame : unit.frames()) {
                            if (frame.thisRun()) {
                                permanentFailedWrites.addAndGet(frame.rowCount());
                                diskSpool.poisonThisRunRows.addAndGet(frame.rowCount());
                            } else {
                                diskSpool.carriedInLostActions.incrementAndGet();
                            }
                        }
                        recordDroppedBatch(unitRows, attempts, DropReason.BOUNDED_TRIES_USED, diagnostic);
                    }
                    return keepGoing;
                }
                if (!sleepBeforeRetry(retryDelay)) return false;
                retryDelay = Math.min(maxRetryDelayMillis(), Math.max(retryDelay + 1L, retryDelay * 2L));
            }
        }
        return false;
    }

    /** writer-01 3 for spool units: each frame alone, in sequence order; then any unreadable tail advance-only. */
    private boolean isolateSpoolUnit(SpoolUnit unit) {
        for (SpoolFrame frame : unit.frames()) {
            if (spool.replayStoppedReason() != null || spool.replayPaused()) return true;
            if (frame.seq() <= spoolKnownPosition) continue;
            SpoolUnit single = new SpoolUnit(spoolKnownPosition, frame.seq(), List.of(frame));
            if (!attemptSpoolUnit(single, false)) return false;
            if (spoolKnownPosition < frame.seq()) return true;
        }
        if (spoolKnownPosition < unit.through()) {
            return attemptSpoolUnit(new SpoolUnit(spoolKnownPosition, unit.through(), List.of()), false);
        }
        return true;
    }

    /** The unit is committed through {@code through}: count, move the position, ask for the delete (6.1). */
    private void resolveSpoolUnit(DiskSpool diskSpool, SpoolUnit unit, long through, Set<Long> expired) {
        long writtenThisRun = 0, expiredActions = 0, expiredRows = 0;
        for (SpoolFrame frame : unit.frames()) {
            if (frame.seq() > through) continue;
            if (expired.contains(frame.seq())) {
                expiredActions++;
                expiredRows += frame.rowCount();
                if (frame.thisRun()) diskSpool.expiredThisRunRows.addAndGet(frame.rowCount());
            } else if (frame.thisRun()) {
                writtenThisRun += frame.rowCount();
            } else {
                diskSpool.carriedInWrittenActions.incrementAndGet();
                diskSpool.carriedInWrittenRows.addAndGet(frame.rowCount());
            }
        }
        if (writtenThisRun > 0) {
            synchronized (writeLifecycleLock) {
                successfulWrites.addAndGet(writtenThisRun);
                successfulWriteBatches.incrementAndGet();
            }
            diskSpool.writtenFromSpoolRows.addAndGet(writtenThisRun);
        }
        if (expiredActions > 0) {
            diskSpool.expiredActions.addAndGet(expiredActions);
            diskSpool.expiredRows.addAndGet(expiredRows);
            if (!spoolExpiredWarned) {
                spoolExpiredWarned = true;
                LOGGER.warn("EventTrail disk spool: {} waiting action(s) ({} rows) were older than retention.days and "
                        + "were not written.", expiredActions, expiredRows);
            }
        }
        spoolKnownPosition = through;
        spoolCommittedPosition = through;
        spoolOwnInDoubtEnds.clear();
        spoolOwnInDoubtExpired.clear();
        spoolKnownUnreadable.removeIf(seq -> seq <= through);
        diskSpool.setReplayPaused(false);
        if (!spoolCommitNeedsCheckpoint()) {
            spoolCheckpointedPosition = through;
            diskSpool.requestDeleteThrough(through);
        }
    }

    /**
     * n-10: the re-read has its own MutationScope, native gate and cancellation registration, as the insert has.
     * R-1 (r9): tri-state: a row found is PRESENT, a read that answered with no row is ABSENT, and a read that failed
     * (or was cancelled) is READ_FAILED; a null no longer means both.
     */
    private SpoolPosition.Reread rereadSpoolPosition(String component) {
        MutationScope mutation = null;
        Connection connection = null;
        try {
            mutation = beginMutation(MutationKind.AUDIT, writerShutdownDeadlineNanos);
            connection = connection();
            mutation.enterNative();
            activeWriteConnection = connection;
            PreparedStatement read = connection.prepareStatement(SpoolPosition.READ_SQL);
            try (read) {
                if (forceWriterStop) activeWriteOperation.cancel();
                activeWriteOperation.register(read, connection);
                read.setQueryTimeout(writeQueryTimeoutSeconds());
                read.setString(1, component);
                try (ResultSet rs = read.executeQuery()) {
                    return rs.next() ? SpoolPosition.Reread.present(rs.getLong(1)) : SpoolPosition.Reread.ABSENT;
                }
            } finally {
                activeWriteOperation.clear(read);
            }
        } catch (SQLException | RuntimeException unavailable) {
            return SpoolPosition.Reread.READ_FAILED;
        } finally {
            activeWriteConnection = null;
            if (connection != null) {
                try { connection.close(); } catch (SQLException | RuntimeException ignored) { /* pool-owned */ }
            }
            if (mutation != null) mutation.close();
        }
    }

    /**
     * N-3 (r3): true when the group at the head of the memory queue was admitted past waiting frames. Groups admitted
     * before the latch (older than every waiting frame) are then all written, so a spool unit may go ahead of the rest.
     * Writer thread only (the only consumer), so the head cannot change under it except by a producer's append.
     */
    private boolean headGroupAdmittedPast() {
        PendingGroup head = writeQueue.peek();
        return head != null && head.admittedPast();
    }

    private static String sqlStateOf(Throwable failure) {
        return failure instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : "unknown";
    }

    /**
     * The writer's terminal path. S2 (design-02 6.8): at a stop, groups still queued (never part of an attempt) and
     * the active unit when its last attempt is proven absent move into the spool's staging, waiting for room until
     * the shutdown deadline, and are resolved SAVED_AT_STOP; the rest are SHUTDOWN_LOST as today.
     */
    private int saveOrDiscardPendingAtShutdown(List<PendingGroup> activeBatch) {
        DiskSpool diskSpool = spool;
        if (diskSpool == null || !closeStarted || diskSpool.state() != DiskSpool.State.READY
                || !diskSpool.positionSettled() || diskSpool.damagedReason() != null) {
            return discardPendingAtShutdown(activeBatch);
        }
        List<PendingGroup> queued = new ArrayList<>();
        writeQueue.drainTo(queued);
        List<PendingGroup> candidates = new ArrayList<>();
        int lost = 0;
        if (activeBatch != null && !activeBatch.isEmpty()) {
            if (activeUnitProvenAbsent) {
                candidates.addAll(activeBatch);
            } else {
                lost += rowCount(activeBatch);
                resolvePending(activeBatch, WriteResolution.SHUTDOWN_LOST);
            }
            activeBatch.clear();
        }
        candidates.addAll(queued);
        boolean spoolTakes = true;
        long deadline = writerShutdownDeadlineNanos;
        for (PendingGroup group : candidates) {
            List<PendingGroup> one = new ArrayList<>(List.of(group));
            boolean saved = false;
            if (spoolTakes) {
                try {
                    saved = diskSpool.stageAtStop(group.rows(), group.retainedBytes(), deadline);
                } catch (RuntimeException failure) {
                    saved = false;
                }
                if (!saved) spoolTakes = false;
            }
            if (saved) {
                resolvePending(one, WriteResolution.SAVED_AT_STOP);
            } else {
                lost += group.size();
                resolvePending(one, WriteResolution.SHUTDOWN_LOST);
            }
        }
        return lost;
    }

    private void runWriter() {
        List<PendingGroup> activeBatch = null;
        Throwable terminalFailure = null;
        try {
            while (!forceWriterStop) {
                // The existing bounded idle poll also observes backend revocation.
                // No storage/native/cancellation lock is held while publishing below.
                if (refreshBackendRestriction() != null) break;
                // design-02 5.2 / L6: at stop the writer replays memory, then the spool, until the deadline.
                boolean spoolDue = spool != null && spoolWriterHousekeeping();
                if (writeShutdownRequested && pendingWrites.get() == 0 && !spoolDue && !spoolStagedAwaitingCommit()) break;
                if (shutdownDeadlineExpired()) break;
                // af-1 F7: drops held back by the rate limit are reported once their interval has passed.
                flushDroppedBatchLog(false);

                // design-01 7.1: memory first (it holds the older actions). Only while the group at the head of the
                // memory queue was admitted past waiting frames (the exception row, MAJOR-1; N-3, r3: the queue is
                // FIFO, so every group admitted before the latch is then written) does a spool unit go after 8
                // memory units in a row; otherwise the spool waits for an empty memory queue. A unit is all-memory
                // or all-spool.
                if (spoolDue && (writeQueue.isEmpty() || (pendingAdmittedPastGroups.get() > 0
                        && headGroupAdmittedPast()
                        && memoryUnitsWhileSpoolWaits >= SPOOL_STARVATION_MEMORY_UNITS))) {
                    memoryUnitsWhileSpoolWaits = 0;
                    if (!replaySpoolUnit()) break;
                    continue;
                }

                PendingGroup first;
                try {
                    first = writeQueue.poll(spoolDue ? 0L : WRITE_QUEUE_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (forceWriterStop || shutdownDeadlineExpired()) break;
                    continue;
                }
                if (first == null) continue;
                memoryUnitsWhileSpoolWaits = spoolDue ? memoryUnitsWhileSpoolWaits + 1 : 0;

                // Whole groups only (writer-01 2.5): at most WRITE_BATCH_SIZE rows, or exactly one group that is
                // larger on its own. A group that would cross the limit waits for the next batch.
                activeBatch = new ArrayList<>();
                activeBatch.add(first);
                int batchRows = first.size();
                while (batchRows < WRITE_BATCH_SIZE) {
                    PendingGroup next = writeQueue.peek();
                    if (next == null || next.size() > WRITE_BATCH_SIZE - batchRows) break;
                    PendingGroup taken = writeQueue.poll();
                    // Taken first, so a violation below still resolves (discards) whatever was removed.
                    if (taken != null) activeBatch.add(taken);
                    if (taken != next) {
                        throw new IllegalStateException("EventTrail write queue has a second consumer");
                    }
                    batchRows += taken.size();
                }

                batchAssembled(Collections.unmodifiableList(activeBatch));
                activeUnitProvenAbsent = true;
                if (!persistWithRetry(activeBatch)) break;
                activeBatch = null;
            }
        } catch (Throwable error) {
            terminalFailure = error;
        } finally {
            // Linearize terminal admission with tryLog before draining. Thread
            // liveness is not admission authority while this finally still runs.
            try {
                EventTrailFailure known;
                synchronized (writeLifecycleLock) {
                    known = backendRestriction != null ? backendRestriction : writerFailure;
                }
                EventTrailFailure terminalReason = known != null ? known : closeStarted ? null
                        : EventTrailFailure.from(terminalFailure, EventTrailFailure.Phase.WRITER, failureBackend);
                synchronized (writeLifecycleLock) {
                    acceptingWrites = false;
                    writerTerminated = true;
                    writerFailure = terminalReason;
                    writerStateVersion++;
                }
                retryingWrites.set(false);
                publishWriteObservation();
                int lost = saveOrDiscardPendingAtShutdown(activeBatch);
                // af-1 F7: no drop of this run stays unreported when the writer ends (outside the rate limit).
                flushDroppedBatchLog(true);
                if (terminalFailure != null) {
                    logUnexpectedStorageFailure("run the database writer", terminalFailure);
                }
                if (lost > 0) {
                    LOGGER.error(
                            "EventTrail {} writer stopped with {} accepted audit entr{} not commit-confirmed. " +
                                    "Their in-memory reservations were discarded; an uncertain transaction may still have database rows.",
                            getBackendName(), lost, lost == 1 ? "y" : "ies");
                }
            } finally {
                writerNativeWorkAcknowledged = true;
                backendOperationFinished();
            }
        }
    }

    /** How one unit of whole groups ended (writer-01 3). */
    private enum UnitOutcome {
        /** Every group of the unit was resolved: written, or permanently failed. */
        DONE,
        /** A retry-safe refusal of a batch of several groups: each group is written alone next. */
        ISOLATE,
        /** Shutdown deadline, forced stop or an identified ownership/lifecycle refusal: the unit stays unresolved. */
        STOP
    }

    /**
     * Returns false only when the writer must stop (shutdown deadline, forced stop, or a positively identified
     * ownership/lifecycle refusal); the groups still in {@code batch} are then left for the terminal discard.
     * A retry-safe failure that is not an outage, lock wait, time limit or database-wide refusal splits a batch
     * of several groups into its groups, each written alone in admission order, so one refused action costs only
     * its own rows (writer-01 3). Each group leaves {@code batch} in the call that resolves it.
     */
    private boolean persistWithRetry(List<PendingGroup> batch) {
        UnitOutcome outcome = attemptUnit(batch, batch.size() > 1);
        if (outcome == UnitOutcome.STOP) return false;
        if (outcome == UnitOutcome.ISOLATE) {
            while (!batch.isEmpty()) {
                int before = batch.size();
                // resolvePending clears the sub-list, which removes that group from batch.
                if (attemptUnit(batch.subList(0, 1), false) == UnitOutcome.STOP) return false;
                if (batch.size() >= before) {
                    throw new IllegalStateException("EventTrail isolated write group was not resolved");
                }
            }
        }
        return true;
    }

    /**
     * Writes {@code unit} (whole groups, admission order) until it is resolved or the writer must stop. Outage,
     * lock-wait, time-limit and database-wide refusal failures are retried without limit; so is a retry-safe
     * failure whose rollback failed with a connection outage, unless the failure itself is a definite row refusal
     * (SQLState class 22, 23, 42 or 45 in its chain; fix-03 A). Any other retry-safe
     * failure returns {@link UnitOutcome#ISOLATE} when {@code mayIsolate}, otherwise it is retried a small, bounded
     * number of times so one malformed action cannot stall every later write; only those failures count toward
     * the bound (A1). A failure that is not retry-safe (commit outcome unknown, or an unproven discard) resolves
     * the whole unit as permanently failed at once: it is never isolated or retried.
     */
    private UnitOutcome attemptUnit(List<PendingGroup> unit, boolean mayIsolate) {
        // Rows, not groups: the log lines report entries of this unit (writer-01 2.5, WR6).
        int unitRows = rowCount(unit);
        int attempts = 0;
        // Only bounded-path failures count toward MAX_PERMANENT_ERROR_ATTEMPTS; retries without limit do not (A1).
        int boundedAttempts = 0;
        long retryDelay = initialRetryDelayMillis();

        while (!forceWriterStop && !shutdownDeadlineExpired()) {
            attempts++;
            // af-1 F7: a unit retried for a long time does not hold back the report of earlier drops.
            flushDroppedBatchLog(false);
            try {
                activeUnitProvenAbsent = false;
                writeBatchTransaction(unit, null);
                markDatabaseSuccess();
                logWriteRecoveryIfNeeded(attempts, unitRows);
                retryingWrites.set(false);
                publishWriteObservation();
                return UnitOutcome.DONE;
            } catch (BatchWriteFailure failure) {
                // S2 (design-02 6.8): proven absent only when the attempt failed retry-safe (rolled back or discarded).
                activeUnitProvenAbsent = failure.retrySafe();
                if (forceWriterStop || shutdownDeadlineExpired()) return UnitOutcome.STOP;
                // A positively identified ownership/lifecycle refusal is not a
                // transient connectivity outage and cannot admit more audit data.
                var identified = EventTrailFailure.find(failure);
                if (identified.isPresent()) {
                    synchronized (writeLifecycleLock) { writerFailure = identified.get(); }
                    return UnitOutcome.STOP;
                }
                boolean retryable = failure.retrySafe() && (isRetryableWriteFailure(failure.getCause())
                        // fix-03 A: the rollback failed with a connection-exception state and no commit was sent, so
                        // the attempt is an outage; a definite row refusal keeps the bounded path even then (hardening).
                        || (failure.connectionLostBy() != null && !isDefiniteRowRefusal(failure.getCause())));
                // Message-free (fix-03 C): states and vendor codes of the chain, and the lost connection if any.
                String diagnostic = writeFailureDiagnostic(failure);
                if (retryable) {
                    retryingWrites.set(true);
                    publishWriteObservation();
                    logRetryableWriteFailure(unitRows, diagnostic);
                } else if (failure.retrySafe() && mayIsolate) {
                    // The failed attempt left nothing (rolled back, or its connection discarded), so writing its
                    // groups again one by one cannot duplicate a row (writer-01 3).
                    return UnitOutcome.ISOLATE;
                } else if (failure.retrySafe() && ++boundedAttempts < MAX_PERMANENT_ERROR_ATTEMPTS) {
                    retryingWrites.set(true);
                    publishWriteObservation();
                    if (claimDiagnostic(OperationalDiagnostic.BOUNDED_BATCH_RETRY)) LOGGER.error(
                            "EventTrail write batch failed using {} storage (attempt {}/{}); retrying {} accepted entr{} " +
                                    "after a bounded delay ({}).",
                            getBackendName(), boundedAttempts, MAX_PERMANENT_ERROR_ATTEMPTS, unitRows,
                            unitRows == 1 ? "y" : "ies", diagnostic);
                } else {
                    resolvePending(unit, WriteResolution.PERMANENT_FAILED);
                    // The unit ended without a commit: a later retry starts its own stall clock (review-01 NIT-3).
                    resetWriteRetryClock();
                    retryingWrites.set(false);
                    publishWriteObservation();
                    // af-1 F7: every drop is reported (rate-limited), not only the first of the run.
                    recordDroppedBatch(unitRows, attempts, failure.retrySafe() ? DropReason.BOUNDED_TRIES_USED
                            : failure.commitSent() ? DropReason.COMMIT_OUTCOME_UNKNOWN
                            : DropReason.CONNECTION_NOT_PROVEN_DISCARDED, diagnostic);
                    return UnitOutcome.DONE;
                }

                if (!sleepBeforeRetry(retryDelay)) return UnitOutcome.STOP;
                retryDelay = Math.min(maxRetryDelayMillis(), Math.max(retryDelay + 1L, retryDelay * 2L));
            }
        }
        return UnitOutcome.STOP;
    }

    /**
     * One transaction of whole groups. With {@code advance} (a spool unit, writer-spool design-01 7.2): the
     * compare-and-set of the position row runs FIRST, then the batch INSERT, then the commit, so the rows and the
     * position commit or roll back together; a spool unit resolves nothing in the memory accounting here.
     */
    private void writeBatchTransaction(List<PendingGroup> batch, SpoolAdvance advance) throws BatchWriteFailure {
        String sql = insertSql();
        Connection connection = null;
        MutationScope mutation = null;
        boolean transactionStarted = false;
        boolean autoCommitOff = false;
        boolean commitStarted = false;
        boolean committed = false;
        boolean inAdvance = false;
        boolean sessionLimitApplied = false;
        boolean advanceLockBoundApplied = false;
        boolean advanceLockBoundRestored = false;
        // N-2 (r3): the connection failed while the bound was being set; the connection is evicted.
        boolean advanceLockBoundConnectionFailed = false;
        // R-10 (r9): the same for the session limit; the connection is evicted too.
        boolean sessionLimitConnectionFailed = false;
        // N-1 (r4): the end of the bound right after the advance failed; it is never retried (see the finally).
        boolean advanceLockBoundEndFailed = false;
        SQLException primaryFailure = null;

        try {
            mutation = beginMutation(MutationKind.AUDIT, writerShutdownDeadlineNanos);
            connection = connection();
            mutation.enterNative();
            activeWriteConnection = connection;
            // From here the connection's transaction state may have changed.
            transactionStarted = true;
            connection.setAutoCommit(false);
            // The switch is confirmed only once the driver call returned (writer-01 2.2 step 1).
            autoCommitOff = true;
            if (advance != null) {
                // M-2 (design-02 6.4 step 1): a session limit around spool transactions only.
                if (!spoolSessionLimitRefused) {
                    try {
                        spoolApplySessionLimit(connection);
                        sessionLimitApplied = true;
                    } catch (SQLException refused) {
                        // R-10 (r9): as for the lock bound (N-2), a connection failure is an outage, not a refusal:
                        // the connection is evicted and the unit retried with the limit.
                        if (isSpoolBoundConnectionFailure(connection, refused)) {
                            sessionLimitConnectionFailed = true;
                            throw new SpoolSessionLimitConnectionFailed(refused);
                        }
                        throw new SpoolSessionLimitRefused(refused);
                    }
                }
                inAdvance = true;
                // MAJOR-4 (r2): at most 2 s on the position row's lock; refused once, the spool runs without it.
                if (!spoolAdvanceLockBoundRefused) {
                    try {
                        spoolApplyAdvanceLockBound(connection);
                        advanceLockBoundApplied = true;
                    } catch (SQLException refused) {
                        // N-2 (r3): a connection failure is an outage, not a refusal: the connection is evicted and
                        // the unit retried with the bound. Only a refusal by the database drops it for the run.
                        if (isSpoolBoundConnectionFailure(connection, refused)) {
                            advanceLockBoundConnectionFailed = true;
                            throw new SpoolAdvanceLockBoundConnectionFailed(refused);
                        }
                        throw new SpoolAdvanceLockBoundRefused(refused);
                    }
                }
                PreparedStatement advanceStatement = connection.prepareStatement(SpoolPosition.ADVANCE_SQL);
                try (advanceStatement) {
                    if (forceWriterStop) activeWriteOperation.cancel();
                    activeWriteOperation.register(advanceStatement, connection);
                    advanceStatement.setQueryTimeout(writeQueryTimeoutSeconds());
                    SpoolPosition.bindAdvance(advanceStatement, advance.component(), advance.from(), advance.to(),
                            System.currentTimeMillis());
                    if (advanceStatement.executeUpdate() != 1) throw new SpoolZeroRowAdvance();
                } finally {
                    activeWriteOperation.clear(advanceStatement);
                }
                if (advanceLockBoundApplied) {
                    advanceLockBoundRestored = spoolEndAdvanceLockBound(connection);
                    advanceLockBoundEndFailed = !advanceLockBoundRestored;
                }
                inAdvance = false;
            }
            if (!batch.isEmpty()) {
            PreparedStatement statement = connection.prepareStatement(sql);
            try (statement) {
                if (forceWriterStop) activeWriteOperation.cancel();
                activeWriteOperation.register(statement, connection);
                int queryTimeoutSeconds = writeQueryTimeoutSeconds();
                statement.setQueryTimeout(queryTimeoutSeconds);
                // Every row of every group, in admission order: one transaction holds whole groups only.
                for (PendingGroup group : batch) {
                    for (SchemaV1Row row : group.rows()) {
                        int i = 1;
                        if (usesServerIdColumn()) statement.setString(i++, serverId);
                        bindEntry(statement, i, row);
                        statement.addBatch();
                    }
                }
                JdbcShutdownDiagnostics.Execution execution = activeWriteOperation.diagnosticExecution(statement);
                int[] results;
                if (execution == null) {
                    results = statement.executeBatch();
                } else {
                    JdbcShutdownDiagnostics.enter(execution, queryTimeoutSeconds);
                    try {
                        results = statement.executeBatch();
                    } catch (SQLException | RuntimeException | Error failure) {
                        JdbcShutdownDiagnostics.returned(execution, failure);
                        throw failure;
                    }
                    JdbcShutdownDiagnostics.returned(execution, null);
                }
                for (int result : results) {
                    if (result == Statement.EXECUTE_FAILED) {
                        throw new BatchUpdateException("JDBC driver reported a failed EventTrail batch element", results);
                    }
                }
            } finally {
                activeWriteOperation.clear(statement);
            }
            }

            if (advance != null) SpoolKillPoint.reach(SpoolKillPoint.REPLAY_TRANSACTION);
            commitStarted = true;
            beforeWriteCommit(advance != null);
            connection.commit();
            // R-16 (r9): tests only; a failure here is a commit that landed but reported a failure (in doubt).
            afterWriteCommit(advance != null);
            committed = true;
            // Commit confirmation must survive later cleanup/diagnostic failure.
            // Retire the batch here so terminal draining cannot resolve it twice.
            if (advance == null) resolvePending(batch, WriteResolution.WRITTEN);
        } catch (SQLException failure) {
            // Kept for the close guard below: a close failure of this uncommitted attempt is suppressed on it.
            primaryFailure = failure;
            boolean retrySafe;
            // Set only below, where no commit was sent: the in-doubt path never reaches a rollback.
            SQLException rollbackFailure = null;
            if (commitStarted) {
                // Commit outcome unknown (in-doubt window): never retried.
                retrySafe = false;
            } else if (!transactionStarted) {
                // Admission, borrow or native gate failed before the transaction state could change.
                retrySafe = true;
            } else if (!autoCommitOff) {
                // The switch to transaction mode failed: the connection's state is uncertain, so it is retry-safe
                // only once it is proven never to be lent again (writer-01 2.2).
                retrySafe = discardUncertainWriteConnection(connection, failure);
            } else {
                // A failed rollback leaves the connection's state uncertain as well: the same discard proof.
                rollbackFailure = rollBack(connection, failure);
                retrySafe = rollbackFailure == null || discardUncertainWriteConnection(connection, failure);
            }
            // fix-03 A: a retry-safe attempt whose rollback failed with a connection outage lost its connection,
            // even when the driver reported the batch itself with a non-outage state (Connector/J's serial batch
            // replaces the 08S01 by a later "statement closed" S1009; an interrupted lock wait is expected to report
            // 70100).
            SQLException connectionLostBy = retrySafe && rollbackFailure != null
                    && EventTrailDiagnostics.isConnectionOutage(rollbackFailure) ? rollbackFailure : null;
            throw new BatchWriteFailure(failure, retrySafe, connectionLostBy, commitStarted, inAdvance);
        } finally {
            try {
                activeWriteConnection = null;
                // M-2: the value read before is restored after commit or rollback.
                String keptSetting = null;
                if (connection != null && sessionLimitApplied && !spoolRestoreSessionLimit(connection)) {
                    keptSetting = "session time limit";
                }
                // MAJOR-4 (r2): the same for the advance's lock bound when the advance did not end normally. N-1
                // (r4): a failed end right after the advance is not retried: MySQL has already dropped the value it
                // read, so a retry would report success with the 2 s lock wait still set.
                if (connection != null && advanceLockBoundApplied && !advanceLockBoundRestored
                        && (advanceLockBoundEndFailed || !spoolRestoreAdvanceLockBound(connection))) {
                    keptSetting = keptSetting == null ? "short lock wait for the spool position"
                            : keptSetting + " and short lock wait for the spool position";
                }
                // r4 (N-1, M-2): a connection that kept a spool setting is evicted and then NOT closed (see
                // evictAfterFailedSpoolRestore); this holds after a commit too, so it can never end the writer.
                boolean evictedWithoutClose = keptSetting != null
                        && evictAfterFailedSpoolRestore(connection, keptSetting, committed);
                // N-2 (r3): a connection that failed while the bound was being set is never lent again. This attempt
                // sent no commit, so a close failure of the evicted connection is suppressed on the batch failure.
                if (connection != null && (advanceLockBoundConnectionFailed || sessionLimitConnectionFailed)
                        && !evictedWithoutClose) {
                    try { evictFromPool(connection); }
                    catch (SQLException | RuntimeException ignored) { /* closed below */ }
                }
                if (connection != null && !evictedWithoutClose) {
                    try {
                        connection.close();
                    } catch (SQLException | RuntimeException closeFailure) {
                        if (!committed && primaryFailure != null) {
                            // Uncommitted attempt with a SQLException batch failure (the only kind that can hold
                            // an evicted connection, whose HikariCP reset can throw a NullPointerException): keep
                            // the batch failure and its retry decision; the close failure is only recorded.
                            addSuppressedFailure(primaryFailure, closeFailure);
                        } else {
                            // As before: an unchecked close failure ends the writer; a checked one after a
                            // confirmed commit is logged.
                            if (closeFailure instanceof RuntimeException unchecked) throw unchecked;
                            if (committed) {
                                LOGGER.warn(
                                        "EventTrail write committed, but its {} connection did not close cleanly ({}).",
                                        getBackendName(), EventTrailDiagnostics.safeFailureSummary(closeFailure));
                            }
                        }
                    }
                }
            } finally {
                if (mutation != null) mutation.close();
            }
        }
    }

    /**
     * Rolls back the failed attempt. Returns null when the rollback succeeded (or there is no connection);
     * otherwise the rollback failure, which is also suppressed on {@code failure}.
     */
    private SQLException rollBack(Connection connection, SQLException failure) {
        if (connection == null) return null;
        try {
            connection.rollback();
            return null;
        } catch (SQLException rollbackFailure) {
            // Never self-suppression (design WR-6; Advisor review AD-2): a driver may rethrow the batch failure.
            addSuppressedFailure(failure, rollbackFailure);
            return rollbackFailure;
        }
    }

    /**
     * Discards a write connection whose transaction state is uncertain (failed switch to transaction mode or
     * failed rollback) and returns true only when it is proven never to be lent again, so the uncommitted batch
     * can be retried without risking a later commit of the failed attempt (writer-01 2.2 step 3). Never throws:
     * it runs inside the writer's {@code catch (SQLException)}, where an escaping RuntimeException would replace
     * the batch failure and end the writer. Every failure it meets is suppressed on {@code failure}; any outcome
     * other than a proven discard returns false (fail closed: dropped, never retried).
     */
    protected boolean discardUncertainWriteConnection(Connection connection, SQLException failure) {
        if (connection == null) return true;
        try {
            if (takesPoolBranch(connection)) {
                // A HikariCP proxy: close() is only a logical return, so the guarantee is eviction. The abort is
                // best effort (ends a live server session at once) and its outcome is not part of the proof.
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailure) {
                    addSuppressedFailure(failure, abortFailure);
                }
                try {
                    evictFromPool(connection);
                    return true;
                } catch (SQLException | RuntimeException evictFailure) {
                    addSuppressedFailure(failure, evictFailure);
                    return false;
                }
            }
            // A connection that is not pooled by HikariCP (unit fixtures on a direct subclass): proven only by a
            // verified physical close.
            ConnectionAbortResult result = abortConnectionFailSafe(connection);
            if (!result.closed() && result.failure() != null) addSuppressedFailure(failure, result.failure());
            return result.closed();
        } catch (RuntimeException unexpected) {
            addSuppressedFailure(failure, unexpected);
            return false;
        }
    }

    /** Seam: true when {@code connection} is a HikariCP proxy (the same class test HikariCP applies). */
    boolean takesPoolBranch(Connection connection) {
        return connection.getClass().getName().startsWith("com.zaxxer.hikari");
    }

    /**
     * Seam: evicts {@code connection} from this storage's pool. Throws when the pool is closed or absent, so the
     * caller cannot count an unevicted proxy as discarded.
     */
    void evictFromPool(Connection connection) throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("EventTrail pool closed; uncertain write connection not discarded");
        }
        source.evictConnection(connection);
    }

    /**
     * r4 (N-1, M-2): a connection that kept a spool session setting (a failed restore) is never lent again, and this
     * never ends the writer, committed or not. While the writer still holds it, HikariCP's
     * {@code evictConnection} is an owner eviction: the pool entry goes straight from in use to removed, so no other
     * borrower can get it, and HikariCP closes the physical connection itself. The logical {@code close()} is then
     * NOT called: after an owner eviction it can only fail (HikariCP 7.0.2 resets the proxy's changed state on the
     * entry's connection, which the eviction already set to null, and throws a NullPointerException; after a commit
     * that would end the writer). Returns true when the connection was evicted (the caller must not close it); false
     * when it is not a HikariCP proxy or the pool is closed, and the caller closes it as before. One WARN per event,
     * naming the setting and the restore failure. Never throws. r5: the start-up position check uses the same path
     * ({@link #spoolInitTransaction}) with its own wording.
     */
    private boolean evictAfterFailedSpoolRestore(Connection connection, String setting, boolean committed) {
        return evictAfterFailedSpoolRestore(connection, setting, spoolTakeRestoreFailure(), "a spool write", committed
                ? "The spool write was committed. Recording continues."
                : "The spool write did not commit and is handled as a failed write. Recording continues.");
    }

    /**
     * r5: {@link #evictAfterFailedSpoolRestore(Connection, String, boolean)} for any spool step: {@code step} names
     * what ran on the connection and {@code then} says what happens next (the WARN's last sentence).
     */
    private boolean evictAfterFailedSpoolRestore(Connection connection, String setting, SQLException restoreFailure,
            String step, String then) {
        boolean evicted = false;
        Throwable evictFailure = null;
        try {
            if (takesPoolBranch(connection)) {
                evictFromPool(connection);
                evicted = true;
            }
        } catch (SQLException | RuntimeException failure) {
            evictFailure = failure;
        }
        String failureText = restoreFailure == null ? "no failure reported"
                : sqlStateOf(restoreFailure) + ", " + EventTrailDiagnostics.safeFailureSummary(restoreFailure);
        String outcome = evicted ? "the connection was removed from the pool and is closed by the pool"
                : evictFailure != null ? "the connection could not be removed from the pool ("
                        + EventTrailDiagnostics.safeFailureSummary(evictFailure) + ") and is closed"
                : "the connection is not pooled and is closed";
        LOGGER.warn("EventTrail disk spool: the database connection did not restore the {} after {} ({}); {}. {}",
                setting, step, failureText, outcome, then);
        return evicted;
    }

    /** Adds {@code extra} to {@code target}'s suppressed list unless it is absent or the same throwable. */
    static void addSuppressedFailure(Throwable target, Throwable extra) {
        if (target == null || extra == null || extra == target) return;
        target.addSuppressed(extra);
    }

    String insertSql() {
        return "INSERT INTO " + tableName() + " (" +
                (usesServerIdColumn() ? "server_id, " : "") +
                "timestamp, action_type, player_uuid, player_name, world, pos_x, pos_y, pos_z, block_state, extra_data) " +
                "VALUES (" + placeholders(usesServerIdColumn() ? 11 : 10) + ")";
    }

    private void resolvePending(List<PendingGroup> writes, WriteResolution resolution) {
        if (writes == null || writes.isEmpty()) return;
        // Per row over whole groups (writer-01 2.5): permits, pending and payload were reserved per row.
        int count = rowCount(writes);
        long retainedBytes = retainedBytes(writes);
        synchronized (writeLifecycleLock) {
            if (pendingWrites.get() < count || pendingPayloadBytes.get() < retainedBytes) {
                throw new IllegalStateException("EventTrail write-buffer reservation accounting underflow");
            }
            for (PendingGroup group : writes) {
                if (group.admittedPast()) pendingAdmittedPastGroups.decrementAndGet();
            }
            switch (resolution) {
                case WRITTEN -> {
                    successfulWrites.addAndGet(count);
                    successfulWriteBatches.incrementAndGet();
                }
                case PERMANENT_FAILED -> permanentFailedWrites.addAndGet(count);
                case SHUTDOWN_LOST -> shutdownLostWrites.addAndGet(count);
                case SAVED_AT_STOP -> { /* counted by the spool (savedAtStop); now staged, then on disk */ }
            }
            pendingWrites.addAndGet(-count);
            pendingPayloadBytes.addAndGet(-retainedBytes);
            pendingWriteSlots.release(count);
            // Lists are owned by this writer only; terminal finally may still
            // reference the batch after a subsequent unexpected failure.
            writes.clear();
        }
    }

    private int discardPendingAtShutdown(List<PendingGroup> activeBatch) {
        // Counts rows: whole groups are discarded, never part of one.
        int lost = activeBatch == null ? 0 : rowCount(activeBatch);
        List<PendingGroup> queued = new ArrayList<>();
        writeQueue.drainTo(queued);
        lost += rowCount(queued);
        if (activeBatch != null) resolvePending(activeBatch, WriteResolution.SHUTDOWN_LOST);
        resolvePending(queued, WriteResolution.SHUTDOWN_LOST);
        return lost;
    }

    private static long retainedBytes(List<PendingGroup> writes) {
        long total = 0L;
        for (PendingGroup write : writes) {
            total += write.retainedBytes();
        }
        return total;
    }

    /** Rows held by {@code groups}. */
    private static int rowCount(List<PendingGroup> groups) {
        int rows = 0;
        for (PendingGroup group : groups) {
            rows += group.size();
        }
        return rows;
    }

    private boolean sleepBeforeRetry(long requestedDelayMs) {
        long delay = Math.max(1L, requestedDelayMs);
        if (writeShutdownRequested) {
            long remainingNanos = writerShutdownDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) return false;
            delay = Math.min(delay, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        }
        try {
            Thread.sleep(delay);
            return !forceWriterStop && !shutdownDeadlineExpired();
        } catch (InterruptedException e) {
            return !forceWriterStop && !shutdownDeadlineExpired();
        }
    }

    private boolean shutdownDeadlineExpired() {
        return writeShutdownRequested && System.nanoTime() >= writerShutdownDeadlineNanos;
    }

    protected long initialRetryDelayMillis() {
        return INITIAL_RETRY_DELAY_MS;
    }

    protected long maxRetryDelayMillis() {
        return MAX_RETRY_DELAY_MS;
    }

    protected int writeQueryTimeoutSeconds() {
        return Math.max(1, Math.min(30, EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get()));
    }

    protected long writeShutdownTimeoutMillis() {
        long connectionTimeout = EventTrailConfig.DATABASE_CONNECTION_TIMEOUT_MS.get();
        long operationTimeout = TimeUnit.SECONDS.toMillis(writeQueryTimeoutSeconds());
        return Math.max(15_000L, connectionTimeout + operationTimeout + 5_000L);
    }

    /**
     * Writer classifier: true when a retry-safe failed batch is retried without limit (outage, lock wait, time
     * limit, database-wide refusal) instead of taking the bounded path. Walks the cause and
     * {@link SQLException#getNextException()} chain (bounded, cycle-safe; writer-01 2.3, 2.4, 2.7).
     */
    final boolean isRetryableWriteFailure(SQLException failure) {
        if (isConnectionFailure(failure)) return true;
        // Time limits (SQLTimeoutException, HYT*, PostgreSQL 57014) are retried like lock waits (writer-01 2.4).
        if (EventTrailDiagnostics.isTimeout(failure)) return true;
        for (Throwable current : EventTrailDiagnostics.failureChain(failure)) {
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                // 55P03: PostgreSQL lock_not_available (writer-01 2.4).
                if (state != null && (state.startsWith("40") || state.startsWith("HYT") || state.equals("55P03"))) {
                    return true;
                }
                if (sql.getErrorCode() == 5 || sql.getErrorCode() == 6) return true;
                // No message text on the shared path: MySQL/PostgreSQL messages can carry the refused row's values
                // (writer-01 review-01 MINOR-1); each backend's own lock rule is in the hook below.
                if (isLockContentionWriteFailure(sql)) return true;
                if (isDatabaseWideWriteRefusal(sql)) return true;
            }
        }
        return false;
    }

    /**
     * A lock wait or deadlock that this backend reports in a form the shared SQLState rules above do not already
     * cover, compared only on its own backend (writer-01 review-01 MINOR-1). This default serves MySQL/MariaDB and
     * PostgreSQL and uses vendor codes only, never message text: their driver messages can carry the refused
     * row's bound values (pgjdbc's default {@code logServerErrorDetail} puts the failed batch entry's parameters
     * and the server's "Failing row contains (...)" detail into the message; MySQL echoes values, for example
     * "Duplicate entry '...'" or "Incorrect string value: '...'"), so row text could otherwise turn a refused row
     * into a retry without limit. MySQL 1205 ER_LOCK_WAIT_TIMEOUT and 1213 ER_LOCK_DEADLOCK arrive from
     * Connector/J as SQLState 40001 already; the vendor numbers keep them retried if a server or driver path
     * reports HY000 instead. PostgreSQL 40P01 deadlock_detected, 40001 serialization_failure and 55P03
     * lock_not_available are the SQLState rules above. PostgreSQL reports vendor code 0, so the MySQL numbers
     * cannot match there. SQLite overrides this with its busy/locked text.
     */
    protected boolean isLockContentionWriteFailure(SQLException failure) {
        int code = failure.getErrorCode();
        return code == 1205 || code == 1213;
    }

    /**
     * True when the database refuses every write for a reason an administrator fixes (disk full, out of memory,
     * read-only, table full), so the batch is retried without limit instead of being isolated and dropped
     * (writer-01 2.7, Owner Q5 A). This default serves MySQL/MariaDB and PostgreSQL: PostgreSQL 53100 disk_full,
     * 53200 out_of_memory, 25006 read_only_sql_transaction (MySQL 1792 carries the same SQLState), and MySQL 1021
     * ER_DISK_FULL, 1114 ER_RECORD_FILE_FULL, 1290 ER_OPTION_PREVENTS_STATEMENT (--read-only) and 1792
     * ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION. PostgreSQL reports vendor code 0, so the MySQL numbers cannot match
     * there. SQLite overrides this with its own result codes.
     */
    protected boolean isDatabaseWideWriteRefusal(SQLException failure) {
        String state = failure.getSQLState();
        if ("53100".equals(state) || "53200".equals(state) || "25006".equals(state)) return true;
        int code = failure.getErrorCode();
        return code == 1021 || code == 1114 || code == 1290 || code == 1792;
    }

    protected final void insertEntriesBatch(List<AuditRecord> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return;
        String sql = "INSERT INTO " + tableName() + " (" +
                (usesServerIdColumn() ? "server_id, " : "") +
                "timestamp, action_type, player_uuid, player_name, world, pos_x, pos_y, pos_z, block_state, extra_data) " +
                "VALUES (" + placeholders(usesServerIdColumn() ? 11 : 10) + ")";

        try (AuxiliaryMutationOperation operation = admitAuxiliaryMutation();
             MutationScope mutation = beginMutation(MutationKind.DIRECT_INSERT, Long.MAX_VALUE);
             Connection connection = connection()) {
            mutation.enterNative();
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            PreparedStatement ps = connection.prepareStatement(sql);
            try (ps) {
                operation.statement.register(ps);
                ps.setQueryTimeout(writeQueryTimeoutSeconds());
                int count = 0;
                for (AuditRecord record : entries) {
                    int i = 1;
                    if (usesServerIdColumn()) ps.setString(i++, serverId);
                    bindEntry(ps, i, SchemaV1RowCodec.encode(record));
                    ps.addBatch();
                    if (++count % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
                connection.commit();
                markDatabaseSuccess();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                try {
                    operation.statement.clear(ps);
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            }
        }
    }

    private static void bindEntry(PreparedStatement ps, int i, SchemaV1Row row) throws SQLException {
        ps.setLong(i++, row.timestamp());
        ps.setString(i++, row.actionCode());
        ps.setString(i++, row.indexedUuid());
        ps.setString(i++, row.indexedName());
        ps.setString(i++, row.dimensionId());
        ps.setInt(i++, row.x());
        ps.setInt(i++, row.y());
        ps.setInt(i++, row.z());
        ps.setString(i++, row.subjectIdentity());
        ps.setString(i, row.evidencePayload());
    }

    @Override
    public final List<AuditRecord> queryPosition(BlockPos pos, String world, int limit) {
        StringBuilder sql = selectBase().append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("world = ? AND pos_x = ? AND pos_y = ? AND pos_z = ? ORDER BY timestamp DESC, id DESC LIMIT ?");
        params.add(world);
        params.add(pos.getX());
        params.add(pos.getY());
        params.add(pos.getZ());
        params.add(limit);
        return readEntries(sql.toString(), params);
    }

    @Override
    public final List<AuditRecord> queryRadius(BlockPos pos, String world, int radius, int limit) {
        StringBuilder sql = selectBase().append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("world = ? AND pos_x BETWEEN ? AND ? AND pos_y BETWEEN ? AND ? AND pos_z BETWEEN ? AND ? ")
                .append("ORDER BY timestamp DESC, id DESC LIMIT ?");
        params.add(world);
        params.add(pos.getX() - radius);
        params.add(pos.getX() + radius);
        params.add(pos.getY() - radius);
        params.add(pos.getY() + radius);
        params.add(pos.getZ() - radius);
        params.add(pos.getZ() + radius);
        params.add(limit);
        return readEntries(sql.toString(), params);
    }

    @Override
    public final List<AuditRecord> queryPlayer(String playerUuid, int limit) {
        StringBuilder sql = selectBase().append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("player_uuid = ? ORDER BY timestamp DESC, id DESC LIMIT ?");
        params.add(playerUuid);
        params.add(limit);
        return readEntries(sql.toString(), params);
    }

    @Override
    public final List<AuditRecord> queryTimeRange(long startTime, long endTime, int limit) {
        StringBuilder sql = selectBase().append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("timestamp BETWEEN ? AND ? ORDER BY timestamp DESC, id DESC LIMIT ?");
        params.add(startTime);
        params.add(endTime);
        params.add(limit);
        return readEntries(sql.toString(), params);
    }

    @Override
    public final AdvancedQueryResult queryAdvanced(AdvancedQuery query) {
        return runRead(() -> executeAdvancedQuery(query));
    }

    @Override
    public final long captureHighWaterMark() {
        return runRead(this::executeHighWaterMarkQuery);
    }

    /**
     * Explosion split (design-02 3.1; Advisor M-6 a, b, d, e): the part rows stamped {@code partTimestamp} on this
     * server whose id is at most {@code maxIdInclusive} (the lookup page's id mark), in id order, at most
     * {@code limit}. The action is bound as its stored code. Runs on a read worker and waits at most
     * {@link #EXPLOSION_PART_READ_WAIT_LIMIT_MILLIS} for one; the caller treats any failure as "not checked".
     */
    @Override
    public final List<AuditRecord> explosionParts(long partTimestamp, long maxIdInclusive, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        ReadParts query = buildExplosionPartsQuery(partTimestamp, maxIdInclusive, limit);
        return runRead(() -> {
            List<AuditRecord> entries = new ArrayList<>();
            try (Connection connection = connection();
                 PreparedStatement ps = connection.prepareStatement(query.sql())) {
                ps.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                bind(ps, query.params());
                withActiveReadStatement(ps, () -> {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) entries.add(mapRow(rs));
                        return null;
                    }
                });
            }
            return entries;
        }, EXPLOSION_PART_READ_WAIT_LIMIT_MILLIS);
    }

    /** M-6 e: a verdict read waits this long for a free read worker, then reads "not checked". */
    static final long EXPLOSION_PART_READ_WAIT_LIMIT_MILLIS = 2_000L;

    ReadParts buildExplosionPartsQuery(long partTimestamp, long maxIdInclusive, int limit) {
        StringBuilder sql = selectBase().append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("action_type = ? AND timestamp = ? AND block_state = ? AND id <= ? ORDER BY id ASC LIMIT ?");
        params.add(AuditAction.EXPLOSION.storageCode());
        params.add(partTimestamp);
        params.add(io.github.betapetey.eventtrail.events.block.ExplosionPartText.TECHNICAL_IDENTITY);
        params.add(maxIdInclusive);
        params.add(limit);
        return new ReadParts(sql.toString(), List.copyOf(params));
    }

    record ReadParts(String sql, List<Object> params) {}

    private long executeHighWaterMarkQuery() throws Exception {
        HighWaterParts highWater = buildHighWaterQuery();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(highWater.sql)) {
            statement.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
            bind(statement, highWater.params);
            return withActiveReadStatement(statement, () -> {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Math.max(0L, result.getLong(1)) : 0L;
                }
            });
        }
    }

    HighWaterParts buildHighWaterQuery() {
        StringBuilder sql = new StringBuilder("SELECT COALESCE(MAX(id), 0) FROM ").append(tableName());
        List<Object> params = new ArrayList<>();
        if (usesServerIdColumn()) {
            sql.append(" WHERE server_id = ?");
            params.add(serverId);
        }
        return new HighWaterParts(sql.toString(), List.copyOf(params));
    }

    private AdvancedQueryResult executeAdvancedQuery(AdvancedQuery query) throws Exception {
        WhereParts where = buildAdvancedWhere(query);
        try (Connection connection = connection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            int originalIsolation = connection.getTransactionIsolation();
            Exception queryFailure = null;
            try {
                int consistentIsolation = consistentReadTransactionIsolation();
                if (originalIsolation != consistentIsolation) {
                    connection.setTransactionIsolation(consistentIsolation);
                }
                if (originalAutoCommit) connection.setAutoCommit(false);

                if (where.textCheck() != null || where.nameCheck() != null) {
                    AdvancedQueryResult checked = executeJavaCheckedQuery(connection, where, query);
                    connection.commit();
                    return checked;
                }
                long total;
                String countSql = "SELECT COUNT(*) FROM " + tableName() + where.sql;
                try (PreparedStatement count = connection.prepareStatement(countSql)) {
                    count.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                    bind(count, where.params);
                    total = withActiveReadStatement(count, () -> {
                        try (ResultSet rs = count.executeQuery()) {
                            return rs.next() ? rs.getLong(1) : 0L;
                        }
                    });
                }
                afterAdvancedCount(connection, total);

                int perPage = Math.max(1, query.perPage());
                int totalPages = total == 0L ? 1 : (int)Math.min(Integer.MAX_VALUE, (total + perPage - 1L) / perPage);
                int page = Math.max(1, Math.min(query.page(), totalPages));
                List<AuditRecord> entries = new ArrayList<>();

                if (total > 0L) {
                    long offset = (long)(page - 1) * perPage;
                    String selectSql = selectBase() + where.sql
                            + " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?";
                    try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                        select.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                        int i = bind(select, where.params);
                        select.setInt(i++, perPage);
                        select.setLong(i, offset);
                        withActiveReadStatement(select, () -> {
                            try (ResultSet rs = select.executeQuery()) {
                                while (rs.next()) entries.add(mapRow(rs));
                                return null;
                            }
                        });
                    }
                }

                connection.commit();
                return new AdvancedQueryResult(total, entries, page, totalPages);
            } catch (Exception failure) {
                queryFailure = failure;
                rollbackReadTransaction(connection, failure);
                throw failure;
            } finally {
                restoreReadConnectionState(
                        connection, originalAutoCommit, originalIsolation, queryFailure);
            }
        }
    }

    /** T31-R4: candidate rows read by one statement of the Java-checked scan. */
    private static final int CANDIDATE_CHUNK_ROWS = 1_000;

    /** T31-R4 test seam: candidate rows per chunk statement. */
    int candidateChunkRows() {
        return CANDIDATE_CHUNK_ROWS;
    }

    /** T31-R4 test seam: the clock of the Java-checked scan's time budget. */
    long candidateScanNanoTime() {
        return System.nanoTime();
    }

    /**
     * T31-R4: a lookup with a text filter that SQL cannot decide exactly (a non-ASCII needle, or any text filter
     * after a failed self-test). SQL returns the candidate rows in lookup order in chunks of
     * {@link #candidateChunkRows()} rows, each chunk starting after the last row of the one before (keyset on
     * timestamp and id, the lookup order), and SearchFold.TextCheck decides each row, so the count and the pages
     * follow one rule on every backend. Runs in the caller's consistent read transaction, so every chunk reads
     * the same snapshot, and keeps only the ids of the requested page and of the latest page, so an
     * out-of-range page clamps to the last page exactly as on the SQL path.
     *
     * <p>Time limit (lookup-lane-01 review-01 MAJOR-1): the whole scan has one budget, the query timeout,
     * counted from the start of this method. No fetch size is set, so Connector/J and pgjdbc read each chunk's
     * whole result inside execute, where the statement's query timeout (set to the budget left) and the shutdown
     * cancel both apply; a streamed or cursor read would be outside both. The budget is also checked before each
     * chunk and at each row, and a spent budget throws SQLTimeoutException, classed TIMEOUT. On SQLite, whose
     * query timeout is a busy timeout only, the row check is the limit, as for the other SQLite lookup reads.
     *
     * <p>The exact-name filter ({@link LoggedNameFilter}) uses the same scan: its SQL part is only a necessary
     * condition, and each candidate also passes only when its decoded logged name equals the typed name. A row
     * must pass the text check (when there is one) and the name check (when there is one). A candidate the
     * read path's codec cannot decode consistently fails the lookup as a data-read failure, as {@link #mapRow}
     * does for the same row (lane xn r2, review-01 MINOR-2), rather than being counted as "no match".</p>
     */
    private AdvancedQueryResult executeJavaCheckedQuery(Connection connection, WhereParts where, AdvancedQuery query)
            throws Exception {
        SearchFold.TextCheck check = where.textCheck();
        LoggedNameFilter nameCheck = where.nameCheck();
        int perPage = Math.max(1, query.perPage());
        int requestedPage = Math.max(1, query.page());
        int chunkRows = Math.max(1, candidateChunkRows());
        int budgetSeconds = EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get();
        long deadline = candidateScanNanoTime() + TimeUnit.SECONDS.toNanos(budgetSeconds);
        // The text check's columns first, in its order (TextCheck.matches reads them by position), then any
        // column the name check needs that is not already read.
        List<String> readColumns = new ArrayList<>(check == null ? List.of() : check.columns());
        int textColumns = readColumns.size();
        if (nameCheck != null) {
            for (String column : LoggedNameFilter.COLUMNS) {
                if (!readColumns.contains(column)) readColumns.add(column);
            }
        }
        int actionColumn = readColumns.indexOf("action_type");
        int subjectColumn = readColumns.indexOf("block_state");
        int payloadColumn = readColumns.indexOf("extra_data");
        StringBuilder candidates = new StringBuilder("SELECT id, timestamp");
        for (String column : readColumns) candidates.append(", ").append(column);
        candidates.append(" FROM ").append(tableName()).append(where.sql);
        String firstChunkSql = candidates + " ORDER BY timestamp DESC, id DESC LIMIT ?";
        String nextChunkSql = candidates + " AND timestamp <= ? AND (timestamp < ? OR id < ?)"
                + " ORDER BY timestamp DESC, id DESC LIMIT ?";
        CandidateScan scan = new CandidateScan(perPage, requestedPage);
        List<String> stored = new ArrayList<>();
        int rowsInChunk;
        do {
            int chunkTimeoutSeconds = remainingScanSeconds(deadline, budgetSeconds);
            boolean firstChunk = !scan.positioned;
            try (PreparedStatement chunk = connection.prepareStatement(firstChunk ? firstChunkSql : nextChunkSql)) {
                chunk.setQueryTimeout(chunkTimeoutSeconds);
                int index = bind(chunk, where.params);
                if (!firstChunk) {
                    chunk.setLong(index++, scan.lastTimestamp);
                    chunk.setLong(index++, scan.lastTimestamp);
                    chunk.setLong(index++, scan.lastId);
                }
                chunk.setInt(index, chunkRows);
                rowsInChunk = withActiveReadStatement(chunk, () -> {
                    int rows = 0;
                    try (ResultSet rs = chunk.executeQuery()) {
                        while (rs.next()) {
                            remainingScanSeconds(deadline, budgetSeconds);
                            rows++;
                            long id = rs.getLong(1);
                            scan.position(rs.getLong(2), id);
                            stored.clear();
                            for (int column = 0; column < readColumns.size(); column++) {
                                stored.add(rs.getString(column + 3));
                            }
                            boolean matches = check == null || check.matches(stored.subList(0, textColumns));
                            if (matches && nameCheck != null) {
                                matches = nameCheck.matches(payloadCodec, stored.get(actionColumn),
                                        stored.get(subjectColumn), stored.get(payloadColumn));
                            }
                            if (matches) scan.match(id);
                        }
                    }
                    return rows;
                });
            }
        } while (rowsInChunk == chunkRows);
        afterAdvancedCount(connection, scan.total);
        long total = scan.total;
        int totalPages = total == 0L ? 1 : (int) Math.min(Integer.MAX_VALUE, (total + perPage - 1L) / perPage);
        int page = Math.max(1, Math.min(query.page(), totalPages));
        List<Long> ids = page == requestedPage ? scan.requested : scan.latest;
        List<AuditRecord> entries = new ArrayList<>();
        if (!ids.isEmpty()) {
            String selectSql = selectBase() + " WHERE id IN (" + placeholders(ids.size()) + ")"
                    + " ORDER BY timestamp DESC, id DESC";
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                bind(select, new ArrayList<>(ids));
                withActiveReadStatement(select, () -> {
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) entries.add(mapRow(rs));
                        return null;
                    }
                });
            }
        }
        return new AdvancedQueryResult(total, entries, page, totalPages);
    }

    /** Seconds left of the scan budget (at least 1, rounded up); throws once the budget is spent. */
    private int remainingScanSeconds(long deadline, int budgetSeconds) throws SQLTimeoutException {
        long remaining = deadline - candidateScanNanoTime();
        if (remaining <= 0L) {
            throw new SQLTimeoutException(
                    "EventTrail lookup check (text or name filter) ran past the " + budgetSeconds
                            + " s query time limit");
        }
        return (int) Math.max(1L, (remaining + 999_999_999L) / 1_000_000_000L);
    }

    /** The Java-checked scan's state: matches so far, the two kept pages of ids, and the last row read. */
    private static final class CandidateScan {
        private final int perPage;
        private final int requestedPage;
        private final List<Long> requested = new ArrayList<>();
        private List<Long> latest = new ArrayList<>();
        private long total;
        private boolean positioned;
        private long lastTimestamp;
        private long lastId;

        private CandidateScan(int perPage, int requestedPage) {
            this.perPage = perPage;
            this.requestedPage = requestedPage;
        }

        private void position(long timestamp, long id) {
            positioned = true;
            lastTimestamp = timestamp;
            lastId = id;
        }

        private void match(long id) {
            if (total % perPage == 0L) latest = new ArrayList<>();
            latest.add(id);
            total++;
            if ((total - 1L) / perPage + 1L == requestedPage) requested.add(id);
        }
    }

    private static void rollbackReadTransaction(Connection connection, Exception failure) {
        try {
            if (!connection.getAutoCommit()) connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreReadConnectionState(
            Connection connection,
            boolean originalAutoCommit,
            int originalIsolation,
            Exception queryFailure) throws SQLException {
        SQLException restorationFailure = null;
        try {
            if (!connection.isClosed() && connection.getTransactionIsolation() != originalIsolation) {
                connection.setTransactionIsolation(originalIsolation);
            }
        } catch (SQLException failure) {
            restorationFailure = failure;
        }
        try {
            if (!connection.isClosed() && connection.getAutoCommit() != originalAutoCommit) {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            if (restorationFailure == null) restorationFailure = failure;
            else restorationFailure.addSuppressed(failure);
        }
        if (restorationFailure != null) {
            if (queryFailure != null) queryFailure.addSuppressed(restorationFailure);
            else throw restorationFailure;
        }
    }

    WhereParts buildAdvancedWhere(AdvancedQuery query) {
        StringBuilder where = new StringBuilder(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(where, params);
        where.append("timestamp >= ? AND timestamp <= ? AND id <= ?");
        params.add(query.fromTime());
        params.add(query.throughTime());
        params.add(query.highWaterId());

        if (query.world() != null) {
            where.append(" AND world = ?");
            params.add(query.world());
        }

        if (query.center() != null && query.radius() > 0) {
            BlockPos pos = query.center();
            int radius = query.radius();
            where.append(" AND pos_x BETWEEN ? AND ? AND pos_y BETWEEN ? AND ? AND pos_z BETWEEN ? AND ?");
            params.add(pos.getX() - radius);
            params.add(pos.getX() + radius);
            params.add(pos.getY() - radius);
            params.add(pos.getY() + radius);
            params.add(pos.getZ() - radius);
            params.add(pos.getZ() + radius);
        }

        ActionSelection actionSelection = query.actionSelection();
        if (actionSelection.restricted()) {
            List<AuditAction> actions = actionSelection.actions();
            List<ContainerAccessPhase> phases = query.accessPhase() == null
                    ? actionSelection.containerAccessPhases() : List.of(query.accessPhase());
            if (!phases.isEmpty()) {
                // The phase disjunction belongs ONLY to the container-access
                // branch. Shared scope/player/item/etc. predicates follow as AND.
                List<AuditAction> otherActions = actions.stream()
                        .filter(action -> action != AuditAction.CONTAINER_ACCESS).toList();
                where.append(" AND (");
                if (!otherActions.isEmpty()) {
                    where.append("action_type IN (").append(placeholders(otherActions.size())).append(") OR ");
                    otherActions.forEach(action -> params.add(action.storageCode()));
                }
                where.append("(action_type = ? AND (");
                params.add(AuditAction.CONTAINER_ACCESS.storageCode());
                for (int index = 0; index < phases.size(); index++) {
                    if (index > 0) where.append(" OR ");
                    String prefix = ContainerAccessPayloadCodec.phasePrefix(phases.get(index));
                    where.append(containerAccessPhaseComparisonSql());
                    params.add(prefix.length());
                    params.add(prefix);
                }
                where.append(")))");
            } else if (actions.size() == 1) {
                where.append(" AND action_type = ?");
                params.add(actions.getFirst().storageCode());
            } else {
                where.append(" AND action_type IN (")
                        .append(placeholders(actions.size()))
                        .append(')');
                actions.forEach(action -> params.add(action.storageCode()));
            }
        }
        // T31-R4 (dbbugs-01 design-r2, option C): every needle is folded with SearchFold. ASCII needles are
        // matched exactly in SQL on every backend; a non-ASCII needle keeps the rows whose text holds a
        // non-ASCII character as candidates, and executeAdvancedQuery decides those in Java.
        List<SearchFold.TextPredicate> javaChecked = new ArrayList<>();
        if (query.playerName() != null) {
            where.append(" AND ");
            appendTextPredicate(where, params, javaChecked, List.of("player_name"),
                    List.of(SearchFold.fold(query.playerName())), true);
        }
        List<String> blockNeedles = normalizeSearchNeedles(query.block(), query.blockNeedles());
        if (!blockNeedles.isEmpty()) {
            where.append(" AND action_type IN ('BLOCK_BREAK','BLOCK_PLACE','EXPLOSION')")
                    .append(" AND ");
            appendTextPredicate(where, params, javaChecked, List.of("block_state"), blockNeedles, false);
        }
        List<String> itemNeedles = normalizeSearchNeedles(query.item(), query.itemNeedles());
        if (!itemNeedles.isEmpty()) {
            where.append(" AND ");
            appendTextPredicate(where, params, javaChecked, List.of("extra_data"), itemNeedles, false);
        }
        if (query.entity() != null) {
            where.append(" AND action_type IN ('ENTITY_KILL','ENTITY_DEATH')")
                    .append(" AND ");
            appendTextPredicate(where, params, javaChecked, List.of("block_state", "extra_data"),
                    List.of(SearchFold.fold(query.entity())), false);
        }
        LoggedNameFilter nameCheck = null;
        if (query.exactName() != null) {
            nameCheck = new LoggedNameFilter(query.exactName());
            appendExactNamePredicate(where, params, nameCheck);
        }
        return new WhereParts(where.toString(), params,
                javaChecked.isEmpty() ? null : SearchFold.TextCheck.of(javaChecked), nameCheck);
    }

    /**
     * The SQL part of {@code name:}: block and container actions only, and an {@code extra_data} that holds the
     * name field's bytes (one of three base64url alignments) or, for container access, its SNBT text. It is a
     * necessary condition only; {@link #executeJavaCheckedQuery} decides each candidate. Each LIKE term is this
     * backend's {@link #exactNameLikeSql}, the form the startup self-test proved. After a failed self-test the
     * LIKE terms are left out (only the action restriction stays) and Java decides every row.
     */
    private void appendExactNamePredicate(StringBuilder where, List<Object> params, LoggedNameFilter name) {
        boolean prefilter = searchSqlUsable;
        String likeTerm = exactNameLikeSql("extra_data");
        where.append(" AND ((action_type IN (")
                .append(placeholders(LoggedNameFilter.BINARY_PAYLOAD_ACTIONS.size())).append(')');
        LoggedNameFilter.BINARY_PAYLOAD_ACTIONS.forEach(action -> params.add(action.storageCode()));
        if (prefilter) {
            where.append(" AND (");
            List<String> fragments = name.binaryFieldFragments();
            for (int index = 0; index < fragments.size(); index++) {
                if (index > 0) where.append(" OR ");
                where.append(likeTerm);
                params.add(literalLikePattern(fragments.get(index)));
            }
            where.append(')');
        }
        where.append(") OR (action_type = ?");
        params.add(LoggedNameFilter.ACCESS_PAYLOAD_ACTION.storageCode());
        if (prefilter) {
            where.append(" AND ").append(likeTerm);
            params.add(literalLikePattern(name.accessPayloadNeedle()));
        }
        where.append("))");
    }

    static List<String> normalizeSearchNeedles(String raw, List<String> resolved) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addSearchNeedle(normalized, raw);
        if (resolved != null) {
            for (String needle : resolved) {
                addSearchNeedle(normalized, needle);
                if (normalized.size() >= ADVANCED_SEARCH_NEEDLE_LIMIT) break;
            }
        }
        return List.copyOf(normalized);
    }

    private static void addSearchNeedle(LinkedHashSet<String> needles, String value) {
        if (needles.size() >= ADVANCED_SEARCH_NEEDLE_LIMIT || value == null) return;
        String normalized = SearchFold.fold(value.trim());
        if (!normalized.isBlank()) needles.add(normalized);
    }

    /**
     * T31-R4: one text filter over {@code columns}, true when any folded needle equals (player) or occurs in
     * (other filters) the folded text of any column. ASCII needles are exact in SQL. A non-ASCII needle adds
     * "column holds non-ASCII text" candidate terms and puts the whole filter on the Java check list. After a
     * failed self-test the filter is left entirely to the Java check. Fix F-A: the ASCII needles of each column
     * form one {@link #appendAsciiNeedleTerms} group, whose non-ASCII guard runs once per row and column.
     */
    private void appendTextPredicate(StringBuilder where, List<Object> params,
                                     List<SearchFold.TextPredicate> javaChecked, List<String> columns,
                                     List<String> foldedNeedles, boolean equality) {
        if (!searchSqlUsable) {
            where.append("1 = 1");
            javaChecked.add(new SearchFold.TextPredicate(columns, foldedNeedles, equality));
            return;
        }
        List<String> asciiNeedles = new ArrayList<>();
        boolean nonAsciiNeedle = false;
        for (String needle : foldedNeedles) {
            if (SearchFold.isAscii(needle)) asciiNeedles.add(needle);
            else nonAsciiNeedle = true;
        }
        String separator = "";
        where.append('(');
        if (!asciiNeedles.isEmpty()) {
            for (String column : columns) {
                where.append(separator);
                appendAsciiNeedleTerms(where, params, textColumnSql(column, equality), asciiNeedles, equality);
                separator = " OR ";
            }
        }
        if (nonAsciiNeedle) {
            for (String column : columns) {
                where.append(separator).append(nonAsciiTextSql(textColumnSql(column, equality)));
                separator = " OR ";
            }
            javaChecked.add(new SearchFold.TextPredicate(columns, foldedNeedles, equality));
        }
        where.append(')');
    }

    /** The player filter compares player_name itself (NOT NULL); the other filters read NULL as "". */
    private static String textColumnSql(String column, boolean equality) {
        return equality ? column : "COALESCE(" + column + ",'')";
    }

    static String literalLikePattern(String needle) {
        return "%" + escapeLikeLiteral(needle) + "%";
    }

    static String literalLikePrefixPattern(String prefix) {
        return escapeLikeLiteral(prefix) + "%";
    }

    private static String escapeLikeLiteral(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private List<AuditRecord> readEntries(String sql, List<Object> params) {
        return runRead(() -> {
            List<AuditRecord> entries = new ArrayList<>();
            try (Connection connection = connection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                bind(ps, params);
                withActiveReadStatement(ps, () -> {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) entries.add(mapRow(rs));
                        return null;
                    }
                });
            }
            return entries;
        });
    }

    @Override
    public final long deleteOlderThan(long cutoffTimestamp) {
        return deleteOlderThanWithResult(cutoffTimestamp).deletedCount();
    }

    @Override
    public final RetentionResult deleteOlderThanWithResult(long cutoffTimestamp) {
        return deleteOlderThanWithResult(cutoffTimestamp, () -> true);
    }

    @Override
    public final RetentionResult deleteOlderThanWithResult(long cutoffTimestamp, java.util.function.BooleanSupplier mayStartBatch) {
        java.util.Objects.requireNonNull(mayStartBatch, "mayStartBatch");
        try (EventTrailJdbcLogScope.Scope ignored = EventTrailJdbcLogScope.open()) {
            return deleteOlderThanScoped(cutoffTimestamp, mayStartBatch);
        }
    }

    private RetentionResult deleteOlderThanScoped(long cutoffTimestamp, java.util.function.BooleanSupplier mayStartBatch) {
        if (Thread.currentThread().isInterrupted()) {
            return new RetentionResult(0L, RetentionResult.Outcome.INTERRUPTED);
        }
        if (!ready) return new RetentionResult(0L, RetentionResult.Outcome.FAILED);

        long deletedTotal = 0L;
        long deadlineNanos = retentionNanoTime()
                + TimeUnit.MILLISECONDS.toNanos(RETENTION_WORK_BUDGET_MILLIS);
        RetentionResult.Outcome outcome;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                outcome = RetentionResult.Outcome.INTERRUPTED;
                break;
            }
            if (!ready) {
                outcome = RetentionResult.Outcome.FAILED;
                break;
            }
            try {
                if (!mayStartBatch.getAsBoolean()) {
                    outcome = RetentionResult.Outcome.YIELDED;
                    break;
                }
            } catch (RuntimeException admissionFailure) {
                logRetentionFailure(admissionFailure);
                outcome = RetentionResult.Outcome.FAILED;
                break;
            }
            if (retentionNanoTime() >= deadlineNanos) {
                outcome = RetentionResult.Outcome.BUDGET_EXHAUSTED;
                break;
            }
            DeleteParts delete = buildRetentionDeleteBatchQuery(
                    cutoffTimestamp, RETENTION_DELETE_BATCH_SIZE);
            int deleted = 0;
            boolean committed = false;
            try (AuxiliaryMutationOperation operation = admitAuxiliaryMutation();
                 MutationScope mutation = beginMutation(MutationKind.RETENTION, deadlineNanos);
                 Connection connection = connection()) {
                mutation.enterNative();
                PreparedStatement statement = connection.prepareStatement(delete.sql());
                try (statement) {
                    operation.statement.register(statement);
                    statement.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                    bind(statement, delete.params());
                    // A join may have arrived while mutation admission or the pool borrow waited.
                    // This is the final batch-admission point; admitted finite SQL is allowed to settle.
                    if (!mayStartBatch.getAsBoolean()) {
                        outcome = RetentionResult.Outcome.YIELDED;
                        break;
                    }
                    deleted = statement.executeUpdate();
                    committed = true;
                    markDatabaseSuccess();
                } finally {
                    operation.statement.clear(statement);
                }
            } catch (Exception failure) {
                // executeUpdate returning under auto-commit means this finite batch
                // committed. Cleanup/close failures must not erase that count.
                if (committed) {
                    deletedTotal += deleted;
                    onRetentionBatchCommitted(deleted);
                }
                logRetentionFailure(failure);
                outcome = Thread.currentThread().isInterrupted()
                        ? RetentionResult.Outcome.INTERRUPTED : RetentionResult.Outcome.FAILED;
                break;
            }

            deletedTotal += deleted;
            onRetentionBatchCommitted(deleted);
            if (deleted < RETENTION_DELETE_BATCH_SIZE) {
                outcome = RetentionResult.Outcome.EXHAUSTED;
                break;
            }

            try {
                Thread.sleep(RETENTION_BATCH_PAUSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outcome = RetentionResult.Outcome.INTERRUPTED;
                break;
            }
        }

        if (deletedTotal > 0L) {
            LOGGER.info("Deleted {} old EventTrail log entries from {} in bounded batches",
                    deletedTotal, getBackendName());
        }
        try {
            afterRetentionMaintenance(deletedTotal);
        } catch (Exception maintenanceFailure) {
            outcome = RetentionResult.Outcome.FAILED;
            LOGGER.warn(
                    "EventTrail {} retention deleted {} row(s), but optional post-delete maintenance failed ({}).",
                    getBackendName(), deletedTotal,
                    EventTrailDiagnostics.safeFailureSummary(maintenanceFailure));
        }
        return new RetentionResult(deletedTotal, outcome);
    }

    private AuxiliaryMutationOperation admitAuxiliaryMutation() throws SQLException {
        synchronized (writeLifecycleLock) {
            if (closeStarted || !ready) {
                throw new SQLException("EventTrail storage is unavailable for maintenance", "08003");
            }
            AuxiliaryMutationOperation operation = new AuxiliaryMutationOperation();
            auxiliaryMutations.add(operation);
            return operation;
        }
    }

    @FunctionalInterface
    protected interface MaintenanceWork<T> {
        T run(Connection connection, Statement statement) throws SQLException;
    }

    @FunctionalInterface
    protected interface MaintenanceOwnershipWork<T> {
        T run() throws SQLException;
    }

    /** Retain backend lifetime across bounded maintenance bookkeeping, without a native mutation lease. */
    protected final <T> T withMaintenanceOwnership(MaintenanceOwnershipWork<T> work) throws SQLException {
        try (AuxiliaryMutationOperation operation = admitAuxiliaryMutation()) {
            return work.run();
        }
    }

    /** Backend-internal finite work; retained native/statement ownership is identical to retention.
     * No command, SQL string or operator endpoint is exposed by this boundary. */
    protected final <T> T withCoordinatedMaintenance(long deadlineNanos, MaintenanceWork<T> work) throws SQLException {
        try (AuxiliaryMutationOperation operation = admitAuxiliaryMutation();
             MutationScope mutation = beginMutation(MutationKind.CHECKPOINT, deadlineNanos);
             Connection connection = connection()) {
            mutation.enterNative();
            Statement statement = connection.createStatement();
            try (statement) {
                operation.statement.register(statement, connection);
                return work.run(connection, statement);
            } finally {
                operation.statement.clear(statement);
            }
        }
    }

    /** Fixed backend checkpoint SQL only; not an arbitrary operator SQL endpoint. */
    protected final int[] executeCoordinatedCheckpoint(String sql) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RETENTION_WORK_BUDGET_MILLIS);
        try (AuxiliaryMutationOperation operation = admitAuxiliaryMutation();
             MutationScope mutation = beginMutation(MutationKind.CHECKPOINT, deadline);
             Connection connection = connection()) {
            mutation.enterNative();
            Statement statement = connection.createStatement();
            try (statement) {
                operation.statement.register(statement);
                statement.setQueryTimeout(EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get());
                try (ResultSet result = statement.executeQuery(sql)) {
                    if (!result.next()) throw new SQLException("SQLite checkpoint did not return an acknowledgement");
                    return new int[]{result.getInt(1), result.getInt(2), result.getInt(3)};
                }
            } finally {
                operation.statement.clear(statement);
            }
        }
    }

    private void cancelAuxiliaryMutations() {
        List<AuxiliaryMutationOperation> operations;
        synchronized (writeLifecycleLock) {
            operations = List.copyOf(auxiliaryMutations);
        }
        for (AuxiliaryMutationOperation operation : operations) operation.cancel();
    }

    private void awaitAuxiliaryMutations() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_SHUTDOWN_TIMEOUT_SECONDS);
        boolean interrupted = false;
        synchronized (writeLifecycleLock) {
            while (!auxiliaryMutations.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(writeLifecycleLock, remaining);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private final class AuxiliaryMutationOperation implements AutoCloseable {
        private final Thread worker = Thread.currentThread();
        private final WriteOperation statement = new WriteOperation();
        private boolean finished;

        void cancel() {
            synchronized (this) {
                if (finished) return;
                worker.interrupt();
            }
            statement.cancel();
        }

        @Override public void close() {
            synchronized (this) { finished = true; }
            synchronized (writeLifecycleLock) {
                auxiliaryMutations.remove(this);
                writeLifecycleLock.notifyAll();
            }
            backendOperationFinished();
        }
    }

    private void logRetentionFailure(Throwable failure) {
        if (isConnectionFailure(failure)) {
            logDatabaseFailure("delete old EventTrail entries", failure);
        } else if (EventTrailDiagnostics.containsSqlException(failure)) {
            logSanitizedDatabaseError("delete old EventTrail entries", failure);
        } else {
            LOGGER.error("Failed to delete old EventTrail entries using {} storage", getBackendName(), failure);
        }
    }

    protected final void ensureIndex(
            Connection connection, String table, String indexName, String columns) throws SQLException {
        ensureIndexes(connection, table, List.of(indexSpec(indexName, columns.split(",\\s*"))));
    }

    protected final void ensureIndexes(
            Connection connection, String table, List<IndexSpec> requiredIndexes) throws SQLException {
        TableIdentity identity = findTable(connection, table);
        if (identity == null) {
            throw new SQLException("Cannot create EventTrail indexes because the log table is missing.");
        }

        Map<String, List<String>> existing = readIndexColumns(connection, identity);
        List<IndexSpec> missing = validateRequiredIndexes(existing, requiredIndexes);

        for (IndexSpec required : missing) {
            String columns = String.join(", ", required.columns());
            try (Statement statement = connection.createStatement()) {
                statement.execute(createIndexSql(required.name(), table, columns));
            } catch (SQLException creationFailure) {
                Map<String, List<String>> afterFailure = readIndexColumns(connection, identity);
                if (!indexColumnsMatch(afterFailure.get(required.normalizedName()), required)) {
                    throw creationFailure;
                }
            }

            Map<String, List<String>> afterCreation = readIndexColumns(connection, identity);
            if (!indexColumnsMatch(afterCreation.get(required.normalizedName()), required)) {
                throw new SQLException(
                        "EventTrail database index '" + required.name()
                                + "' has incompatible columns after initialization.");
            }
        }
    }

    protected final void requireIndexes(
            Connection connection, String table, List<IndexSpec> requiredIndexes) throws SQLException {
        TableIdentity identity = findTable(connection, table);
        if (identity == null) {
            throw new SQLException("Cannot validate EventTrail indexes because the log table is missing.");
        }
        List<IndexSpec> missing = validateRequiredIndexes(
                readIndexColumns(connection, identity), requiredIndexes);
        if (!missing.isEmpty()) {
            throw new SQLException(
                    "EventTrail database schema is missing required index(es): "
                            + missing.stream().map(IndexSpec::name)
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + ".");
        }
    }

    private Map<String, List<String>> readIndexColumns(
            Connection connection, TableIdentity table) throws SQLException {
        Map<String, TreeMap<Short, String>> ordered = new HashMap<>();
        try (ResultSet result = connection.getMetaData().getIndexInfo(
                table.catalog(), table.schema(), table.name(), false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                ordered.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new TreeMap<>())
                        .put(result.getShort("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }

        Map<String, List<String>> indexes = new HashMap<>();
        ordered.forEach((name, columns) -> indexes.put(name, List.copyOf(columns.values())));
        return indexes;
    }

    protected static IndexSpec indexSpec(String name, String... columns) {
        return new IndexSpec(name, List.of(columns));
    }

    static boolean indexColumnsMatch(List<String> actualColumns, IndexSpec required) {
        if (actualColumns == null) return false;
        List<String> normalized = actualColumns.stream()
                .map(column -> column.toLowerCase(Locale.ROOT))
                .toList();
        return normalized.equals(required.columns());
    }

    static List<IndexSpec> validateRequiredIndexes(
            Map<String, List<String>> existingIndexes,
            List<IndexSpec> requiredIndexes) throws SQLException {
        List<IndexSpec> missing = new ArrayList<>();
        for (IndexSpec required : requiredIndexes) {
            List<String> actual = existingIndexes.get(required.normalizedName());
            if (actual == null) {
                missing.add(required);
            } else if (!indexColumnsMatch(actual, required)) {
                throw new SQLException(
                    "EventTrail database index '" + required.name()
                                + "' exists with incompatible ordered columns.");
            }
        }
        return List.copyOf(missing);
    }

    private void appendServerFilter(StringBuilder sql, List<Object> params) {
        if (usesServerIdColumn()) {
            sql.append("server_id = ? AND ");
            params.add(serverId);
        }
    }

    private StringBuilder selectBase() {
        return new StringBuilder("SELECT timestamp, action_type, player_uuid, player_name, world, pos_x, pos_y, pos_z, block_state, extra_data FROM ")
                .append(tableName());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object value : params) {
            if (value instanceof Integer n) ps.setInt(i++, n);
            else if (value instanceof Long n) ps.setLong(i++, n);
            else ps.setString(i++, value == null ? null : String.valueOf(value));
        }
        return i;
    }

    private AuditRecord mapRow(ResultSet rs) {
        try {
            return rowCodec.decode(new SchemaV1Row(
                    rs.getLong("timestamp"),
                    rs.getString("action_type"),
                    rs.getString("player_uuid"),
                    rs.getString("player_name"),
                    rs.getString("world"),
                    rs.getInt("pos_x"),
                    rs.getInt("pos_y"),
                    rs.getInt("pos_z"),
                    rs.getString("block_state"),
                    rs.getString("extra_data")
            ));
        } catch (SQLException | RuntimeException error) {
            throw EventTrailQueryException.dataReadFailure(error);
        }
    }

    DeleteParts buildRetentionDeleteBatchQuery(long cutoffTimestamp, int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("Retention batch size must be positive");
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(tableName())
                .append(" WHERE id IN (SELECT id FROM ").append(tableName()).append(" WHERE ");
        List<Object> params = new ArrayList<>();
        appendServerFilter(sql, params);
        sql.append("timestamp < ? ORDER BY timestamp ASC, id ASC LIMIT ?)");
        params.add(cutoffTimestamp);
        params.add(batchSize);
        return new DeleteParts(sql.toString(), List.copyOf(params));
    }

    private <T> T runRead(CheckedSupplier<T> supplier) {
        return runRead(supplier, READ_QUEUE_WAIT_LIMIT_MILLIS);
    }

    /**
     * 6 b: a read that has not started within {@code waitLimitMillis} is given up at once (STORAGE_UNAVAILABLE,
     * busy text in the game) and never starts later. A read that has started runs to its end, bounded by its
     * statement timeouts, as before.
     */
    private <T> T runRead(CheckedSupplier<T> supplier, long waitLimitMillis) {
        ReadOperation operation = new ReadOperation();
        if (!admitReadOperation(operation)) {
            throw recordQueryFailure(EventTrailQueryException.storageUnavailable(
                    new SQLException("EventTrail storage is not ready", "08003")));
        }
        try {
            Future<T> future = submitReadOperation(operation, supplier);
            T result;
            try {
                result = future.get(Math.max(1L, waitLimitMillis), TimeUnit.MILLISECONDS);
            } catch (TimeoutException waitLimitReached) {
                // Exactly one of the worker (QUEUED -> STARTED) and this caller (QUEUED -> EXPIRED) wins.
                if (operation.tryExpire()) {
                    giveUpQueuedRead(operation, future);
                    // Built without a cause: a TimeoutException in the chain would classify it as TIMEOUT.
                    throw new ReadQueueWaitExpiredException(waitLimitMillis);
                }
                // The worker started it in the same instant: wait for that result, exactly as before the limit.
                result = future.get();
            }
            markDatabaseSuccess();
            return result;
        } catch (InterruptedException error) {
            operation.cancel();
            Thread.currentThread().interrupt();
            throw recordQueryFailure(error);
        } catch (CancellationException error) {
            throw recordQueryFailure(error);
        } catch (Exception error) {
            throw recordQueryFailure(error);
        } finally {
            if (!operation.workerStarted() || operation.workerFinished()) {
                activeReadOperations.remove(operation);
            }
        }
    }

    /**
     * 6 b: the caller gave a still-queued read up (it won QUEUED -> EXPIRED). This is the only place that untracks
     * it and releases the backend for it, because its worker never starts it: tracking removal and the SQLite
     * release (SQLiteStorage#backendOperationFinished) happen exactly once.
     */
    private void giveUpQueuedRead(ReadOperation operation, Future<?> future) {
        Runnable queuedTask = operation.queuedTask();
        if (queuedTask != null) readExecutor.remove(queuedTask);
        future.cancel(false);
        activeReadOperations.remove(operation);
        backendOperationFinished();
    }

    /** Package-visible deterministic seam for exercising the shared read lifecycle. */
    final <T> T runTrackedReadForTest(Callable<T> task) {
        return runRead(task::call);
    }

    /** 6 b test seam: the same read lifecycle with a short wait limit. */
    final <T> T runTrackedReadForTest(Callable<T> task, long waitLimitMillis) {
        return runRead(task::call, waitLimitMillis);
    }

    /** 6 b test view: "waited" notices written so far, and reads given up since the last notice. */
    final ReadQueueWaitNotices readQueueWaitNoticesForTest() {
        synchronized (readQueueWaitNoticeLock) {
            return new ReadQueueWaitNotices(readQueueWaitNoticesWritten, readQueueWaitGivenUpSinceNotice);
        }
    }

    record ReadQueueWaitNotices(long noticesWritten, long givenUpSinceLastNotice) {}

    /** 6 b: at most one WARN every 60 s; it says how many reads were given up since the previous one. */
    private void noteReadQueueWaitExpired(ReadQueueWaitExpiredException expired) {
        long givenUp;
        synchronized (readQueueWaitNoticeLock) {
            readQueueWaitGivenUpSinceNotice++;
            long now = System.nanoTime();
            if (readQueueWaitNoticeWritten
                    && now - readQueueWaitNoticeNanos < READ_QUEUE_WAIT_NOTICE_INTERVAL_NANOS) {
                return;
            }
            readQueueWaitNoticeWritten = true;
            readQueueWaitNoticeNanos = now;
            givenUp = readQueueWaitGivenUpSinceNotice;
            readQueueWaitGivenUpSinceNotice = 0L;
            readQueueWaitNoticesWritten++;
        }
        LOGGER.warn("EventTrail {} database reads waited more than {} for a free read worker; {} given up since the "
                + "last notice", getBackendName(), waitText(expired.waitLimitMillis()), givenUp);
    }

    /** "30 s" for whole seconds (the production limit), otherwise milliseconds (test seams). */
    static String waitText(long millis) {
        return millis % 1_000L == 0L ? (millis / 1_000L) + " s" : millis + " ms";
    }

    private EventTrailQueryException recordQueryFailure(Throwable error) {
        EventTrailQueryException queryFailure = EventTrailQueryException.classify(error, isReady());
        Throwable technicalCause = queryFailure.getCause() == null ? queryFailure : queryFailure.getCause();
        if (technicalCause instanceof InterruptedException || technicalCause instanceof CancellationException) {
            LOGGER.debug("EventTrail {} database read was canceled", getBackendName());
        } else if (technicalCause instanceof ReadQueueWaitExpiredException expired) {
            // 6 b: before the generic RejectedExecutionException branch, so the log says "waited", not "full".
            noteReadQueueWaitExpired(expired);
        } else if (technicalCause instanceof RejectedExecutionException) {
            LOGGER.warn("EventTrail {} database read queue is full; the query was rejected without caller-thread execution",
                    getBackendName());
        } else if (queryFailure.getCategory() == EventTrailQueryException.Category.STORAGE_UNAVAILABLE &&
                isConnectionFailure(technicalCause)) {
            logDatabaseFailure("run EventTrail query", technicalCause);
        } else if (EventTrailDiagnostics.containsSqlException(technicalCause)) {
            LOGGER.error(
                    "EventTrail query failed using {} storage (category={}, {}).",
                    getBackendName(), queryFailure.getCategory(),
                    EventTrailDiagnostics.safeFailureSummary(technicalCause));
        } else {
            LOGGER.error(
                    "EventTrail query failed using {} storage (category={}, {}).",
                    getBackendName(), queryFailure.getCategory(),
                    EventTrailDiagnostics.safeFailureSummary(technicalCause));
        }
        return queryFailure;
    }

    private void logSanitizedDatabaseError(String operation, Throwable error) {
        LOGGER.error(
                "Failed to {} using {} storage ({}).",
                operation, getBackendName(), EventTrailDiagnostics.safeFailureSummary(error));
    }

    private void logUnexpectedStorageFailure(String operation, Throwable error) {
        logSanitizedDatabaseError(operation, error);
    }

    /**
     * Connection outages can cause one failure per queued write/query. EventTrail
     * retains first-seen diagnostics without periodic reminders. JDBC
     * diagnostics use only exception type and SQLState, never driver messages.
     */
    private void logDatabaseFailure(String operation, Throwable error) {
        if (!isConnectionFailure(error)) {
            if (EventTrailDiagnostics.containsSqlException(error)) {
                logSanitizedDatabaseError(operation, error);
            } else {
                logSanitizedDatabaseError(operation, error);
            }
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (outageLogLock) {
            if (outageStartedAt < 0L) outageStartedAt = now;
            suppressedOutageErrors++;
        }
        if (claimDiagnostic(OperationalDiagnostic.CONNECTION_OUTAGE)) {
            LOGGER.warn(
                    "EventTrail connectivity diagnostic: database unavailable while attempting to {} using {} storage ({}). " +
                            "Repeated observations update cached status; there are no periodic reminders.",
                    operation, getBackendName(), EventTrailDiagnostics.safeFailureSummary(error));
        }
    }

    private void markDatabaseSuccess() {
        long now = System.currentTimeMillis();
        long outageDurationMs;
        synchronized (outageLogLock) {
            if (outageStartedAt < 0L) return;

            outageDurationMs = Math.max(0L, now - outageStartedAt);
            outageStartedAt = -1L;
            suppressedOutageErrors = 0;
        }
        if (claimDiagnostic(OperationalDiagnostic.CONNECTION_AVAILABLE)) {
            LOGGER.info(
                    "EventTrail database connectivity succeeded for {} storage after {}s; this does not establish recording recovery.",
                    getBackendName(), Math.max(1L, outageDurationMs / 1000L));
        }
    }

    private void logWritePressure(WritePressureReason reason) {
        synchronized (writePressureLogLock) {
            // Only the two capacity refusals mean a full buffer; size refusals say nothing about saturation.
            if (reason == WritePressureReason.COUNT_CAPACITY || reason == WritePressureReason.PAYLOAD_CAPACITY) {
                bufferSaturated = true;
            }
        }
        OperationalDiagnostic diagnostic = switch (reason) {
            case COUNT_CAPACITY -> OperationalDiagnostic.PRESSURE_COUNT;
            case PAYLOAD_CAPACITY -> OperationalDiagnostic.PRESSURE_PAYLOAD;
            case OVERSIZED_ENTRY -> OperationalDiagnostic.PRESSURE_OVERSIZED;
            case OVERSIZED_ACTION -> OperationalDiagnostic.PRESSURE_ACTION_ROWS;
            case OVERSIZED_ACTION_PAYLOAD -> OperationalDiagnostic.PRESSURE_ACTION_PAYLOAD;
        };
        if (claimDiagnostic(diagnostic)) {
            bufferFullErrorLogs.incrementAndGet();
            LOGGER.warn(
                    "EventTrail {} write-pressure diagnostic ({}, {} pending/{}, {} estimated payload bytes/{}). " +
                            "Rejected totals: count={}, payload={}, oversized={}. Accepted buffered entries remain volatile; " +
                            "rejected entries were not queued. Check /eventtrail status; no periodic reminders are emitted.",
                    getBackendName(), reason.description(), pendingWrites.get(), WRITE_BUFFER_CAPACITY,
                    pendingPayloadBytes.get(), maxPendingPayloadBytes(), rejectedFullWrites.get(),
                    rejectedPayloadWrites.get(), rejectedOversizedWrites.get());
        }
    }

    private long totalCapacityRejections() {
        return rejectedFullWrites.get() + rejectedPayloadWrites.get() + rejectedOversizedWrites.get();
    }

    private void logWriteRecoveryIfNeeded(int attempts, int batchSize) {
        boolean recoveredFromPressure;
        synchronized (writePressureLogLock) {
            recoveredFromPressure = bufferSaturated;
            if (recoveredFromPressure) {
                bufferSaturated = false;
            }
        }

        int suppressedRetries;
        long retryDurationMs;
        synchronized (writeRetryLogLock) {
            suppressedRetries = suppressedWriteRetryErrors;
            // M-4 (review-01 NIT-1): a wall clock that stepped back since the stall's latest failure moves the origin
            // back by the same step, so the recovery line keeps the time the stall had already lasted.
            long now = diagnosticNowMillis();
            long origin = writeRetryStartedAt;
            if (origin >= 0L && now < writeRetryLastSeenAt) origin = Math.max(0L, origin - (writeRetryLastSeenAt - now));
            retryDurationMs = origin < 0L ? 0L : Math.max(0L, now - origin);
            writeRetryStartedAt = -1L;
            suppressedWriteRetryErrors = 0;
            stallWarningsThisStall = 0L;
        }

        // A commit ends a lasting stall: report it once and re-arm the stall warning for the next one.
        if (releaseDiagnostic(OperationalDiagnostic.WRITE_STALL)) {
            LOGGER.info(
                    "EventTrail database writes recovered for {} storage after {} min of retries; {} pending.",
                    getBackendName(), retryDurationMs / 60_000L, pendingWrites.get());
        }

        if ((attempts > 1 || recoveredFromPressure) && claimDiagnostic(OperationalDiagnostic.WRITE_PROGRESS)) {
            LOGGER.info(
                    "EventTrail {} database write progress: after {} attempt(s) and {}s, commit-confirmed a batch of {}; {} " +
                            "pending write(s). Suppressed retry errors: {}. Rejected writes since startup: {}.",
                    getBackendName(), attempts, Math.max(1L, retryDurationMs / 1000L), batchSize, pendingWrites.get(),
                    suppressedRetries, totalCapacityRejections());
        }
    }

    /**
     * Ends the retry duration of a unit that was resolved without a commit (dropped as permanently failed), so
     * the stall warning and the progress line measure only from the next retryable failure (writer-01 2.8,
     * review-01 NIT-3). An outstanding stall warning stays claimed until a commit releases it (and prints the
     * recovery line); the next stall's repeated warnings count from its own first failure (af-1 F8).
     */
    private void resetWriteRetryClock() {
        synchronized (writeRetryLogLock) {
            writeRetryStartedAt = -1L;
            stallWarningsThisStall = 0L;
        }
    }

    /**
     * af-1 F7 (writer thread): adds one discarded batch to the drop report and writes the ERROR line when none
     * was written in this run yet or {@link #DROPPED_BATCH_LOG_INTERVAL_MILLIS} has passed since the last one.
     * Only the figures are taken under the report's own lock; logging happens outside it. The counters
     * (permanentFailed) are updated by resolvePending as before.
     */
    private void recordDroppedBatch(int rows, int attempts, DropReason reason, String diagnostic) {
        DroppedBatchLine line;
        synchronized (droppedBatchLog) {
            DroppedBatchLog log = droppedBatchLog;
            log.rows += rows;
            log.batches++;
            log.batchesByReason[reason.ordinal()]++;
            if (log.diagnostics.contains(diagnostic) || log.diagnostics.size() < MAX_DROP_LINE_DIAGNOSTICS) {
                log.diagnostics.add(diagnostic);
            } else {
                log.diagnosticsOmitted++;
            }
            log.lastAttempts = attempts;
            line = takeDroppedBatchLineIfDue(false);
        }
        if (line != null) logDroppedBatchLine(line);
    }

    /**
     * af-1 F7: writes the drop line for drops held back by the rate limit. {@code force} (the writer's end and
     * storage close) ignores the interval, so no drop of a run stays unreported; otherwise the line waits until
     * the interval since the previous line has passed. Called by the writer thread, and by the closing thread
     * after the writer ended or was abandoned; the lock only guards the figures.
     */
    private void flushDroppedBatchLog(boolean force) {
        DroppedBatchLine line;
        synchronized (droppedBatchLog) {
            line = takeDroppedBatchLineIfDue(force);
        }
        if (line != null) logDroppedBatchLine(line);
    }

    /** Under the {@link #droppedBatchLog} lock: the pending figures, reset, when a line is due; else null. */
    private DroppedBatchLine takeDroppedBatchLineIfDue(boolean force) {
        DroppedBatchLog log = droppedBatchLog;
        if (log.batches == 0L) return null;
        long now = diagnosticNowMillis();
        // A wall clock that stepped back counts as due once; the new time then starts the next interval.
        boolean due = force || !log.lineWritten || now < log.lastLineMillis
                || now - log.lastLineMillis >= DROPPED_BATCH_LOG_INTERVAL_MILLIS;
        if (!due) return null;
        DroppedBatchLine line = new DroppedBatchLine(log.rows, log.batches, log.batchesByReason.clone(),
                List.copyOf(log.diagnostics), log.diagnosticsOmitted, log.lastAttempts);
        log.lineWritten = true;
        log.lastLineMillis = now;
        log.rows = 0L;
        log.batches = 0L;
        java.util.Arrays.fill(log.batchesByReason, 0L);
        log.diagnostics.clear();
        log.diagnosticsOmitted = 0L;
        return line;
    }

    /**
     * af-1 F7: one batch keeps the original drop line (rows, attempts, the in-doubt sentence, its diagnostic);
     * several batches get one line with the rows and batches since the previous line, each reason with its
     * batch count, and the distinct diagnostics. Both start "could not confirm persistence"; the in-doubt
     * sentence is present exactly when a batch of the line was not retry-safe.
     */
    private void logDroppedBatchLine(DroppedBatchLine line) {
        boolean notRetrySafe = line.batchesByReason()[DropReason.COMMIT_OUTCOME_UNKNOWN.ordinal()] > 0L
                || line.batchesByReason()[DropReason.CONNECTION_NOT_PROVEN_DISCARDED.ordinal()] > 0L;
        String inDoubt = notRetrySafe
                ? " The transaction outcome could not be proven safe to retry without risking duplicate rows."
                : "";
        if (line.batches() == 1L) {
            LOGGER.error(
                    "EventTrail could not confirm persistence of {} accepted audit entr{} using {} storage after {} attempt(s). " +
                            "The batch has been discarded to prevent an infinite write stall.{} Diagnostic: {}.",
                    line.rows(), line.rows() == 1L ? "y" : "ies", getBackendName(), line.lastAttempts(), inDoubt,
                    line.diagnostics().isEmpty() ? "" : line.diagnostics().get(0));
            return;
        }
        StringBuilder reasons = new StringBuilder();
        for (DropReason reason : DropReason.values()) {
            long count = line.batchesByReason()[reason.ordinal()];
            if (count == 0L) continue;
            if (reasons.length() > 0) reasons.append("; ");
            reasons.append(reason.text).append(": ").append(count).append(count == 1L ? " batch" : " batches");
        }
        LOGGER.error(
                "EventTrail could not confirm persistence of {} accepted audit entr{} in {} batches using {} storage " +
                        "since the previous such line. The batches have been discarded to prevent an infinite write " +
                        "stall.{} Reasons: {}. Diagnostics: {}{}. Permanently discarded without commit confirmation " +
                        "since startup: {}.",
                line.rows(), line.rows() == 1L ? "y" : "ies", line.batches(), getBackendName(), inDoubt, reasons,
                String.join(" | ", line.diagnostics()),
                line.diagnosticsOmitted() == 0L ? ""
                        : " (and " + line.diagnosticsOmitted() + " more batch(es) with other diagnostics)",
                permanentFailedWrites.get());
    }

    /**
     * The original signature, kept for RecordingProducerLogTest's reflective call (no production caller): the
     * same lines with the failure's message-free summary and chain codes (fix-03 C), no lost-connection part.
     */
    private void logRetryableWriteFailure(int batchSize, SQLException error) {
        logRetryableWriteFailure(batchSize, EventTrailDiagnostics.safeFailureSummary(error)
                + ", chain=" + failureChainCodes(error));
    }

    private void logRetryableWriteFailure(int batchSize, String diagnostic) {
        long now = diagnosticNowMillis();
        long retryStartedAt;
        boolean stallWarningDue = false;
        synchronized (writeRetryLogLock) {
            if (writeRetryStartedAt < 0L) {
                writeRetryStartedAt = now;
            } else if (now < writeRetryLastSeenAt) {
                // M-4: the wall clock stepped back. Move the stall origin back by the same step (as F7 guards its
                // line interval against a step back), so the stall keeps the time it had already lasted and the
                // step delays neither the first nor a repeated warning. Never below 0 (negative means "no stall").
                writeRetryStartedAt = Math.max(0L, writeRetryStartedAt - (writeRetryLastSeenAt - now));
            }
            writeRetryLastSeenAt = now;
            suppressedWriteRetryErrors++;
            retryStartedAt = writeRetryStartedAt;
            // af-1 F8: one warning for each full 5 minutes of the same stall. A failure seen late (one attempt
            // that took long) warns once for all the periods it passed, never once per missed period.
            long periods = (now - retryStartedAt) / WRITE_STALL_WARN_MILLIS;
            if (periods > stallWarningsThisStall) {
                stallWarningsThisStall = periods;
                stallWarningDue = true;
            }
        }
        if (claimDiagnostic(OperationalDiagnostic.WRITE_RETRY)) {
            LOGGER.warn(
                    "EventTrail could not persist a batch of {} audit entr{} using {} storage. " +
                            "The accepted volatile batch remains pending and will be retried ({}); repeated failures update status only.",
                    batchSize, batchSize == 1 ? "y" : "ies", getBackendName(), diagnostic);
        }
        // The first-seen texts above print once per run; a lasting stall gets a warning of its own after 5 minutes,
        // repeated every 5 minutes while it lasts (af-1 F8). The first warning claims WRITE_STALL, so the commit
        // that ends the stall prints the recovery line once (writer-01 2.8).
        long stalledMs = now - retryStartedAt;
        if (stallWarningDue) {
            claimDiagnostic(OperationalDiagnostic.WRITE_STALL);
            LOGGER.warn(
                    "EventTrail has retried database writes for {} min without a successful commit using {} storage " +
                            "({} pending, rejected writes since startup: {}). Accepted entries stay pending; " +
                            "check /eventtrail status. This warning repeats every 5 minutes while the stall lasts. " +
                            "Last failure: {}.",
                    stalledMs / 60_000L, getBackendName(), pendingWrites.get(), totalCapacityRejections(), diagnostic);
        }
    }

    /**
     * fix-03 C: the writer's message-free diagnostic for a failed attempt: the batch failure's safe summary, the
     * SQLState and vendor code of each link of its cause and next-exception chain, and, when the attempt's rollback
     * failed with a connection outage, "connection lost" with that rollback failure's summary and chain. Exception
     * messages are never copied (they can hold row values or endpoints).
     */
    private static String writeFailureDiagnostic(BatchWriteFailure failure) {
        StringBuilder text = new StringBuilder(EventTrailDiagnostics.safeFailureSummary(failure.getCause()))
                .append(", chain=").append(failureChainCodes(failure.getCause()));
        SQLException lost = failure.connectionLostBy();
        if (lost != null) {
            text.append("; connection lost: the rollback failed with ")
                    .append(EventTrailDiagnostics.safeFailureSummary(lost))
                    .append(", chain=").append(failureChainCodes(lost));
        }
        return text.toString();
    }

    /** Bounded number of chain links {@link #failureChainCodes} prints. */
    private static final int MAX_DIAGNOSTIC_CHAIN_LINKS = 8;

    /**
     * "[Type state/vendor, ...]" for each throwable of {@link EventTrailDiagnostics#failureChain}: simple type name,
     * then SQLState and vendor code for a SQLException ("-" for an absent state). No message text.
     * Legend: "BatchUpdateException S1009/0" = type, SQLState S1009, vendor error code 0 ("state/vendor").
     */
    static String failureChainCodes(Throwable error) {
        List<String> links = new ArrayList<>();
        for (Throwable current : EventTrailDiagnostics.failureChain(error)) {
            if (links.size() == MAX_DIAGNOSTIC_CHAIN_LINKS) {
                links.add("...");
                break;
            }
            String type = current.getClass().getSimpleName();
            type = EventTrailDiagnostics.sanitizeLogValue(type == null || type.isBlank() ? "UnknownFailure" : type, 64);
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                links.add(type + " " + (state == null || state.isBlank() ? "-"
                        : EventTrailDiagnostics.sanitizeLogValue(state, 16)) + "/" + sql.getErrorCode());
            } else {
                links.add(type);
            }
        }
        return "[" + String.join(", ", links) + "]";
    }

    /**
     * fix-03 A hardening: true when any link of {@code failure}'s cause and next-exception chain reports a definite
     * refusal of the statement or its rows (SQLState class 22 data exception, 23 integrity constraint violation,
     * 42 syntax error or access rule violation, 45 MySQL user-defined SIGNAL). Such a failure keeps the bounded
     * path even when its rollback then failed with a connection outage.
     */
    static boolean isDefiniteRowRefusal(SQLException failure) {
        for (Throwable current : EventTrailDiagnostics.failureChain(failure)) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("22") || state.startsWith("23")
                        || state.startsWith("42") || state.startsWith("45"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isConnectionFailure(Throwable error) {
        return EventTrailDiagnostics.isConnectionOutage(error);
    }

    @Override
    public void close() {
        try (EventTrailJdbcLogScope.Scope ignored = EventTrailJdbcLogScope.open()) {
            closeScoped();
        }
    }

    private void closeScoped() {
        Thread writer;
        long shutdownTimeout;
        synchronized (writeLifecycleLock) {
            if (closeStarted) return;
            closeStarted = true;
            acceptingWrites = false;
            writerStateVersion++;
            ready = false;
            writeShutdownRequested = true;
            shutdownTimeout = writeShutdownTimeoutMillis();
            writerShutdownDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeout);
            writer = writerThread;
        }
        publishWriteObservation();

        // Stop and cancel investigation reads immediately. Writer flushing may
        // use most of the shutdown deadline, so reads must not wait behind it.
        cancelActiveReads();
        cancelAuxiliaryMutations();

        int pendingAtStart = pendingWrites.get();
        LOGGER.info(
                "Shutting down {} storage with {} pending database write(s); allowing up to {}s to flush.",
                getBackendName(), pendingAtStart, Math.max(1L, shutdownTimeout / 1000L));

        boolean interrupted = false;
        if (writer != null) {
            try {
                writer.join(shutdownTimeout);
            } catch (InterruptedException e) {
                interrupted = true;
            }

            if (writer.isAlive()) {
                if (activeWriteOperation.diagnostics != null) JdbcShutdownDiagnostics.force(
                        activeWriteOperation.diagnostics, writerShutdownDeadlineNanos, interrupted);
                forceWriterStop = true;
                writer.interrupt();
                activeWriteOperation.cancel();
                abortActiveWriteConnection();
                try {
                    writer.join(WRITER_ABORT_GRACE_MS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }

        if (writer != null && writer.isAlive()) {
            LOGGER.error(
                    "EventTrail {} database writer did not terminate after the bounded shutdown deadline; {} accepted write(s) remain unresolved.",
                    getBackendName(), pendingWrites.get());
        }

        // writer-spool design-02 5.2: writer ended -> CLOSE and a bounded wait (L13); writer alive or the wait
        // expired -> abandoned: no clean mark; only the spool thread closes the connection, after its running call.
        DiskSpool diskSpool = spool;
        boolean spoolClosedInTime = diskSpool == null
                || diskSpool.close(writer == null || !writer.isAlive(), DiskSpool.CLOSE_WAIT_MILLIS);

        boolean readsTerminated = awaitReadExecutorShutdown();
        logReadShutdownResult(readsTerminated);
        awaitAuxiliaryMutations();
        closeDataSourceQuietly();
        if (interrupted) Thread.currentThread().interrupt();

        // af-1 F7: a writer abandoned at the deadline cannot flush its own drop report; this thread does it.
        flushDroppedBatchLog(true);
        WriteBufferStats stats = getWriteBufferStats();
        // m-10 (design-02 11.3): for this run's own actions, accepted = written + waiting on disk + could not be
        // stored + unreadable + expired + permanentFailed + shutdownLost + pending + staged but not confirmed.
        DiskSpool.SpoolStats spoolStats = diskSpool == null ? null : diskSpool.stats();
        long waitingOnDisk = spoolStats == null ? 0L : spoolStats.waitingThisRunRows();
        long spoolUnresolved = spoolStats == null ? 0L : spoolStats.couldNotStoreRows()
                + spoolStats.unresolvedThisRunRows() + spoolStats.stagedRows();
        if (spoolStats != null) logSpoolCloseLines(diskSpool, spoolStats, spoolClosedInTime);
        if (diskSpool != null && diskSpool.fileDamaged() && waitingOnDisk > 0) {
            // R-4 (r9): this run's rows still in a file damaged in the run are not "waiting to be written": the next
            // start sets that file aside (7.3), so they are unresolved here.
            LOGGER.error("EventTrail disk spool at close: {} action row(s) of this run stay in a spool file that failed "
                    + "during the run ({}); it is set aside at the next start and they are not written.",
                    waitingOnDisk, diskSpool.damagedReason());
            spoolUnresolved += waitingOnDisk;
            waitingOnDisk = 0L;
        }
        // af-1 F4: refusals after the writer stopped make the close incomplete as well, and are stated as a figure.
        if (waitingOnDisk > 0 && stats.pending() == 0 && stats.shutdownLost() == 0 && stats.permanentFailed() == 0
                && stats.rejectedClosed() == 0 && spoolUnresolved == 0
                && stats.written() + waitingOnDisk == stats.accepted()) {
            LOGGER.info(
                    "{} storage closed after persisting {} of {} accepted database write(s); {} action rows are waiting "
                            + "on disk and will be written at the next start. "
                            + "Rejected by count/payload/entry-size limits: {}/{}/{}.",
                    getBackendName(), stats.written(), stats.accepted(), waitingOnDisk, stats.rejectedFull(),
                    stats.rejectedPayload(), stats.rejectedOversized());
        } else if (stats.pending() == 0 && stats.shutdownLost() == 0 && stats.permanentFailed() == 0 &&
                stats.rejectedClosed() == 0 && spoolUnresolved == 0 && waitingOnDisk == 0
                && stats.written() == stats.accepted()) {
            LOGGER.info(
                    "{} storage closed after persisting all {} accepted database write(s). " +
                            "Rejected by count/payload/entry-size limits: {}/{}/{}.",
                    getBackendName(), stats.accepted(), stats.rejectedFull(), stats.rejectedPayload(),
                    stats.rejectedOversized());
        } else {
            // af-1 F4: rows still pending here were not resolved by the shutdown deadline (the writer was abandoned);
            // they are named as unresolved, never only as "pending". M-3: an abandoned writer can still end later and
            // report them in its own line, or commit them, so they are not called lost.
            LOGGER.error(
                    "{} storage closed with incomplete audit persistence: {} unresolved at the shutdown deadline " +
                            "(accepted, not commit-confirmed; the writer may still report or commit them later; " +
                            "{} estimated payload bytes), " +
                            "{} discarded at shutdown without commit confirmation, {} permanently discarded without commit confirmation, " +
                            "{} refused because the database writer had stopped or the storage was closing, " +
                            "rejected by count/payload/entry-size limits: {}/{}/{}. Uncertain transactions may still have database rows.",
                    getBackendName(), stats.pending(), stats.pendingPayloadBytes(), stats.shutdownLost(),
                    stats.permanentFailed(), stats.rejectedClosed(), stats.rejectedFull(), stats.rejectedPayload(),
                    stats.rejectedOversized());
        }
    }

    /** design-02 11.3: the spool's part of the shutdown summary; carried-in actions are reported apart (m-10). */
    private void logSpoolCloseLines(DiskSpool diskSpool, DiskSpool.SpoolStats s, boolean closedInTime) {
        if (diskSpool.spoolThreadDied()) {
            // R-3 (r9): nothing the stop staged reached the disk; close() counted the staged actions as not stored.
            LOGGER.error("EventTrail disk spool: its thread ended with a fatal error during the run; the actions staged "
                    + "for it were not stored and are counted as could not be stored. {} action(s) already on disk stay "
                    + "there for the next start.", s.waitingFrames());
        } else if (s.savedAtStopActions() > 0) {
            LOGGER.info("{} action(s) still in memory at the deadline were saved to the disk spool.", s.savedAtStopActions());
        }
        if (!closedInTime) {
            long actions = Math.max(0L, diskSpool.abandonedStagedActions);
            long rows = Math.max(0L, diskSpool.abandonedStagedRows);
            LOGGER.error("The disk spool did not finish closing within 5 s; up to {} accepted action(s) ({} rows) may not "
                    + "have reached the disk.", actions, rows);
        } else if (diskSpool.closedWithoutMarkWriterRunning()) {
            // MINOR-12 (r2): closed in time; only the clean mark was skipped, because the writer was still running.
            LOGGER.warn("The database writer was still running at stop, so the disk spool closed without its clean mark; "
                    + "every action it had stored is kept and is written at the next start.");
        }
        if (s.carriedInActions() > 0 || s.carriedInWrittenActions() > 0) {
            LOGGER.info("EventTrail disk spool: {} action(s) carried in from an earlier run, {} of them written this run "
                    + "({} rows), {} dropped as refused by the database.", s.carriedInActions(),
                    s.carriedInWrittenActions(), s.carriedInWrittenRows(), diskSpool.carriedInLostActions.get());
        }
        if (s.couldNotStoreActions() + s.unreadableActions() + s.expiredActions() > 0) {
            LOGGER.error("EventTrail disk spool at close: {} accepted action(s) could not be stored ({} rows), {} failed "
                    + "their check ({} rows as stored), {} expired while waiting ({} rows).", s.couldNotStoreActions(),
                    s.couldNotStoreRows(), s.unreadableActions(), s.unreadableRowsAsStored(), s.expiredActions(),
                    s.expiredRows());
        }
    }

    private void abortActiveWriteConnection() {
        Connection connection = activeWriteConnection;
        if (connection == null) return;
        abortWriteConnection(connection);
    }

    /** SQLite dispatches retirement without the generic inline logical-close fallback. */
    protected void abortWriteConnection(Connection connection) {
        JdbcShutdownDiagnostics.CancelCall diagnosticCall = activeWriteOperation.diagnostics == null
                ? null : JdbcShutdownDiagnostics.abortTarget(activeWriteOperation.diagnostics, connection);
        ConnectionAbortResult result = abortConnectionFailSafe(connection);
        if (diagnosticCall != null) JdbcShutdownDiagnostics.abortReturned(diagnosticCall, result);
        if (!result.closed()) {
            LOGGER.warn(
                    "Unable to abort EventTrail's active database write during shutdown ({}).",
                    EventTrailDiagnostics.safeFailureSummary(result.failure()));
        }
    }

    /**
     * JDBC permits drivers to implement {@link Connection#abort} differently.
     * Xerial SQLite currently returns normally without closing its connection,
     * so a successful return is not treated as proof that shutdown released the
     * database. This authoritative primitive verifies closure and invokes the
     * ordinary close fallback whenever abort did not actually close.
     */
    static ConnectionAbortResult abortConnectionFailSafe(Connection connection) {
        if (connection == null) {
            return new ConnectionAbortResult(false, false, false, true, null);
        }

        boolean abortReturned = false;
        Throwable abortFailure = null;
        try {
            connection.abort(Runnable::run);
            abortReturned = true;
        } catch (SQLException | RuntimeException failure) {
            abortFailure = failure;
        }

        boolean closedByAbort = false;
        Throwable statusFailure = null;
        try {
            closedByAbort = connection.isClosed();
        } catch (SQLException | RuntimeException failure) {
            statusFailure = failure;
        }

        boolean closeInvoked = false;
        Throwable closeFailure = null;
        if (!closedByAbort) {
            closeInvoked = true;
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                closeFailure = failure;
            }
        }

        boolean closed = closedByAbort;
        if (!closed) {
            try {
                closed = connection.isClosed();
            } catch (SQLException | RuntimeException failure) {
                if (statusFailure == null) statusFailure = failure;
                else if (statusFailure != failure) statusFailure.addSuppressed(failure);
            }
        }

        Throwable unresolved = null;
        if (!closed) {
            unresolved = closeFailure != null ? closeFailure
                    : statusFailure != null ? statusFailure
                    : abortFailure != null ? abortFailure
                    : new SQLException("JDBC connection remained open after abort and close");
            if (abortFailure != null && abortFailure != unresolved) {
                unresolved.addSuppressed(abortFailure);
            }
            if (statusFailure != null && statusFailure != unresolved) {
                unresolved.addSuppressed(statusFailure);
            }
            if (closeFailure != null && closeFailure != unresolved) {
                unresolved.addSuppressed(closeFailure);
            }
        }
        return new ConnectionAbortResult(
                abortReturned, closedByAbort, closeInvoked, closed, unresolved);
    }

    private void closeDataSourceQuietly() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        Exception failure = null;
        try {
            beforeDataSourceClose();
        } catch (Exception e) {
            failure = e;
        }
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        try {
            closeBackendResources();
        } catch (Exception e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) {
            recordCleanupFailure(EventTrailFailure.from(failure, EventTrailFailure.Phase.CLEANUP,
                    EventTrailFailure.Backend.fromLabel(getBackendName())));
            for (Throwable secondary : failure.getSuppressed()) {
                recordCleanupFailure(EventTrailFailure.from(secondary, EventTrailFailure.Phase.CLEANUP,
                        EventTrailFailure.Backend.fromLabel(getBackendName())));
            }
            LOGGER.warn("Error closing EventTrail datasource resources ({}).",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    @Override
    public final boolean isReady() {
        HikariDataSource ds = dataSource;
        return refreshBackendRestriction() == null && ready && ds != null && !ds.isClosed();
    }

    @Override
    public final EventTrailFailure initializationFailure() { return initializationFailure; }

    @Override
    public final EventTrailFailure terminalBackendAccessFailure() { return refreshBackendRestriction(); }

    @Override
    public final List<EventTrailFailure> cleanupFailures() { return cleanupFailureFacts.get(); }

    protected final void recordCleanupFailure(EventTrailFailure failure) {
        cleanupFailureFacts.updateAndGet(previous -> {
            if (previous.contains(failure) || previous.size() >= 8) return previous;
            List<EventTrailFailure> next = new ArrayList<>(previous);
            next.add(failure);
            return List.copyOf(next);
        });
    }

    @Override
    public final WriteAdmissionSnapshot writeAdmissionSnapshot() {
        refreshBackendRestriction();
        synchronized (writeLifecycleLock) {
            WriteAdmissionSnapshot.State state;
            if (closeStarted) state = WriteAdmissionSnapshot.State.STOPPING;
            else if (initializationFailure != null || backendRestriction != null || writerTerminated) state = WriteAdmissionSnapshot.State.DISABLED;
            else if (acceptingWrites) state = retryingWrites.get() || !hasBufferCapacityHintLocked()
                    ? WriteAdmissionSnapshot.State.BUFFERING : WriteAdmissionSnapshot.State.ACCEPTING;
            else state = initializationStarted ? WriteAdmissionSnapshot.State.STARTING
                    : WriteAdmissionSnapshot.State.STOPPED;
            EventTrailFailure failure = initializationFailure != null ? initializationFailure
                    : backendRestriction != null ? backendRestriction : writerFailure;
            HikariDataSource source = dataSource;
            WriteAdmissionSnapshot.History history = ready && backendRestriction == null && !closeStarted
                    && source != null && !source.isClosed()
                    ? WriteAdmissionSnapshot.History.READABLE : WriteAdmissionSnapshot.History.UNAVAILABLE;
            if (latestWriterObservation.state() != state
                    || latestWriterObservation.history() != history
                    || !java.util.Objects.equals(latestWriterObservation.failure(), failure)) {
                writerStateVersion = Math.max(writerStateVersion, latestWriterObservation.version() + 1L);
                latestWriterObservation = new WriteAdmissionSnapshot(writerStateVersion, state, failure, history);
            }
            return latestWriterObservation;
        }
    }

    @Override
    public final WriteObservation observeWriteAdmission(Consumer<WriteAdmissionSnapshot> observer) {
        java.util.Objects.requireNonNull(observer, "observer");
        writerObservers.add(observer);
        try { observer.accept(writeAdmissionSnapshot()); }
        catch (Throwable failure) { writerObservers.remove(observer); throw failure; }
        return () -> writerObservers.remove(observer);
    }

    /** All callers are outside writer/native/cancellation locks. */
    private void publishWriteObservation() {
        WriteAdmissionSnapshot snapshot = writeAdmissionSnapshot();
        long previous;
        do {
            previous = lastPublishedWriterVersion.get();
            if (snapshot.version() <= previous) return;
        } while (!lastPublishedWriterVersion.compareAndSet(previous, snapshot.version()));
        for (Consumer<WriteAdmissionSnapshot> observer : writerObservers) {
            try { observer.accept(snapshot); }
            catch (Throwable failure) {
                if (observerFailureReported.compareAndSet(false, true)) {
                    LOGGER.warn("EventTrail writer observation delivery failed ({}); cached writer admission remains authoritative.",
                            EventTrailDiagnostics.safeFailureSummary(failure));
                }
            }
        }
    }

    @Override
    public final String getServerId() {
        return serverId;
    }

    public final WriteBufferStats getWriteBufferStats() {
        synchronized (writeLifecycleLock) {
            Thread writer = writerThread;
            return new WriteBufferStats(
                    WRITE_BUFFER_CAPACITY,
                    maxPendingPayloadBytes(),
                    maxSingleEntryRetainedBytes(),
                    pendingWrites.get(),
                    pendingPayloadBytes.get(),
                    acceptedWrites.get(),
                    successfulWrites.get(),
                    rejectedFullWrites.get(),
                    rejectedPayloadWrites.get(),
                    rejectedOversizedWrites.get(),
                    rejectedClosedWrites.get(),
                    permanentFailedWrites.get(),
                    shutdownLostWrites.get(),
                    successfulWriteBatches.get(),
                    bufferFullErrorLogs.get(),
                    retryingWrites.get(),
                    writer != null && writer.isAlive(),
                    acceptingWrites && (hasBufferCapacityHintLocked() || spoolHintLocked()),
                    acceptingWrites);
        }
    }

    final ReadExecutorStats getReadExecutorStats() {
        return new ReadExecutorStats(
                readExecutor.getCorePoolSize(),
                readExecutor.getMaximumPoolSize(),
                readExecutor.getQueue().size() + readExecutor.getQueue().remainingCapacity(),
                readExecutor.getQueue().size(),
                readExecutor.getActiveCount(),
                activeReadOperations.size(),
                readExecutor.isShutdown(),
                readExecutor.isTerminated(),
                readExecutor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
    }

    protected static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Conservative retained-heap estimate for the queued object graph. Java may
     * use compact strings, but charging two bytes per character plus fixed
     * object/string overhead avoids relying on that implementation detail.
     */
    static long estimateRetainedPayloadBytes(AuditRecord record) {
        return SchemaV1RowCodec.retainedBytes(SchemaV1RowCodec.encode(record));
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    record ReadExecutorStats(
            int coreWorkers,
            int maximumWorkers,
            int queueCapacity,
            int queued,
            int activeWorkers,
            int trackedOperations,
            boolean shutdown,
            boolean terminated,
            boolean abortPolicy) {}

    static final class ReadOperation {
        // 6 b admission switch: QUEUED -> STARTED (worker) or QUEUED -> EXPIRED (waiting caller), decided by one CAS.
        private static final int QUEUED = 0;
        private static final int STARTED = 1;
        private static final int EXPIRED = 2;
        private final AtomicInteger admission = new AtomicInteger(QUEUED);
        private volatile Runnable queuedTask;
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicBoolean workerStarted = new AtomicBoolean();
        private final AtomicBoolean workerFinished = new AtomicBoolean();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<JdbcStatementCancellation> activeStatement = new AtomicReference<>();
        private final JdbcShutdownDiagnostics.Operation diagnostics = JdbcShutdownDiagnostics.operation(this, "READ");

        JdbcShutdownDiagnostics.Execution diagnosticExecution(Statement statement) {
            if (diagnostics == null) return null;
            JdbcStatementCancellation lease = activeStatement.get();
            return lease != null && lease.owns(statement) ? lease.diagnosticExecution() : null;
        }

        void attach(Future<?> submittedFuture) {
            future.set(submittedFuture);
            if (canceled.get()) submittedFuture.cancel(true);
        }

        void register(Statement statement) throws SQLException {
            JdbcStatementCancellation lease = new JdbcStatementCancellation(statement);
            if (diagnostics != null) lease.observeExecution(
                    JdbcShutdownDiagnostics.register(diagnostics, lease, statement, null));
            if (canceled.get()) {
                try {
                    cancelStatement(lease);
                } finally {
                    lease.retire();
                }
                throw new SQLException("EventTrail database read was canceled", "57014");
            }
            if (!activeStatement.compareAndSet(null, lease)) {
                throw new SQLException("EventTrail read operation already has an active JDBC statement");
            }
            if (canceled.get()) {
                try {
                    cancelStatement(lease);
                } finally {
                    lease.retire();
                    activeStatement.compareAndSet(lease, null);
                }
                throw new SQLException("EventTrail database read was canceled", "57014");
            }
        }

        void clear(Statement statement) {
            JdbcStatementCancellation lease = activeStatement.get();
            if (lease != null && lease.owns(statement)) {
                lease.retire();
                activeStatement.compareAndSet(lease, null);
            }
        }

        void cancel() {
            canceled.set(true);
            Future<?> submittedFuture = future.get();
            if (submittedFuture != null) submittedFuture.cancel(true);
            JdbcStatementCancellation lease = activeStatement.get();
            if (lease != null) cancelStatement(lease);
        }

        boolean isCanceled() {
            return canceled.get();
        }

        void markWorkerStarted() {
            workerStarted.set(true);
        }

        void markWorkerFinished() {
            workerFinished.set(true);
        }

        boolean workerStarted() {
            return workerStarted.get();
        }

        boolean workerFinished() {
            return workerFinished.get();
        }

        /** The worker wins the switch and may run the read. */
        boolean tryStart() {
            return admission.compareAndSet(QUEUED, STARTED);
        }

        /** The waiting caller wins the switch: the read is given up and must never start. */
        boolean tryExpire() {
            return admission.compareAndSet(QUEUED, EXPIRED);
        }

        void attachQueuedTask(Runnable task) {
            queuedTask = task;
        }

        Runnable queuedTask() {
            return queuedTask;
        }

    }

    /**
     * 6 b: a read gave up after waiting {@link #READ_QUEUE_WAIT_LIMIT_MILLIS} for a free read worker. It is a
     * RejectedExecutionException, so it is classed STORAGE_UNAVAILABLE like a full queue and the game shows the
     * busy text. It never carries a cause: EventTrailQueryException.classify checks the whole cause chain for a
     * timeout first, and a TimeoutException there would turn it into TIMEOUT ("try a narrower search").
     */
    static final class ReadQueueWaitExpiredException extends RejectedExecutionException {
        private final long waitLimitMillis;

        ReadQueueWaitExpiredException(long waitLimitMillis) {
            super("EventTrail database read waited more than " + waitText(waitLimitMillis)
                    + " for a free read worker and was given up before it started");
            this.waitLimitMillis = waitLimitMillis;
        }

        long waitLimitMillis() {
            return waitLimitMillis;
        }
    }

    /** Single-writer statement ownership; cancellation stays set through late publication during stop. */
    static final class WriteOperation {
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicReference<JdbcStatementCancellation> activeStatement = new AtomicReference<>();
        private final JdbcShutdownDiagnostics.Operation diagnostics = JdbcShutdownDiagnostics.operation(this, "WRITE");

        void register(Statement statement) throws SQLException {
            register(statement, null);
        }

        void register(Statement statement, Connection connection) throws SQLException {
            JdbcStatementCancellation lease = new JdbcStatementCancellation(statement);
            if (diagnostics != null) lease.observeExecution(
                    JdbcShutdownDiagnostics.register(diagnostics, lease, statement, connection));
            if (canceled.get()) {
                try {
                    cancelStatement(lease);
                } finally {
                    lease.retire();
                }
                throw new SQLException("EventTrail database write was canceled during shutdown", "57014");
            }
            if (!activeStatement.compareAndSet(null, lease)) {
                throw new SQLException("EventTrail writer already has an active JDBC statement");
            }
            if (canceled.get()) {
                try {
                    cancelStatement(lease);
                } finally {
                    lease.retire();
                    activeStatement.compareAndSet(lease, null);
                }
                throw new SQLException("EventTrail database write was canceled during shutdown", "57014");
            }
        }

        JdbcShutdownDiagnostics.Execution diagnosticExecution(Statement statement) {
            if (diagnostics == null) return null;
            JdbcStatementCancellation lease = activeStatement.get();
            return lease != null && lease.owns(statement) ? lease.diagnosticExecution() : null;
        }

        void clear(Statement statement) {
            JdbcStatementCancellation lease = activeStatement.get();
            if (lease != null && lease.owns(statement)) {
                lease.retire();
                activeStatement.compareAndSet(lease, null);
            }
        }

        void cancel() {
            canceled.set(true);
            JdbcStatementCancellation lease = activeStatement.get();
            if (lease != null) cancelStatement(lease);
        }
    }

    private static void cancelStatement(JdbcStatementCancellation lease) {
        JdbcStatementCancellation.Result result = lease.cancelAndClose();
        if (result.claimed() && !result.closed()) {
            LOGGER.warn("EventTrail JDBC statement cancellation could not confirm statement closure ({})",
                    EventTrailDiagnostics.safeFailureSummary(result.failure()));
        } else if (result.failure() != null) {
            LOGGER.debug("EventTrail JDBC statement closed after cancellation reported a driver failure ({})",
                    EventTrailDiagnostics.safeFailureSummary(result.failure()));
        }
    }

    public record WriteBufferStats(
            int capacity,
            long payloadCapacityBytes,
            long maxSingleEntryBytes,
            int pending,
            long pendingPayloadBytes,
            long accepted,
            long written,
            long rejectedFull,
            long rejectedPayload,
            long rejectedOversized,
            long rejectedClosed,
            long permanentFailed,
            long shutdownLost,
            long successfulBatches,
            long bufferFullErrorLogs,
            boolean retrying,
            boolean writerAlive,
            boolean capacityHintAvailable,
            boolean acceptingWrites) {
        public long lostOrRejected() {
            return rejectedFull + rejectedPayload + rejectedOversized
                    + permanentFailed + shutdownLost;
        }

        public boolean saturated() {
            return acceptingWrites && !capacityHintAvailable;
        }

        public boolean impaired() {
            return !acceptingWrites || !writerAlive || retrying || saturated();
        }
    }

    /**
     * One admitted action: 1 to MAX_ROWS_PER_ACTION encoded rows in capture order, and their summed retained
     * bytes. One queue element; the writer never splits it across transactions (writer-01 2.5).
     */
    record PendingGroup(List<SchemaV1Row> rows, long retainedBytes, boolean admittedPast) {
        PendingGroup(List<SchemaV1Row> rows, long retainedBytes) {
            this(rows, retainedBytes, false);
        }

        PendingGroup {
            rows = List.copyOf(rows);
            if (rows.isEmpty() || rows.size() > MAX_ROWS_PER_ACTION) {
                throw new IllegalArgumentException("An EventTrail write group holds 1 to "
                        + MAX_ROWS_PER_ACTION + " rows, not " + rows.size());
            }
        }

        int size() {
            return rows.size();
        }
    }

    /** SAVED_AT_STOP: S2 (writer-spool design-02 6.8), moved into the spool's staging at the shutdown deadline. */
    private enum WriteResolution { WRITTEN, PERMANENT_FAILED, SHUTDOWN_LOST, SAVED_AT_STOP }

    /** A spool unit's compare-and-set m -> n on its position row (design-01 7.2). */
    private record SpoolAdvance(String component, long from, long to) {}

    /** The advance updated no row: someone else moved the position, or an own late commit landed (M-3). */
    private static final class SpoolZeroRowAdvance extends SQLException {
        private SpoolZeroRowAdvance() { super("EventTrail spool position advance updated no row", "ETSP6"); }
    }

    /** The database refused the M-2 session limit (a proxy, a managed service): the spool runs without it. */
    private static final class SpoolSessionLimitRefused extends SQLException {
        private SpoolSessionLimitRefused(SQLException cause) {
            super("EventTrail spool session limit refused", "ETSP7", cause);
        }
    }

    /**
     * R-10 (r9): the connection failed while the M-2 session limit was being set (an outage, not a refusal): the
     * connection is evicted and the unit is retried with the limit, as N-2 does for the lock bound.
     */
    private static final class SpoolSessionLimitConnectionFailed extends SQLException {
        private SpoolSessionLimitConnectionFailed(SQLException cause) {
            super("EventTrail spool session limit: connection failed", cause.getSQLState(), cause);
        }
    }

    /**
     * MAJOR-4 (r2): the database refused the advance's 2 s lock bound: the spool runs without it, and a query
     * timeout on the advance is then the lock wait (replay paused).
     */
    private static final class SpoolAdvanceLockBoundRefused extends SQLException {
        private SpoolAdvanceLockBoundRefused(SQLException cause) {
            super("EventTrail spool advance lock bound refused", "ETSP7", cause);
        }
    }

    /**
     * N-2 (r3): the connection failed while the advance's lock bound was being set (an outage, not a refusal): the
     * connection is evicted and the unit is retried with the bound.
     */
    private static final class SpoolAdvanceLockBoundConnectionFailed extends SQLException {
        private SpoolAdvanceLockBoundConnectionFailed(SQLException cause) {
            super("EventTrail spool advance lock bound: connection failed", cause.getSQLState(), cause);
        }
    }

    /**
     * N-2 (r3): true when a failure while a spool session setting was being applied came from the connection, not
     * from the database refusing the setting: a connection outage state (SQLState class 08, or PostgreSQL's
     * 57P01-57P03) or a socket failure anywhere in the chain, an {@link SQLRecoverableException} or
     * {@link SQLTransientConnectionException} anywhere in the chain, or a connection that no longer reports itself
     * valid within {@value #SPOOL_BOUND_VALID_CHECK_SECONDS} s (or whose check fails).
     */
    static boolean isSpoolBoundConnectionFailure(Connection connection, SQLException failure) {
        if (EventTrailDiagnostics.isConnectionOutage(failure)) return true;
        for (Throwable current : EventTrailDiagnostics.failureChain(failure)) {
            if (current instanceof SQLRecoverableException || current instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        if (connection == null) return true;
        try {
            return !connection.isValid(SPOOL_BOUND_VALID_CHECK_SECONDS);
        } catch (SQLException | RuntimeException checkFailure) {
            return true;
        }
    }

    record ConnectionAbortResult(
            boolean abortReturned,
            boolean closedByAbort,
            boolean closeInvoked,
            boolean closed,
            Throwable failure) {}

    private enum WritePressureReason {
        COUNT_CAPACITY("entry-count capacity saturated"),
        PAYLOAD_CAPACITY("retained-payload capacity saturated"),
        OVERSIZED_ENTRY("single entry exceeded the retained-size limit"),
        OVERSIZED_ACTION("one action exceeded the " + MAX_ROWS_PER_ACTION + "-row limit"),
        OVERSIZED_ACTION_PAYLOAD("one action exceeded the whole retained-payload limit");

        private final String description;

        WritePressureReason(String description) {
            this.description = description;
        }

        private String description() {
            return description;
        }
    }

    private static final class BatchWriteFailure extends Exception {
        private final boolean retrySafe;
        /** fix-03 A: the rollback failure that shows the connection was lost; null otherwise. */
        private final SQLException connectionLostBy;
        /** af-1 F7: the commit had been sent, so its outcome is unknown (the drop line's reason). */
        private final boolean commitSent;

        /** writer-spool m-4 (design-02 6.7): the failure came from the position statement, not from a row. */
        private final boolean inAdvance;

        private BatchWriteFailure(SQLException cause, boolean retrySafe, SQLException connectionLostBy,
                                  boolean commitSent, boolean inAdvance) {
            super(cause);
            this.retrySafe = retrySafe;
            this.connectionLostBy = connectionLostBy;
            this.commitSent = commitSent;
            this.inAdvance = inAdvance;
        }

        private boolean inAdvance() {
            return inAdvance;
        }

        private boolean retrySafe() {
            return retrySafe;
        }

        private boolean commitSent() {
            return commitSent;
        }

        /** Non-null only for a retry-safe attempt (no commit sent) whose rollback failed with a connection outage. */
        private SQLException connectionLostBy() {
            return connectionLostBy;
        }

        @Override
        public synchronized SQLException getCause() {
            return (SQLException)super.getCause();
        }
    }

    /**
     * {@code textCheck}: T31-R4 text filters decided in Java after SQL; {@code nameCheck}: the {@code name:}
     * filter, always decided in Java after its SQL prefilter. Both null when SQL decides every row.
     */
    record WhereParts(String sql, List<Object> params, SearchFold.TextCheck textCheck, LoggedNameFilter nameCheck) {
        WhereParts(String sql, List<Object> params, SearchFold.TextCheck textCheck) {
            this(sql, params, textCheck, null);
        }
    }
    record HighWaterParts(String sql, List<Object> params) {}
    record DeleteParts(String sql, List<Object> params) {}
}
