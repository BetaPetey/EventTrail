package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.SchemaV1Row;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The writer's disk spool (writer-spool design-02 4, 5, 7, 11; the Advisor's S-3, S-4, S-5, S-8, S-9, S-10, S-11).
 * Admission only moves the {@code SchemaV1Row} lists it already built into a bounded staging queue (A1); the spool
 * thread {@code EventTrail-Spool} gives sequence numbers inside its insert transaction and commits in batches. No
 * SQLite call ever runs on an admitting thread or under {@code writeLifecycleLock}: every call on the one spool
 * connection goes through {@link #withConnection}, which asserts that rule (S-4, SpoolThreadRuleTest).
 *
 * <p>Lock order: {@code writeLifecycleLock -> stagingLock} and {@code spoolLock -> stagingLock}; the storage's
 * {@code writeLifecycleLock} and {@code spoolLock} are never held together. {@code stagingLock} is a leaf.</p>
 */
final class DiskSpool {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final String DIRECTORY = "spool";
    static final int STAGING_MAX_ACTIONS = 4096;
    static final long STAGING_MAX_RETAINED_BYTES = 32L * 1024L * 1024L;
    static final long FLOOR_BYTES = 512L * 1024L * 1024L;
    static final long FLOOR_PROBE_MILLIS = 5_000L;
    static final long STORE_FAILURE_PAUSE_MILLIS = 60_000L;
    static final long OPEN_WAIT_MILLIS = 5_000L;
    static final long CLOSE_WAIT_MILLIS = 5_000L;
    static final long RETIRE_SEQUENCE = 1L << 30;
    static final long MAX_SEQUENCE = Integer.MAX_VALUE;
    static final int BACKEND_SQLITE = 1;
    static final int BACKEND_MYSQL = 2;
    static final int BACKEND_POSTGRESQL = 3;
    private static final long MIB = 1024L * 1024L;

    /** Everything a test may replace; production uses {@link #production}. */
    record Settings(boolean enabled, long capBytes, int retentionDays, boolean deleteKeptAfterRetention,
                    SpoolFile.ConnectionFactory factory, LongSupplier clock, ToLongFunction<Path> usableSpace,
                    OpenHook openHook) {
        Settings {
            Objects.requireNonNull(factory, "factory");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(usableSpace, "usableSpace");
            if (openHook == null) openHook = () -> {};
        }

        static Settings production(boolean enabled, int maxMegabytes, int retentionDays) {
            return new Settings(enabled, Math.max(1L, maxMegabytes) * MIB, retentionDays,
                    SpoolFile.DELETE_KEPT_AFTER_RETENTION, SpoolFile.ConnectionFactory.bundled(),
                    System::currentTimeMillis, DiskSpool::usableSpace, null);
        }

        Settings withClock(LongSupplier c) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, c, usableSpace, openHook); }
        Settings withUsableSpace(ToLongFunction<Path> u) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, u, openHook); }
        Settings withOpenHook(OpenHook h) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, usableSpace, h); }
        Settings withFactory(SpoolFile.ConnectionFactory f) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, f, clock, usableSpace, openHook); }
        Settings withDeleteKept(boolean d) { return new Settings(enabled, capBytes, retentionDays, d, factory, clock, usableSpace, openHook); }
    }

    /** Test seam: runs on the spool thread before the open (an injected slow open, design-02 13.1). */
    @FunctionalInterface
    interface OpenHook {
        void beforeOpen() throws Exception;
    }

    /** One staged action (design-02 4.3): the rows admission built, their retained bytes, the capture time. */
    record Staged(List<SchemaV1Row> rows, long retainedBytes, long minRowTs, long capturedMillis) {}

    /** What the open found (design-02 5.1 steps 1 to 4), handed to init. */
    record OpenOutcome(String offReason, boolean disabled, long disabledWaiting, SpoolFile.Meta meta, boolean fresh,
                       boolean notClosedCleanly, boolean walMissing, List<SpoolFile.MovedSet> movedNow,
                       List<SpoolFile.KeptSet> aside, List<SpoolFile.KeptSet> held, boolean rebound,
                       boolean closedUnmarked) {
        OpenOutcome(String offReason, boolean disabled, long disabledWaiting, SpoolFile.Meta meta, boolean fresh,
                    boolean notClosedCleanly, boolean walMissing, List<SpoolFile.MovedSet> movedNow,
                    List<SpoolFile.KeptSet> aside, List<SpoolFile.KeptSet> held, boolean rebound) {
            this(offReason, disabled, disabledWaiting, meta, fresh, notClosedCleanly, walMissing, movedNow, aside, held,
                    rebound, false);
        }

        boolean usable() { return offReason == null && !disabled && meta != null; }
    }

    /** MINOR-6 (r2): false when a disabled spool's waiting count is only a lower bound (not closed cleanly). */
    volatile boolean disabledWaitingExact = true;

    enum State { STARTING, READY, OFF, DISABLED, CLOSED }

    private final Path spoolDirectory;
    private final SpoolFile.Binding binding;
    private final Settings settings;
    private final Object forbiddenMonitor;
    private final String backendName;

    private final ReentrantLock spoolLock = new ReentrantLock();
    private final Object stagingLock = new Object();
    private final ArrayDeque<Staged> staging = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> unreadableMarks = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<OpenOutcome> openResult = new CompletableFuture<>();
    private final AtomicBoolean openAbandoned = new AtomicBoolean();

    private volatile Thread spoolThread;
    private volatile Thread writerThread;
    private SpoolFile file;  // spool thread (and writer reads) under spoolLock
    private volatile State state = State.STARTING;
    private volatile String offReason;
    private volatile String damagedReason;
    private final Object damageLock = new Object();
    private volatile String replayStoppedReason;
    private volatile boolean replayPaused;
    private volatile boolean positionSettled;
    private volatile long storePauseUntilMillis;
    private volatile String storeFailureReason;
    private volatile boolean belowFloor;
    private volatile long nextFloorProbeMillis;
    private volatile boolean closeRequested;
    /** R-3 (r9): the damage reason when a VirtualMachineError ended the spool thread (the file itself is not damaged). */
    static final String SPOOL_THREAD_DIED = "spool-thread-died";
    private volatile boolean spoolThreadDied;
    /** R-3 (r9) test seam: runs on the spool thread before each entry is encoded (null in production). */
    volatile Runnable encodeSeamForTest;
    private volatile boolean abandoned;
    private volatile boolean writerRunningAtClose;
    private static final long EMPTY_LINE_INTERVAL_MILLIS = 10L * 60L * 1000L;
    /** Spool thread only (NIT-3). */
    private long lastEmptyLineMillis = Long.MIN_VALUE;
    private volatile boolean shrinkWarned;
    private volatile boolean shrinkDue;
    private volatile long requestedDeleteThrough = -1L;
    private volatile long oldestAppendedMillis = -1L;
    private volatile boolean firstUseWarned;
    private volatile boolean fullWarned;
    private volatile boolean storeErrorWritten;
    private volatile boolean unreadableErrorWritten;

    // Lock-free mirrors (design-02 4.2).
    private final AtomicLong stagedActions = new AtomicLong();
    private final AtomicLong stagedRows = new AtomicLong();
    private final AtomicLong stagedRetainedBytes = new AtomicLong();
    private final AtomicLong waitingFrames = new AtomicLong();
    private final AtomicLong waitingRows = new AtomicLong();
    private final AtomicLong waitingBytes = new AtomicLong();
    private final AtomicLong unreadableFrames = new AtomicLong();
    private final AtomicLong unreadableRows = new AtomicLong();
    private final AtomicLong unreadableBytes = new AtomicLong();
    private final AtomicLong publishedHighWater = new AtomicLong();

    // Counters (design-02 11; rows unless the name says actions).
    final AtomicLong spooledActions = new AtomicLong();
    final AtomicLong spooledRows = new AtomicLong();
    final AtomicLong committedThisRunRows = new AtomicLong();
    final AtomicLong writtenFromSpoolRows = new AtomicLong();
    final AtomicLong carriedInActions = new AtomicLong();
    final AtomicLong carriedInWrittenActions = new AtomicLong();
    final AtomicLong carriedInWrittenRows = new AtomicLong();
    final AtomicLong carriedInLostActions = new AtomicLong();
    final AtomicLong refusedFullRows = new AtomicLong();
    final AtomicLong refusedUnwritableRows = new AtomicLong();
    final AtomicLong refusedOffRows = new AtomicLong();
    final AtomicLong admittedPastActions = new AtomicLong();
    final AtomicLong couldNotStoreActions = new AtomicLong();
    final AtomicLong couldNotStoreRows = new AtomicLong();
    final AtomicLong unreadableActions = new AtomicLong();
    final AtomicLong unreadableRowsAsStored = new AtomicLong();
    final AtomicLong unreadableThisRunRows = new AtomicLong();
    final AtomicLong expiredActions = new AtomicLong();
    final AtomicLong expiredRows = new AtomicLong();
    final AtomicLong expiredThisRunRows = new AtomicLong();
    final AtomicLong poisonThisRunRows = new AtomicLong();
    final AtomicLong skippedPastPositionActions = new AtomicLong();
    final AtomicLong positionBehindLostActions = new AtomicLong();
    final AtomicLong savedAtStopActions = new AtomicLong();
    final AtomicLong savedAtStopRows = new AtomicLong();
    final AtomicLong ruleViolations = new AtomicLong();
    final AtomicLong connectionCallsBySpoolThread = new AtomicLong();
    final AtomicLong connectionCallsByWriterThread = new AtomicLong();
    volatile long carriedInThrough;
    volatile long asideSets;
    volatile long asideBytes;
    volatile long asideActions;
    volatile long asideUnknownSets;
    volatile long asideOldSets;
    private volatile String liveSpoolIdHex = "";

    /** The live spool id, for the writer's position component (set at open, rebind, hold and restore). */
    String metaIdHexForWriter() { return liveSpoolIdHex; }
    volatile long heldSets;
    volatile long heldActions;
    /** spf1 MINOR-2: held sets with unreadable meta (no readable set.properties); counted apart, as aside's. */
    volatile long heldUnknownSets;
    /** spf1 r2 MINOR-1: held sets with readable meta and 0 waiting actions (such as the spool a restore replaced). */
    volatile long heldEmptySets;
    /** R-9 (r9): folders of spool/aside and spool/held with no spool.db; not sets. spf1 r3: plain folders only. */
    volatile long foldersWithoutDatabase;
    /** spf1 r3: the scans of spool/aside and spool/held: links and unreadable entries, left in place, warned at start. */
    volatile List<SpoolFile.KeptFolderScan> keptFolderScans = List.of();
    volatile long abandonedStagedActions = -1L;
    volatile long abandonedStagedRows = -1L;

    DiskSpool(Path eventTrailDirectory, SpoolFile.Binding binding, Settings settings, Object forbiddenMonitor,
              String backendName) {
        this.spoolDirectory = eventTrailDirectory.toAbsolutePath().normalize().resolve(DIRECTORY);
        this.binding = Objects.requireNonNull(binding, "binding");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.forbiddenMonitor = forbiddenMonitor;
        this.backendName = backendName == null ? "storage" : backendName;
    }

    Path directory() { return spoolDirectory; }
    Settings settings() { return settings; }
    SpoolFile.Binding binding() { return binding; }
    State state() { return state; }
    String offReason() { return offReason; }
    String damagedReason() { return damagedReason; }
    String replayStoppedReason() { return replayStoppedReason; }
    boolean replayPaused() { return replayPaused; }
    boolean positionSettled() { return positionSettled; }
    long publishedHighWater() { return publishedHighWater.get(); }
    long waitingFrames() { return waitingFrames.get(); }
    long stagedActions() { return stagedActions.get(); }
    boolean abandoned() { return abandoned; }
    /** R-3 (r9): a VirtualMachineError ended the spool thread; staged actions were never stored. */
    boolean spoolThreadDied() { return spoolThreadDied; }
    /** R-4 (r9): the file failed during the run and is set aside at the next start (a dead thread is not that). */
    boolean fileDamaged() { return damagedReason != null && !SPOOL_THREAD_DIED.equals(damagedReason); }

    // ---- start (design-02 5.1) ------------------------------------------------------------------------------

    /** Starts the spool thread; {@link #awaitOpen} gives its open result. Never throws (S-8). */
    void start() {
        Thread thread = new Thread(this::runSpoolThread, "EventTrail-Spool");
        thread.setDaemon(true);
        spoolThread = thread;
        thread.start();
    }

    /** design-02 5.1 step 2: init waits at most {@code millis}; on timeout the spool is off, it closes itself later. */
    OpenOutcome awaitOpen(long millis) {
        try {
            return openResult.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            openAbandoned.set(true);
            // A result that raced the timeout is closed by the spool thread itself (it sees the flag).
            turnOff("open-timeout", null);
            return offOutcome("open-timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            openAbandoned.set(true);
            turnOff("open-timeout", null);
            return offOutcome("open-timeout");
        } catch (ExecutionException failure) {
            turnOff("unexpected-failure", failure.getCause());
            return offOutcome("unexpected-failure");
        }
    }

    private static OpenOutcome offOutcome(String reason) {
        return new OpenOutcome(reason, false, 0, null, false, false, false, List.of(), List.of(), List.of(), false);
    }

    void bindWriterThread(Thread writer) {
        this.writerThread = writer;
    }

    private void runSpoolThread() {
        OpenOutcome outcome;
        try {
            settings.openHook().beforeOpen();
            outcome = openOnSpoolThread();
        } catch (VirtualMachineError fatal) {
            openResult.completeExceptionally(fatal);
            throw fatal;
        } catch (Throwable failure) {
            // S-8: any Throwable but VirtualMachineError, LinkageError included, turns the spool off.
            String reason = failure instanceof LinkageError ? "native-unavailable" : "unexpected-failure";
            closeFileQuietly(false);
            turnOff(reason, failure);
            openResult.complete(offOutcome(reason));
            return;
        }
        if (!openResult.complete(outcome) || openAbandoned.get()) {
            // init gave up waiting (design-02 5.1 step 2): this thread closes and releases, never init.
            closeFileQuietly(false);
            if (state != State.OFF) turnOff("open-timeout", null);
            return;
        }
        if (!outcome.usable()) return;
        try {
            loop();
        } catch (VirtualMachineError fatal) {
            // R-3 (r9): the thread is gone. Mark the spool dead first, without I/O: refusal() answers unwritable,
            // stageAtStop refuses, and close() and the summary report the staged actions as not stored. Then, best
            // effort, close the file so the lease is not held to JVM exit: closeFileQuietly(false) on this undamaged
            // file closes it without the clean mark (clean = 2), so the next start knows the close was on purpose.
            spoolThreadDied = true;
            claimDamage(SPOOL_THREAD_DIED);
            try {
                closeFileQuietly(false);
            } catch (Throwable closeFailure) {
                // The thread is ending with the fatal error; the lease then stays to JVM exit.
            }
            throw fatal;
        } catch (Throwable unexpected) {
            LOGGER.error("EventTrail disk spool thread failed ({}); the spool takes no new actions for this run.",
                    EventTrailDiagnostics.safeFailureSummary(unexpected));
            damage("unexpected-failure", null);
            closeFileQuietly(false);
        }
    }

    private OpenOutcome openOnSpoolThread() throws Exception {
        if (!settings.enabled()) return openDisabled();
        try {
            ensureEventTrailDirectory();
        } catch (IOException failure) {
            turnOff("access-denied", failure);
            return offOutcome("access-denied");
        }
        SpoolFile.Opened opened;
        try {
            opened = SpoolFile.open(spoolDirectory, binding, settings.capBytes(), settings.factory());
        } catch (SpoolFile.SpoolOff off) {
            turnOff(off.reason, off.getCause());
            return offOutcome(off.reason);
        }
        List<SpoolFile.MovedSet> moved = new ArrayList<>(opened.movedNow());
        SpoolFile current = opened.file();
        boolean rebound = false;
        SpoolFile.Meta meta = current.meta();
        if (!meta.binding().equals(binding)) {
            if (meta.waitingFrames() == 0) {
                try {
                    current.rebind(binding, false);
                } catch (SQLException | RuntimeException failure) {
                    // R-6 (r9): a failed rebind (SQLITE_FULL, IOERR) closes the file and releases the lease, without
                    // the clean mark, so a world reload in the same JVM can open it again.
                    String reason = failure instanceof SQLException sql ? SpoolFile.offReasonFor(sql)
                            : "unexpected-failure";
                    current.closeAndRelease(false);
                    turnOff(reason, failure);
                    return offOutcome(reason);
                }
                rebound = true;
                LOGGER.info("EventTrail disk spool was bound to another backend or server id with nothing waiting; "
                        + "it now uses the current one.");
            } else {
                // design-02 5.1 step 4, m-11: held, and a fresh spool for the current identity.
                String folder = SpoolFile.setFolderName(settings.clock().getAsLong(), meta.spoolIdHex());
                try {
                    current.moveFamily(SpoolFile.HELD, folder, SpoolFile.setProperties(meta, "binding-changed", null));
                } catch (SpoolFile.SpoolOff off) {
                    // R-6 (r9): the held move's own reason code, as the fresh open below keeps its own.
                    turnOff(off.reason, off.getCause());
                    return offOutcome(off.reason);
                }
                moved.add(new SpoolFile.MovedSet(SpoolFile.HELD, folder, "binding-changed", meta));
                try {
                    SpoolFile.Opened fresh = SpoolFile.open(spoolDirectory, binding, settings.capBytes(),
                            settings.factory());
                    moved.addAll(fresh.movedNow());
                    current = fresh.file();
                } catch (SpoolFile.SpoolOff off) {
                    turnOff(off.reason, off.getCause());
                    return offOutcome(off.reason);
                }
            }
        }
        install(current);
        return new OpenOutcome(null, false, 0, current.meta(), opened.fresh(), opened.notClosedCleanly(),
                opened.walMissing(), List.copyOf(moved), SpoolFile.listSets(spoolDirectory, SpoolFile.ASIDE),
                SpoolFile.listSets(spoolDirectory, SpoolFile.HELD), rebound, opened.closedUnmarked());
    }

    /** design-02 5.1 step 1 (n-9): open, read meta, close; nothing replayed, moved or deleted. */
    private OpenOutcome openDisabled() {
        state = State.DISABLED;
        Path database = spoolDirectory.resolve(SpoolFile.FILE_NAME);
        if (!SpoolFile.existsNoFollow(database)) {
            return new OpenOutcome(null, true, 0, null, false, false, false, List.of(), List.of(), List.of(), false);
        }
        try {
            // MINOR-6 (r2): read-only; a disabled spool never sets the file aside, marks it clean or checkpoints it.
            SpoolFile.Peek peek = SpoolFile.peek(spoolDirectory, settings.factory());
            disabledWaitingExact = peek.exact();
            return new OpenOutcome(null, true, peek.waitingFrames(), null, false, false, false, List.of(), List.of(),
                    List.of(), false);
        } catch (SpoolFile.SpoolOff off) {
            LOGGER.warn("EventTrail disk spool is disabled in the configuration; spool/spool.db could not be read "
                    + "({}). It is left untouched.", off.reason);
            return new OpenOutcome(null, true, -1, null, false, false, false, List.of(), List.of(), List.of(), false);
        }
    }

    /** {@code <world>/eventtrail/} if missing, then {@code spool/} itself: a real folder, rwx------ on POSIX (2.3). */
    private void ensureEventTrailDirectory() throws IOException {
        Path parent = spoolDirectory.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        SpoolFile.ensurePrivateDirectory(spoolDirectory);
    }

    private void install(SpoolFile opened) {
        file = opened;
        SpoolFile.Meta meta = opened.meta();
        waitingFrames.set(meta.waitingFrames());
        waitingRows.set(meta.waitingRows());
        waitingBytes.set(meta.waitingBytes());
        unreadableFrames.set(meta.unreadableFrames());
        unreadableRows.set(meta.unreadableRows());
        unreadableBytes.set(meta.unreadableBytes());
        publishedHighWater.set(meta.highWater());
        if (meta.waitingFrames() > 0) oldestAppendedMillis = settings.clock().getAsLong();
        liveSpoolIdHex = meta.spoolIdHex();
    }

    /** Runs {@code work} on the spool thread and waits at most {@code millis} for it (init's settle steps). */
    <T> T call(Callable<T> work, long millis) throws Exception {
        if (Thread.currentThread() == spoolThread) return work.call();
        CompletableFuture<T> result = new CompletableFuture<>();
        tasks.add(() -> {
            try { result.complete(work.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        wake();
        try {
            return result.get(millis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    SpoolFile.Meta metaOnSpoolThread() {
        return withConnection(() -> file.meta());
    }

    /** R-4 (r9), tests only, on the spool thread from {@link #call}: {@link SpoolFile#executeUpdateForTest}. */
    int executeUpdateOnSpoolThreadForTest(String sql) throws Exception {
        return withConnectionChecked(() -> file.executeUpdateForTest(sql));
    }

    /** Called on the spool thread from {@link #call}: setting tasks of the position settle. */
    void setPositionCreatedOnSpoolThread() throws Exception {
        withConnectionChecked(() -> { file.setPositionCreated(); return null; });
    }

    void raiseHighWaterOnSpoolThread(long position) throws Exception {
        withConnectionChecked(() -> { file.raiseHighWater(position); return null; });
        publishedHighWater.set(Math.max(publishedHighWater.get(), position));
    }

    void rebindOnSpoolThread(boolean resetSequence) throws Exception {
        withConnectionChecked(() -> { file.rebind(binding, resetSequence); return null; });
        if (resetSequence) publishedHighWater.set(0);
        liveSpoolIdHex = file.meta().spoolIdHex();
    }

    void deleteThroughOnSpoolThread(long x) throws Exception {
        long deleted = withConnectionChecked(() -> file.deleteThrough(x));
        refreshMirrors();
        if (deleted > 0) refreshOldest();
    }

    /** R-8 (r9): the meta of the file now open, and whether a held set was restored into it. */
    record HoldOutcome(SpoolFile.Meta meta, boolean restored) {}

    /** m-5 (6.2) or a held set's restore (7.4): moves the live family to held/ and opens the next file. */
    HoldOutcome holdLiveAndOpenOnSpoolThread(String reason, String heldFolderToRestore) throws Exception {
        spoolLock.lock();
        try {
            SpoolFile.Meta meta = file.meta();
            String folder = SpoolFile.setFolderName(settings.clock().getAsLong(), meta.spoolIdHex());
            file.moveFamily(SpoolFile.HELD, folder, SpoolFile.setProperties(meta, reason, null));
            file = null;
            boolean restored = false;
            if (heldFolderToRestore != null) {
                try {
                    SpoolFile.restoreHeld(spoolDirectory, spoolDirectory.resolve(SpoolFile.HELD).resolve(heldFolderToRestore));
                    restored = true;
                } catch (IOException | RuntimeException restoreFailure) {
                    LOGGER.warn("EventTrail disk spool could not restore a held set ({}); a new spool was started.",
                            EventTrailDiagnostics.safeFailureSummary(restoreFailure));
                }
            }
            SpoolFile.Opened next = SpoolFile.open(spoolDirectory, binding, settings.capBytes(), settings.factory());
            install(next.file());
            return new HoldOutcome(next.file().meta(), restored);
        } catch (SpoolFile.SpoolOff off) {
            turnOff(off.reason, off.getCause());
            throw off;
        } finally {
            spoolLock.unlock();
        }
    }

    /**
     * spf1 r2 MINOR-2, r3: a folder without spool.db that the start's scan found plain but that is not plain when the
     * pass comes to it (it changed in between). The start WARNs about the links it found (r3 ruling 2).
     */
    static final String NOT_PLAIN_FOLDER_WARN = "EventTrail disk spool left a folder without spool.db in spool/{} in "
            + "place: it is a link, junction, other reparse point or file, which is never entered or deleted.";

    /** spf1 r3 ruling 3: as {@link #NOT_PLAIN_FOLDER_WARN}, for a folder whose attributes could not be read. */
    static final String UNREADABLE_FOLDER_WARN = "EventTrail disk spool left a folder without spool.db in spool/{} in "
            + "place: its attributes could not be read.";

    /**
     * spf1 r3 rulings 1-3, once per start: {@code spool/<kind>} that is not a plain folder is never listed for folders
     * without spool.db, and the retention cleanup deletes nothing in it ({@link SpoolFile#deleteKeptSet} refuses).
     * spf1 r4 (check-r3 (d), the Lead's option i): {@link SpoolFile#listSets} still lists a junction (a symbolic link
     * or a file reads as no folder there), so its sets are counted and a held set in it can be restored by
     * {@link SpoolFile#restoreHeld}, which moves that set's spool.db family out and removes its set.properties and,
     * when nothing else is left in it, its folder (SpoolFile#removeOwnSetFolder).
     */
    static final String KIND_NOT_PLAIN_WARN = "EventTrail disk spool: spool/{} is a link, junction, other reparse "
            + "point or file, not a plain folder. It is never searched for folders without spool.db, the retention "
            + "cleanup deletes nothing in it, and it is left in place. If it is a junction, the sets in it (folders "
            + "holding spool.db) are still counted, and a held set there for this server id and database can still be "
            + "restored: the restore moves that set's spool.db and its -wal, -shm or -journal out of it and removes "
            + "that set's set.properties and then its folder if nothing else is left in it.";

    /** spf1 r3 ruling 3, once per start: {@code spool/<kind>} whose attributes could not be read. */
    static final String KIND_UNREADABLE_WARN = "EventTrail disk spool: the attributes of spool/{} could not be read; "
            + "it is never entered to find folders without spool.db, nothing in it is deleted, and it is left in place.";

    /**
     * spf1 r3 ruling 2, once per start: entries of {@code spool/<kind>} that are links (symbolic or junction). The scan
     * never looks into them and the retention cleanup never deletes them ({@link SpoolFile#deleteKeptSet} refuses).
     * spf1 r4 MAJOR-1 (the Lead's option i): {@link SpoolFile#listSets} still takes a junction holding a regular
     * spool.db as a set (a symbolic link reads as no folder there), so it is counted, and in spool/held a matching
     * one is restored by {@link SpoolFile#restoreHeld}: its spool.db family is moved out, then its set.properties and
     * the junction itself are removed (SpoolFile#removeOwnSetFolder); the junction's target folder stays.
     */
    static final String ENTRY_LINK_WARN = "EventTrail disk spool: {} entr(ies) in spool/{} are a link, junction, other "
            + "reparse point or special file, not a plain folder. They are never searched as folders without spool.db, "
            + "and the retention cleanup never deletes them: it leaves them in place. A junction that holds spool.db "
            + "is still counted as a set, and in spool/held one for this server id and database can still be restored: "
            + "the restore moves that set's spool.db and its -wal, -shm or -journal out of it and removes that set's "
            + "set.properties and the junction.";

    /** spf1 r3 ruling 3, once per start: entries of {@code spool/<kind>} whose attributes could not be read. */
    static final String ENTRY_UNREADABLE_WARN = "EventTrail disk spool: the attributes of {} entr(ies) in spool/{} could "
            + "not be read; they are never entered or deleted, and are left in place.";

    /**
     * design-02 18 (L11, the Owner's answer: on as built): deletes kept sets and unreadable frames older than retention.
     * spf1 MINOR-1, ADDENDUM-01: a folder of spool/aside or spool/held without spool.db (an interrupted move or
     * restore; a lone -wal is a spool file) is deleted too once its newest file is older than the cutoff. Returns
     * {sets, frames, rows, folders without spool.db}.
     */
    long[] deleteKeptOnSpoolThread(long cutoffMillis) throws Exception {
        long sets = 0, folders = 0;
        for (String kind : new String[]{SpoolFile.ASIDE, SpoolFile.HELD}) {
            for (SpoolFile.KeptSet set : SpoolFile.listSets(spoolDirectory, kind)) {
                if (set.newestWriteMillis() >= cutoffMillis) continue;
                try {
                    SpoolFile.deleteKeptSet(set.folder());
                    sets++;
                } catch (IOException failure) {
                    LOGGER.warn("EventTrail disk spool could not delete a kept set ({}).",
                            EventTrailDiagnostics.safeFailureSummary(failure));
                }
            }
            // r2 MINOR-2, r3: the same scan the start counts. It tests spool/<kind> before listing it and never lists
            // one that is not plain; it lists only plain folders, never a link (each is tested before it is read).
            // Links and unreadable entries or spool/<kind> are WARNed once at the start, not here. A folder that is
            // no longer plain when the pass reaches it gets its own WARN and is left.
            for (Path folder : SpoolFile.foldersWithoutDatabase(spoolDirectory, kind)) {
                SpoolFile.EntryKind now = SpoolFile.entryKind(folder);
                if (now == SpoolFile.EntryKind.ABSENT) continue;
                if (now == SpoolFile.EntryKind.UNREADABLE) {
                    LOGGER.warn(UNREADABLE_FOLDER_WARN, kind);
                    continue;
                }
                if (now != SpoolFile.EntryKind.PLAIN_FOLDER) {
                    LOGGER.warn(NOT_PLAIN_FOLDER_WARN, kind);
                    continue;
                }
                if (SpoolFile.newestRegularFileMillis(folder) >= cutoffMillis) continue;
                try {
                    SpoolFile.deleteKeptSet(folder);
                    folders++;
                } catch (IOException failure) {
                    LOGGER.warn("EventTrail disk spool could not delete a kept folder without spool.db ({}).",
                            EventTrailDiagnostics.safeFailureSummary(failure));
                }
            }
        }
        long[] frames = withConnectionChecked(() -> file.deleteUnreadableAppendedBefore(cutoffMillis));
        refreshMirrors();
        return new long[]{sets, frames[0], frames[1], folders};
    }

    /** The position settle finished: replay and admission may start (design-02 5.1 step 5). */
    void markSettled(long carriedInThroughSeq) {
        carriedInThrough = carriedInThroughSeq;
        carriedInActions.set(waitingFrames.get());
        positionSettled = true;
        if (state == State.STARTING) state = State.READY;
        wake();
    }

    /** M-2 (6.4 step 2): the init check met a lock; replay paused until the writer's retry settles it. */
    void markReadyUnsettled() {
        if (state == State.STARTING) state = State.READY;
    }

    // ---- admission (design-02 4.3; no SQLite, no file I/O) ----------------------------------------------------

    /** The latch: waiting + staged > 0 (design-02 4.2, the Advisor's n-1: one definition). */
    boolean latched() {
        return waitingFrames.get() + stagedActions.get() > 0;
    }

    /** Per action: can the spool take one action of {@code retainedBytes}? Atomic mirrors only. */
    boolean canAccept(long retainedBytes) {
        return refusal(retainedBytes) == null;
    }

    /** The capacity hint's part (design-01 2.2 / L8): could the spool take a small action? */
    boolean canAcceptHint() {
        return canAccept(512L);
    }

    /** null when the spool can accept; else the breakdown of a refusal: "full", "unwritable" or "off". */
    String refusal(long retainedBytes) {
        if (state != State.READY || closeRequested) return "off";
        if (!positionSettled || replayStoppedReason != null || replayPaused) return "off";
        if (damagedReason != null) return "unwritable";
        if (settings.clock().getAsLong() < storePauseUntilMillis) return "unwritable";
        if (belowFloor) return "full";
        long actions = stagedActions.get();
        long staged = stagedRetainedBytes.get();
        if (actions + 1 > STAGING_MAX_ACTIONS || staged + retainedBytes > STAGING_MAX_RETAINED_BYTES) return "full";
        if (publishedHighWater.get() + actions + 1 > MAX_SEQUENCE) return "full";
        double projected = waitingBytes.get() + unreadableBytes.get() + 1.5d * (staged + (double) retainedBytes);
        if (projected > settings.capBytes()) return "full";
        return null;
    }

    /**
     * Stages one action behind the waiting ones; a pointer move under the leaf {@code stagingLock}. The caller
     * holds {@code writeLifecycleLock} and has just checked {@link #canAccept}. Returns false when the staging
     * bound no longer holds (never partly staged).
     */
    boolean stage(List<SchemaV1Row> rows, long retainedBytes, long capturedMillis) {
        Staged entry = new Staged(rows, retainedBytes, SpoolFrameCodec.minTimestamp(rows), capturedMillis);
        boolean firstUse;
        synchronized (stagingLock) {
            if (staging.size() + 1 > STAGING_MAX_ACTIONS
                    || stagedRetainedBytes.get() + retainedBytes > STAGING_MAX_RETAINED_BYTES) return false;
            firstUse = !latched();
            staging.addLast(entry);
            stagedActions.incrementAndGet();
            stagedRows.addAndGet(rows.size());
            stagedRetainedBytes.addAndGet(retainedBytes);
            stagingLock.notifyAll();
        }
        spooledActions.incrementAndGet();
        spooledRows.addAndGet(rows.size());
        if (firstUse && !firstUseWarned) {
            firstUseWarned = true;
            firstUseLinePending = true;
        }
        return true;
    }

    /** S2 (6.8): stages a group saved at stop; waits for staging room until {@code deadlineNanos}. */
    boolean stageAtStop(List<SchemaV1Row> rows, long retainedBytes, long deadlineNanos) {
        Staged entry = new Staged(rows, retainedBytes, SpoolFrameCodec.minTimestamp(rows), settings.clock().getAsLong());
        synchronized (stagingLock) {
            while (true) {
                if (state != State.READY || damagedReason != null || abandoned || closeRequested) return false;
                if (staging.size() + 1 <= STAGING_MAX_ACTIONS
                        && stagedRetainedBytes.get() + retainedBytes <= STAGING_MAX_RETAINED_BYTES) break;
                long waitNanos = deadlineNanos - System.nanoTime();
                if (waitNanos <= 0) return false;
                try {
                    stagingLock.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(waitNanos)));
                } catch (InterruptedException interrupted) {
                    // The close path interrupts the writer (design-01 8.2 S2): the save keeps its deadline.
                    Thread.interrupted();
                }
            }
            staging.addLast(entry);
            stagedActions.incrementAndGet();
            stagedRows.addAndGet(rows.size());
            stagedRetainedBytes.addAndGet(retainedBytes);
            stagingLock.notifyAll();
        }
        savedAtStopActions.incrementAndGet();
        savedAtStopRows.addAndGet(rows.size());
        return true;
    }

    private volatile boolean firstUseLinePending;

    private volatile boolean fullLinePending;

    /** Under the admission lock: notes (no logging) that the spool refused this action for room. */
    void markFullIfFull(long retainedBytes) {
        if (!fullWarned && "full".equals(refusal(retainedBytes))) {
            fullWarned = true;
            fullLinePending = true;
        }
    }

    /** Writes the deferred first-use and full WARNs outside every lock (called after admission). */
    void flushAdmissionLines() {
        if (firstUseLinePending) {
            firstUseLinePending = false;
            LOGGER.warn("The database writer is full; new actions now wait on disk in the EventTrail spool and will be "
                    + "written when the database catches up.");
        }
        if (fullLinePending) {
            fullLinePending = false;
            LOGGER.warn("EventTrail's disk spool is full ({} of {} MiB); new actions go to memory while it has room and "
                            + "are refused and counted after that. See /eventtrail status.",
                    (waitingBytes.get() + unreadableBytes.get()) / MIB, settings.capBytes() / MIB);
        }
    }

    // ---- the spool thread (design-02 4.4) -------------------------------------------------------------------

    private void wake() {
        synchronized (stagingLock) { stagingLock.notifyAll(); }
    }

    private void loop() {
        while (true) {
            synchronized (stagingLock) {
                while (staging.isEmpty() && tasks.isEmpty() && unreadableMarks.isEmpty() && !closeRequested
                        && !deleteDue() && !shrinkDue) {
                    try {
                        stagingLock.wait(belowFloor ? FLOOR_PROBE_MILLIS : 1_000L);
                    } catch (InterruptedException interrupted) {
                        Thread.interrupted();
                    }
                    if (belowFloor) break;
                }
            }
            runTasks();
            if (belowFloor && settings.clock().getAsLong() >= nextFloorProbeMillis) probeFloor();
            if (!staging.isEmpty()) commitBatch();
            applyMarks();
            applyDelete();
            if (shrinkDue) shrinkNow();
            if (closeRequested) {
                closeOnSpoolThread();
                return;
            }
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) task.run();
    }

    private boolean deleteDue() {
        SpoolFile f = file;
        return f != null && damagedReason == null && requestedDeleteThrough > f.meta().replayedThrough();
    }

    private void probeFloor() {
        long usable;
        try {
            usable = settings.usableSpace().applyAsLong(spoolDirectory);
        } catch (RuntimeException unavailable) {
            usable = Long.MAX_VALUE;
        }
        belowFloor = usable < FLOOR_BYTES;
        nextFloorProbeMillis = settings.clock().getAsLong() + FLOOR_PROBE_MILLIS;
    }

    /** design-02 4.4 steps 2 to 6. */
    private void commitBatch() {
        List<Staged> batch;
        synchronized (stagingLock) {
            batch = new ArrayList<>(staging);
        }
        if (batch.isEmpty()) return;
        if (damagedReason != null) {
            dropStaged(batch, "damaged");
            return;
        }
        try {
            withConnectionChecked(() -> { file.verifyCurrent(); return null; });
        } catch (Exception identity) {
            damage("identity-changed", batch);
            return;
        }
        probeFloor();
        // m-3: one entry that cannot be encoded is counted alone as "accepted, could not be stored"; the rest commit.
        List<SpoolFile.EncodedFrame> encoded = new ArrayList<>(batch.size());
        List<Staged> stored = new ArrayList<>(batch.size());
        long codecFailedRows = 0;
        int codecFailed = 0;
        for (Staged entry : batch) {
            Runnable seam = encodeSeamForTest;
            if (seam != null) seam.run();
            try {
                encoded.add(new SpoolFile.EncodedFrame(entry.rows().size(), entry.minRowTs(),
                        SpoolFrameCodec.encode(entry.rows())));
                stored.add(entry);
            } catch (RuntimeException codecFault) {
                codecFailed++;
                codecFailedRows += entry.rows().size();
            }
        }
        long appendedAt = settings.clock().getAsLong();
        long newHigh;
        try {
            newHigh = withConnectionChecked(() -> file.insertBatch(encoded, appendedAt));
        } catch (SpoolFile.InsertFailure failure) {
            boolean full = SpoolFile.primaryCode(failure.failure) == SpoolFile.SQLITE_FULL;
            String reason = full ? "disk-full" : failure.readBackHighWater < 0 ? "read-back-failed" : "commit-failed";
            removeStaged(batch);
            countCouldNotStore(batch.size(), rowsOf(batch), reason,
                    full && failure.readBackHighWater >= 0 ? AFTER_PAUSE : AFTER_RUN);
            if (full && failure.readBackHighWater >= 0) {
                storePauseUntilMillis = settings.clock().getAsLong() + STORE_FAILURE_PAUSE_MILLIS;
                storeFailureReason = reason;
            } else {
                // m-9: nothing is retried until it "works"; the file is damaged for the run (7.3).
                damage(reason, null);
            }
            return;
        } catch (Exception unexpected) {
            removeStaged(batch);
            countCouldNotStore(batch.size(), rowsOf(batch), "commit-failed");
            damage("commit-failed", null);
            return;
        }
        boolean wasEmpty = waitingFrames.get() == 0;
        removeStaged(batch);
        if (codecFailed > 0) countCouldNotStore(codecFailed, codecFailedRows, "encode-failed", AFTER_CONTINUES);
        long rows = rowsOf(stored);
        committedThisRunRows.addAndGet(rows);
        refreshMirrors();
        publishedHighWater.set(newHigh);
        if (wasEmpty && !stored.isEmpty()) oldestAppendedMillis = appendedAt;
    }

    private void removeStaged(List<Staged> batch) {
        synchronized (stagingLock) {
            for (int i = 0; i < batch.size(); i++) {
                Staged head = staging.pollFirst();
                if (head != batch.get(i)) throw new IllegalStateException("EventTrail spool staging has a second consumer");
                stagedActions.decrementAndGet();
                stagedRows.addAndGet(-head.rows().size());
                stagedRetainedBytes.addAndGet(-head.retainedBytes());
            }
            stagingLock.notifyAll();
        }
    }

    private void dropStaged(List<Staged> batch, String reason) {
        removeStaged(batch);
        countCouldNotStore(batch.size(), rowsOf(batch), reason);
    }

    private static long rowsOf(List<Staged> entries) {
        long rows = 0;
        for (Staged entry : entries) rows += entry.rows().size();
        return rows;
    }

    /** MINOR-4 (r2): what the spool does next, so the could-not-store ERROR is true as written for every reason. */
    private static final String AFTER_PAUSE = " The spool takes no new actions for 60 s.";
    private static final String AFTER_CONTINUES = " The spool goes on storing the other actions.";
    private static final String AFTER_RUN = " The spool takes no new actions for the rest of this run.";
    private static final String AFTER_CLOSE = " The spool was closing.";

    /** S-10: counted once with their rows, one ERROR per run. */
    private void countCouldNotStore(long actions, long rows, String reason) {
        countCouldNotStore(actions, rows, reason, "close".equals(reason) ? AFTER_CLOSE : AFTER_RUN);
    }

    private void countCouldNotStore(long actions, long rows, String reason, String after) {
        if (actions <= 0) return;
        couldNotStoreActions.addAndGet(actions);
        couldNotStoreRows.addAndGet(rows);
        if (!storeErrorWritten) {
            storeErrorWritten = true;
            LOGGER.error("EventTrail disk spool could not store {} accepted action(s) ({} rows) ({}); they are lost and "
                    + "counted.{}", actions, rows, reason, after);
        }
    }

    /** MINOR-1 (r2): the first damage reason wins, atomically, whichever thread reports it. */
    private boolean claimDamage(String reason) {
        synchronized (damageLock) {
            if (damagedReason != null) return false;
            damagedReason = reason;
            return true;
        }
    }

    /**
     * MINOR-1 (r2): the writer thread reports an in-run failure (a read that failed). It only claims the damage and
     * posts the rest as a task: the spool thread, the only consumer of staging, drains and counts it, so a batch the
     * spool thread is inserting is never drained under it.
     */
    void requestDamage(String reason) {
        if (!claimDamage(reason)) return;
        tasks.add(() -> finishDamage(reason));
        wake();
    }

    /** design-02 7.3: damaged for the run; no further write to the file; staged entries counted under S-10. */
    void damage(String reason, List<Staged> pendingBatch) {
        if (!claimDamage(reason)) return;
        finishDamage(reason);
    }

    /** Spool thread only: drains staging (counted under S-10), writes the marker, logs the ERROR once. */
    private void finishDamage(String reason) {
        List<Staged> rest;
        synchronized (stagingLock) {
            rest = new ArrayList<>(staging);
        }
        if (!rest.isEmpty()) {
            removeStaged(rest);
            countCouldNotStore(rest.size(), rowsOf(rest), reason);
        }
        String marker = "";
        try {
            SpoolFile.createDamageMarker(spoolDirectory);
        } catch (IOException | RuntimeException failure) {
            marker = " The damage marker could not be written; the next start finds the damage when it reads.";
        }
        LOGGER.error("EventTrail disk spool file failed during the run ({}); it is no longer written. {} actions wait in "
                + "it; it will be set aside at the next start. New actions use memory.{}", reason, waitingFrames.get(), marker);
    }

    private void applyMarks() {
        Long seq;
        while ((seq = unreadableMarks.poll()) != null) {
            if (damagedReason != null) continue;
            long target = seq;
            try {
                withConnectionChecked(() -> file.markUnreadable(target));
            } catch (Exception failure) {
                damage("mark-failed", null);
                return;
            }
            refreshMirrors();
        }
    }

    private void applyDelete() {
        if (!deleteDue()) return;
        long x = requestedDeleteThrough;
        try {
            withConnectionChecked(() -> { file.verifyCurrent(); return null; });
            withConnectionChecked(() -> file.deleteThrough(x));
        } catch (Exception failure) {
            damage(failure instanceof IOException ? "identity-changed" : "delete-failed", null);
            return;
        }
        refreshMirrors();
        refreshOldest();
        if (waitingFrames.get() == 0 && stagedActions.get() == 0) {
            shrinkDue = true;
        }
    }

    private void shrinkNow() {
        shrinkDue = false;
        if (damagedReason != null || file == null) return;
        boolean done;
        try {
            done = withConnectionChecked(() -> file.shrink());
        } catch (Exception failure) {
            done = false;
        }
        if (!done && !shrinkWarned) {
            shrinkWarned = true;
            LOGGER.warn("EventTrail disk spool could not complete its shrink; spool/spool.db may keep deleted data until "
                    + "the next empty point or clean stop.");
        } else if (done) {
            // NIT-3 (r2): at most once per 10 minutes under oscillating load.
            long now = settings.clock().getAsLong();
            if (lastEmptyLineMillis == Long.MIN_VALUE || now - lastEmptyLineMillis >= EMPTY_LINE_INTERVAL_MILLIS) {
                lastEmptyLineMillis = now;
                LOGGER.info("EventTrail disk spool is empty again.");
            }
        }
    }

    private void refreshMirrors() {
        SpoolFile f = file;
        if (f == null) return;
        SpoolFile.Meta meta = f.meta();
        waitingFrames.set(meta.waitingFrames());
        waitingRows.set(meta.waitingRows());
        waitingBytes.set(meta.waitingBytes());
        unreadableFrames.set(meta.unreadableFrames());
        unreadableRows.set(meta.unreadableRows());
        unreadableBytes.set(meta.unreadableBytes());
    }

    /** Status only: "oldest" is the time since the spool last became non-empty (an upper bound). */
    private void refreshOldest() {
        if (waitingFrames.get() == 0 && stagedActions.get() == 0) oldestAppendedMillis = -1L;
    }

    // ---- writer side (design-02 4.5, 6.1) -----------------------------------------------------------------

    /**
     * Readable frames after {@code afterSeq}, at most the published high-water (m-5), in sequence order. Writer
     * thread; under spoolLock; the result set is closed before the release. A read error is an in-run failure.
     */
    List<SpoolFile.StoredFrame> readFrames(long afterSeq, int limit) throws SQLException {
        return readFrames(afterSeq, publishedHighWater.get(), limit);
    }

    /**
     * MAJOR-2 (r2): readable frames in (afterSeq, through], where {@code through} is a published high-water the caller
     * read once, before this read; the caller uses the same value for an advance-only unit, so a frame published
     * after it is never advanced past unread.
     */
    List<SpoolFile.StoredFrame> readFrames(long afterSeq, long through, int limit) throws SQLException {
        if (through <= afterSeq) return List.of();
        try {
            return withConnectionChecked(() -> {
                if (file == null || file.isClosed()) throw new SQLException("spool file closed", "08003");
                return file.readFrames(afterSeq, through, limit);
            });
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception unexpected) {
            throw new SQLException("spool read failed", "ETSP4", unexpected);
        }
    }

    /** The writer confirmed X (design-02 6.1): the spool thread deletes through it, off writeLifecycleLock. */
    void requestDeleteThrough(long x) {
        if (x > requestedDeleteThrough) {
            requestedDeleteThrough = x;
            wake();
        }
    }

    /** A frame failed its CRC or the strict decode (4.5): one ERROR per run, a mark request to the spool thread. */
    void reportUnreadable(long seq, int rowsAsStored, boolean thisRun) {
        unreadableActions.incrementAndGet();
        unreadableRowsAsStored.addAndGet(rowsAsStored);
        if (thisRun) unreadableThisRunRows.addAndGet(rowsAsStored);
        if (!unreadableErrorWritten) {
            unreadableErrorWritten = true;
            LOGGER.error("EventTrail disk spool: {} stored action(s) ({} rows as stored) failed their check and were "
                    + "skipped; they stay in spool/spool.db.", unreadableActions.get(), unreadableRowsAsStored.get());
        }
        if (damagedReason == null) {
            unreadableMarks.add(seq);
            wake();
        }
    }

    /** M-3 / m-4: replay stops for the run; new actions use memory; frames stay for the next start. */
    void stopReplay(String reason) {
        if (replayStoppedReason != null) return;
        replayStoppedReason = reason;
        // R-1 (r9): out of step also covers a position row that disappeared (failover, restore, table cleared).
        String extra = "out-of-step".equals(reason) ? " The database position was moved or removed by someone else (a "
                + "copied world on the same database and server id, a database rolled back or restored, a failover to "
                + "a lagging copy, or the position row deleted)." : "";
        LOGGER.error("EventTrail disk spool replay stopped ({}); {} actions stay on disk for the next start. New actions "
                + "use memory.{}", reason, waitingFrames.get(), extra);
    }

    void setReplayPaused(boolean paused) {
        replayPaused = paused;
    }

    /** m-8: a Throwable from spool code in admission turns the spool off for the run (one ERROR). */
    void failInAdmission(Throwable failure) {
        if (state == State.OFF) return;
        LOGGER.error("EventTrail disk spool failed inside admission ({}); it is off for this run and actions take the "
                + "memory path.", EventTrailDiagnostics.safeFailureSummary(failure));
        offReason = "unexpected-failure";
        state = State.OFF;
    }

    /** One WARN for "off" (design-02 11.1); files are left as the reason says. */
    void turnOff(String reason, Throwable cause) {
        if (state == State.OFF) return;
        offReason = reason;
        state = State.OFF;
        LOGGER.warn("EventTrail disk spool is off for this run ({}); actions the database writer cannot take are "
                + "refused and counted as before.{}", reason, cause == null ? ""
                : " (" + EventTrailDiagnostics.safeFailureSummary(cause) + ")");
    }

    // ---- close (design-02 5.2) ------------------------------------------------------------------------------

    /**
     * The closing thread posts CLOSE and waits at most {@code millis}. With {@code writerEnded} false, or when the
     * wait expires, the close is abandoned: no clean mark is written from then on, and only the spool thread closes
     * the connection after its running call returns. Returns true when the spool thread ended within the wait
     * (MINOR-12, r2: also with {@code writerEnded} false; {@link #closedWithoutMarkWriterRunning()} then says the
     * close skipped the clean mark only because the writer was still running).
     */
    boolean close(boolean writerEnded, long millis) {
        Thread thread = spoolThread;
        if (!writerEnded) {
            abandoned = true;
            writerRunningAtClose = true;
        }
        closeRequested = true;
        wake();
        if (thread == null) {
            state = State.CLOSED;
            return true;
        }
        if (thread == Thread.currentThread()) return false;
        try {
            thread.join(Math.max(1L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            abandoned = true;
            abandonedStagedActions = stagedActions.get();
            abandonedStagedRows = stagedRows.get();
            return false;
        }
        if (spoolThreadDied) {
            // R-3 (r9): nothing drains staging any more (the thread is gone, so this thread is its only consumer):
            // the staged actions are counted as accepted but not stored, with the could-not-store ERROR.
            List<Staged> rest;
            synchronized (stagingLock) { rest = new ArrayList<>(staging); }
            if (!rest.isEmpty()) {
                removeStaged(rest);
                countCouldNotStore(rest.size(), rowsOf(rest), SPOOL_THREAD_DIED, AFTER_CLOSE);
            }
        }
        if (!writerEnded) {
            abandonedStagedActions = stagedActions.get();
            abandonedStagedRows = stagedRows.get();
        }
        state = State.CLOSED;
        return true;
    }

    /** MINOR-12 (r2): the spool closed in time but without its clean mark, because the writer was still running. */
    boolean closedWithoutMarkWriterRunning() {
        return writerRunningAtClose && state == State.CLOSED;
    }

    /** On the spool thread: commit all staging, delete through X, shrink if empty, clean mark, close, release. */
    private void closeOnSpoolThread() {
        int guard = 0;
        while (!staging.isEmpty() && damagedReason == null && guard++ < 64) commitBatch();
        if (!staging.isEmpty()) {
            List<Staged> rest;
            synchronized (stagingLock) { rest = new ArrayList<>(staging); }
            dropStaged(rest, damagedReason != null ? damagedReason : "close");
        }
        runTasks();
        applyMarks();
        applyDelete();
        if (damagedReason == null && waitingFrames.get() == 0 && stagedActions.get() == 0) shrinkNow();
        closeFileQuietly(!abandoned && damagedReason == null);
    }

    /**
     * R-17 (r9): spool thread only; any other thread is refused (counted as a rule violation) and closes nothing, so
     * a closing thread can never close the connection under the spool thread's running call. R-7 (r9): a file damaged
     * in the run gets no further write (design-02 7.3), so it is closed without the clean = 2 meta write; abandoned
     * and undamaged unclean closes (a dead spool thread included) keep clean = 2. The checkpoint SQLite runs at the
     * physical close is inherent.
     */
    void closeFileQuietly(boolean markClean) {
        if (Thread.currentThread() != spoolThread) {
            ruleViolations.incrementAndGet();
            throw new IllegalStateException("EventTrail spool file closed off the spool thread");
        }
        spoolLock.lock();
        try {
            SpoolFile f = file;
            if (f != null) {
                if (markClean) f.closeAndRelease(true);
                else if (fileDamaged()) f.closeAndRelease(false);
                else f.closeAndReleaseWithoutCleanMark();
            }
        } finally {
            spoolLock.unlock();
        }
    }

    // ---- the S-4 guard ------------------------------------------------------------------------------------

    @FunctionalInterface
    interface ConnectionWork<T> {
        T run() throws Exception;
    }

    private <T> T withConnection(java.util.function.Supplier<T> work) {
        try {
            return withConnectionChecked(work::get);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    /**
     * Every call on the spool connection passes here: never under the storage's writeLifecycleLock, and only on
     * the spool or the writer thread (design-02 4.2; SpoolThreadRuleTest).
     */
    <T> T withConnectionChecked(ConnectionWork<T> work) throws Exception {
        Thread current = Thread.currentThread();
        boolean underLifecycle = forbiddenMonitor != null && Thread.holdsLock(forbiddenMonitor);
        boolean ownThread = current == spoolThread || current == writerThread;
        if (underLifecycle || !ownThread) {
            ruleViolations.incrementAndGet();
            throw new IllegalStateException("EventTrail spool connection used outside the spool and writer threads "
                    + "or under the admission lock");
        }
        if (current == spoolThread) connectionCallsBySpoolThread.incrementAndGet();
        else connectionCallsByWriterThread.incrementAndGet();
        spoolLock.lock();
        try {
            return work.run();
        } finally {
            spoolLock.unlock();
        }
    }

    // ---- status (design-02 11.2) ---------------------------------------------------------------------------

    /** A snapshot for the status lines and the shutdown summary; atomic mirrors only. */
    record SpoolStats(State state, String offReason, String damagedReason, String replayStoppedReason,
                      boolean replayPaused, boolean settled, long storePauseRemainingSeconds, String storeFailureReason,
                      long capBytes, long waitingFrames, long waitingRows, long waitingBytes, long oldestSeconds,
                      long stagedActions, long stagedRows, long spooledRows, long writtenFromSpoolRows,
                      long carriedInActions, long carriedInWrittenActions, long carriedInWrittenRows,
                      long refusedFullRows, long refusedUnwritableRows, long refusedOffRows, long admittedPastActions,
                      long couldNotStoreActions, long couldNotStoreRows, long unreadableActions,
                      long unreadableRowsAsStored, long expiredActions, long expiredRows, long skippedActions,
                      long positionBehindLostActions, long asideSets, long asideBytes, long asideActions,
                      long asideUnknownSets, long heldSets, long heldActions, long heldUnknownSets,
                      long savedAtStopActions,
                      long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting) {}

    volatile long disabledWaiting;

    SpoolStats stats() {
        long now = settings.clock().getAsLong();
        long oldest = oldestAppendedMillis;
        long committed = committedThisRunRows.get();
        long resolvedThisRun = writtenFromSpoolRows.get() + unreadableThisRunRows.get() + expiredThisRunRows.get()
                + poisonThisRunRows.get();
        return new SpoolStats(state, offReason, damagedReason, replayStoppedReason, replayPaused, positionSettled,
                Math.max(0L, (storePauseUntilMillis - now + 999L) / 1000L), storeFailureReason, settings.capBytes(),
                waitingFrames.get(), waitingRows.get(), waitingBytes.get(),
                oldest < 0 ? -1L : Math.max(0L, (now - oldest) / 1000L), stagedActions.get(), stagedRows.get(),
                spooledRows.get(), writtenFromSpoolRows.get(), carriedInActions.get(), carriedInWrittenActions.get(),
                carriedInWrittenRows.get(), refusedFullRows.get(), refusedUnwritableRows.get(), refusedOffRows.get(),
                admittedPastActions.get(), couldNotStoreActions.get(), couldNotStoreRows.get(), unreadableActions.get(),
                unreadableRowsAsStored.get(), expiredActions.get(), expiredRows.get(), skippedPastPositionActions.get(),
                positionBehindLostActions.get(), asideSets, asideBytes, asideActions, asideUnknownSets, heldSets,
                heldActions, heldUnknownSets, savedAtStopActions.get(), Math.max(0L, committed - resolvedThisRun),
                unreadableThisRunRows.get() + expiredThisRunRows.get(), disabledWaiting);
    }

    /** design-02 11.2, the texts as written (no colour codes; the command adds them). */
    static List<String> statusLines(SpoolStats s) {
        List<String> lines = new ArrayList<>();
        long limit = s.capBytes() / MIB;
        switch (s.state()) {
            case DISABLED -> lines.add("Disk spool: disabled in the configuration"
                    + (s.disabledWaiting() > 0 ? "; " + count(s.disabledWaiting()) + " actions wait unread" : ""));
            case OFF -> lines.add("Disk spool: off for this run (" + s.offReason() + ")");
            case STARTING -> lines.add("Disk spool: starting");
            case CLOSED -> lines.add("Disk spool: closed");
            case READY -> {
                if (SPOOL_THREAD_DIED.equals(s.damagedReason())) {
                    // R-3 (r9): the thread ended with a fatal error; the file is not damaged.
                    lines.add("Disk spool: stopped - its thread ended with a fatal error; takes no new actions; "
                            + count(s.waitingFrames()) + " actions stay on disk for the next start, "
                            + count(s.stagedActions()) + " staged actions are not stored");
                } else if (s.damagedReason() != null) {
                    // R-11 (r9): the line says whether replay still runs (read-only, L14) or why it stopped.
                    lines.add("Disk spool: damaged (" + s.damagedReason() + "); no longer written; "
                            + count(s.waitingFrames()) + " actions will be set aside at the next start; replay "
                            + (s.replayStoppedReason() != null ? "stopped (" + s.replayStoppedReason() + ")"
                            : "continues read-only"));
                } else if (s.replayStoppedReason() != null) {
                    lines.add("Disk spool: replay stopped (" + s.replayStoppedReason() + "); "
                            + count(s.waitingFrames()) + " actions stay on disk for the next start");
                } else if (s.replayPaused() || !s.settled()) {
                    lines.add("Disk spool: paused - the database position row is locked; " + count(s.waitingFrames())
                            + " actions wait, new actions use memory");
                } else if (s.storePauseRemainingSeconds() > 0) {
                    lines.add("Disk spool: not accepting for " + s.storePauseRemainingSeconds()
                            + "s (could not store: " + s.storeFailureReason() + ")");
                } else if (s.waitingBytes() >= s.capBytes()) {
                    lines.add("Disk spool: FULL (" + (s.waitingBytes() / MIB) + " of " + limit
                            + " MiB) - new actions go to memory while it has room, else are refused");
                } else if (s.waitingFrames() + s.stagedActions() == 0) {
                    lines.add("Disk spool: ready, empty (limit " + limit + " MiB)");
                } else {
                    lines.add("Disk spool: in use - " + count(s.waitingFrames() + s.stagedActions())
                            + " actions waiting on disk (" + (s.waitingBytes() / MIB) + " MiB of " + limit + " MiB)"
                            + (s.oldestSeconds() >= 0 ? ", oldest " + s.oldestSeconds() + "s" : ""));
                }
            }
        }
        lines.add("Spooled / written from the spool since startup: " + count(s.spooledRows()) + " / "
                + count(s.writtenFromSpoolRows() + s.carriedInWrittenRows()) + " rows; carried over from an earlier run: "
                + count(s.carriedInActions()) + " actions");
        if (s.refusedFullRows() + s.refusedUnwritableRows() + s.refusedOffRows() > 0) {
            lines.add("Refused while the spool was full / unwritable / off: " + count(s.refusedFullRows()) + " / "
                    + count(s.refusedUnwritableRows()) + " / " + count(s.refusedOffRows()) + " rows");
        }
        if (s.admittedPastActions() > 0) lines.add("Admitted past the spool: " + count(s.admittedPastActions()) + " actions");
        if (s.couldNotStoreActions() > 0) {
            lines.add("Accepted but not stored on disk: " + count(s.couldNotStoreActions()) + " actions ("
                    + count(s.couldNotStoreRows()) + " rows)");
        }
        if (s.unreadableActions() > 0) {
            lines.add("Spool actions that failed their check: " + count(s.unreadableActions()) + " ("
                    + count(s.unreadableRowsAsStored()) + " rows as stored), kept in spool.db");
        }
        if (s.expiredActions() > 0) {
            lines.add("Expired while waiting: " + count(s.expiredActions()) + " actions (" + count(s.expiredRows()) + " rows)");
        }
        if (s.skippedActions() > 0) {
            lines.add("Skipped: the database position was already past " + count(s.skippedActions())
                    + " actions (content not compared)");
        }
        if (s.positionBehindLostActions() > 0) {
            lines.add("Lost: the database no longer has " + count(s.positionBehindLostActions())
                    + " actions the spool handed it");
        }
        if (s.asideSets() > 0) {
            lines.add("Set aside: " + count(s.asideSets()) + " sets (" + (s.asideBytes() / MIB) + " MiB, "
                    + (s.asideUnknownSets() > 0 && s.asideActions() == 0 ? "unknown"
                    : count(s.asideActions()) + " actions" + (s.asideUnknownSets() > 0 ? " | unknown" : ""))
                    + ") in spool/aside");
        }
        if (s.heldSets() > 0) {
            // spf1 MINOR-2: the unknown count shown as the aside line shows it.
            lines.add("Held: " + count(s.heldSets()) + " sets ("
                    + (s.heldUnknownSets() > 0 && s.heldActions() == 0 ? "unknown"
                    : count(s.heldActions()) + " actions" + (s.heldUnknownSets() > 0 ? " | unknown" : ""))
                    + ") in spool/held");
        }
        return List.copyOf(lines);
    }

    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    // ---- misc --------------------------------------------------------------------------------------------

    static long usableSpace(Path directory) {
        try {
            return Files.getFileStore(directory).getUsableSpace();
        } catch (IOException | RuntimeException unavailable) {
            return Long.MAX_VALUE;
        }
    }

    String backendName() { return backendName; }
}
