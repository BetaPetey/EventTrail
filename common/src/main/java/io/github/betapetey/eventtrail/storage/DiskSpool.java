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
 * {@code writeLifecycleLock} and {@code spoolLock} are never held together. {@code stagingLock} is a leaf: no other
 * lock is taken and no I/O is done under it. Q383 A: it also guards the cap test's mirrors (staged, waiting,
 * unreadable, the published high-water): they change and are read for the cap test only under it.</p>
 */
final class DiskSpool {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final String DIRECTORY = "spool";
    /** No-drop item 2: at most this many staged actions per spool transaction; room comes back after each one. */
    static final int COMMIT_CHUNK_ACTIONS = 4_096;
    /**
     * D-563 (B2): the least retained bytes one action can carry by the writer's estimate. Every action has at least one
     * row, and every row's estimate starts at the codec's fixed 512 bytes (SchemaV1RowCodec.retainedBytes; a test pins
     * the two together). So the hand-off's byte limit also bounds its action count: at most limit / 512 actions.
     */
    static final long MIN_ACTION_RETAINED_BYTES = 512L;
    /**
     * D-563 (B2), Owner Q405 A: the disk hand-off (staging) between admission and the spool thread holds actions in
     * memory: the waiting room, the memory set aside for actions waiting to be written to disk. Its memory limit is
     * {@link #handOffByteLimit} (the spool's size, fitted to the heap at startup). The Owner's rule (Q367 A as Q405 A
     * rewrote it, with Q413 A, 4:03 PM 11-25): "Actions are dropped only when the disk spool is full, when the drive it
     * is on has less than 512 MiB free, or when they arrive faster than the disk can take them for long enough to fill
     * the memory set aside for them. Each case has its own WARN." ({@link #REFUSED_DISK_FULL}, {@link #REFUSED_FLOOR},
     * {@link #REFUSED_WAITING_ROOM}). The action count needs no bound: the byte limit
     * bounds it ({@link #MIN_ACTION_RETAINED_BYTES}). This field is a test seam only: tests may lower it before the
     * first action to fill the waiting room quickly by count; a refusal there is the waiting room's too. Production
     * leaves it at Integer.MAX_VALUE, which the byte limit always reaches first.
     */
    volatile int stagingMaxActions = Integer.MAX_VALUE;

    /** Owner Q405 A: the disk part is full (the cap test, exact disk bytes). Its WARN is {@link #fullWarn}. */
    static final String REFUSED_DISK_FULL = "full";
    /**
     * Owner Q405 A: the waiting room is full (the hand-off's memory limit, or the test seam). Its WARN is
     * {@link #waitingRoomWarn}. The string is the one the "behind" counter has always used.
     */
    static final String REFUSED_WAITING_ROOM = "behind";
    /**
     * The drive's free space is below {@link #FLOOR_BYTES}. Owner Q413 A (4:03 PM 11-25): its own count ("drive low on
     * space", {@link #refusedFloorRows}) and its own WARN ({@link #floorWarn}); never counted as "disk spool full".
     */
    static final String REFUSED_FLOOR = "floor";
    /**
     * Owner Q405 A: the spool's sequence numbers are used up; counted as "disk spool full", with no WARN of its own
     * (Q413: it cannot happen in practice, more than a billion spooled actions between two empty starts).
     */
    static final String REFUSED_SEQUENCE = "sequence";

    /** True for the refusals counted as "disk spool full" (the cap and the sequence limit; Q413 A: not the floor). */
    static boolean countsAsDiskFull(String reason) {
        return REFUSED_DISK_FULL.equals(reason) || REFUSED_SEQUENCE.equals(reason);
    }

    /** The close's commit rounds (D-563, live): the staged actions in chunks, plus the 64 rounds the close always had. */
    static int closeCommitRounds(long stagedActionsNow) {
        long chunks = (Math.max(0L, stagedActionsNow) + COMMIT_CHUNK_ACTIONS - 1) / COMMIT_CHUNK_ACTIONS;
        return (int) Math.min(Integer.MAX_VALUE - 64L, chunks) + 64;
    }

    /**
     * D-563, Owner Q397 A: the hand-off's most retained bytes (its memory limit) for a cap: the cap itself. Before
     * Q397 it was cap / 1.5, the cap test's staged weight; the cap test now counts each staged action's exact disk
     * bytes instead ({@link #refusal}), so the memory limit is no longer tied to it.
     */
    static long handOffMaxBytes(long capBytes) {
        return Math.max(0L, capBytes);
    }

    /** D-563: the hand-off's most actions for a cap (the byte limit over the least bytes an action carries). */
    static long handOffMaxActions(long capBytes) {
        return handOffMaxBytes(capBytes) / MIN_ACTION_RETAINED_BYTES;
    }

    /**
     * D-563, Owner Q405 A: the waiting room's memory limit, in retained bytes: {@link Settings#waitingRoomBytes}, at
     * most {@link #handOffMaxBytes} of the cap, fitted to the heap at startup ({@link #fittedWaitingRoomBytes}). It does
     * not depend on what waits on disk: the disk side is the cap test's, which counts each staged action's exact disk
     * bytes ({@link #refusal}), at the configured cap on every server. A refusal at this limit is
     * {@link #REFUSED_WAITING_ROOM}, never "full".
     */
    long handOffByteLimit() {
        return settings.waitingRoomBytes();
    }

    /**
     * Owner Q397 A: the disk room the cap leaves now, in exact disk bytes: the cap less the bytes waiting on disk, the
     * unreadable ones and the staged actions' disk bytes. Takes no lock itself; every caller that decides on it calls
     * it inside a {@code stagingLock} hold (Q383 A), so the mirrors are one consistent read.
     */
    private long diskRoomLocked() {
        return settings.capBytes() - waitingBytes.get() - unreadableBytes.get() - stagedDiskBytes.get();
    }
    static final long FLOOR_BYTES = 512L * 1024L * 1024L;
    static final long FLOOR_PROBE_MILLIS = 5_000L;
    /** Owner Q413 (b) A: the least time between two writes of the same drop WARN (10 minutes). */
    static final long DROP_WARN_REARM_GAP_MILLIS = 10L * 60L * 1000L;
    static final long STORE_FAILURE_PAUSE_MILLIS = 60_000L;
    static final long OPEN_WAIT_MILLIS = 5_000L;
    static final long CLOSE_WAIT_MILLIS = 5_000L;
    static final long RETIRE_SEQUENCE = 1L << 30;
    static final long MAX_SEQUENCE = Integer.MAX_VALUE;
    static final int BACKEND_SQLITE = 1;
    static final int BACKEND_MYSQL = 2;
    static final int BACKEND_POSTGRESQL = 3;
    private static final long MIB = 1024L * 1024L;

    /**
     * Everything a test may replace; production uses {@link #production}. {@code capBytes} is the disk spool's size,
     * the configured one on every server (Owner Q405 A); {@code waitingRoomBytes} is the waiting room's memory limit
     * ({@link #handOffByteLimit}), and {@code maxHeapBytes} the server's maximum heap that the startup check read (0
     * when none was read). When the waiting room is below {@link #handOffMaxBytes} of the cap, the startup check
     * lowered it to fit the heap ({@link #fittedWaitingRoomBytes}). {@code collector} is the garbage collector the
     * server runs, by its beans' names (Owner Q378 A), for the -Xmx advice ({@link #xmxMibAdvisedFor}).
     */
    record Settings(boolean enabled, long capBytes, int retentionDays, boolean deleteKeptAfterRetention,
                    SpoolFile.ConnectionFactory factory, LongSupplier clock, ToLongFunction<Path> usableSpace,
                    OpenHook openHook, long waitingRoomBytes, long maxHeapBytes, HeapCollector collector) {
        Settings {
            Objects.requireNonNull(factory, "factory");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(usableSpace, "usableSpace");
            if (openHook == null) openHook = () -> {};
            if (collector == null) collector = HeapCollector.UNKNOWN;
        }

        /** A heap read with no collector name: the advice keeps the unknown collector's safe margin. */
        Settings(boolean enabled, long capBytes, int retentionDays, boolean deleteKeptAfterRetention,
                 SpoolFile.ConnectionFactory factory, LongSupplier clock, ToLongFunction<Path> usableSpace,
                 OpenHook openHook, long waitingRoomBytes, long maxHeapBytes) {
            this(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, usableSpace, openHook,
                    waitingRoomBytes, maxHeapBytes, HeapCollector.UNKNOWN);
        }

        /** The shape before Q367: the waiting room is the whole of the cap ({@link #handOffMaxBytes}), no heap read. */
        Settings(boolean enabled, long capBytes, int retentionDays, boolean deleteKeptAfterRetention,
                 SpoolFile.ConnectionFactory factory, LongSupplier clock, ToLongFunction<Path> usableSpace,
                 OpenHook openHook) {
            this(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, usableSpace, openHook,
                    handOffMaxBytes(capBytes), 0L);
        }

        /**
         * Owner Q378 A: what Java reports about itself only, its usable maximum memory ({@link Runtime#maxMemory()})
         * and its collector's name ({@link HeapCollector#current()}); never the server's command-line arguments.
         */
        static Settings production(boolean enabled, int maxMegabytes, int retentionDays) {
            return production(enabled, maxMegabytes, retentionDays, Runtime.getRuntime().maxMemory(),
                    HeapCollector.current());
        }

        /** {@link #production(boolean, int, int, long, HeapCollector)} with no collector name (the safe margin). */
        static Settings production(boolean enabled, int maxMegabytes, int retentionDays, long maxHeapBytes) {
            return production(enabled, maxMegabytes, retentionDays, maxHeapBytes, HeapCollector.UNKNOWN);
        }

        /**
         * Owner Q405 A: the configured disk size, unchanged on every server, and the waiting room fitted at startup to
         * the heap ({@link #fittedWaitingRoomBytes}).
         */
        static Settings production(boolean enabled, int maxMegabytes, int retentionDays, long maxHeapBytes,
                                   HeapCollector collector) {
            long configured = Math.max(1L, maxMegabytes) * MIB;
            return new Settings(enabled, configured, retentionDays,
                    SpoolFile.DELETE_KEPT_AFTER_RETENTION, SpoolFile.ConnectionFactory.bundled(),
                    System::currentTimeMillis, DiskSpool::usableSpace, null,
                    fittedWaitingRoomBytes(configured, maxHeapBytes), maxHeapBytes, collector);
        }

        /** Owner Q405 A: true when the startup check lowered the waiting room below the whole of the cap. */
        boolean waitingRoomLowered() { return waitingRoomBytes < handOffMaxBytes(capBytes); }

        Settings withClock(LongSupplier c) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, c, usableSpace, openHook, waitingRoomBytes, maxHeapBytes, collector); }
        Settings withUsableSpace(ToLongFunction<Path> u) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, u, openHook, waitingRoomBytes, maxHeapBytes, collector); }
        Settings withOpenHook(OpenHook h) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, usableSpace, h, waitingRoomBytes, maxHeapBytes, collector); }
        Settings withFactory(SpoolFile.ConnectionFactory f) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, f, clock, usableSpace, openHook, waitingRoomBytes, maxHeapBytes, collector); }
        Settings withDeleteKept(boolean d) { return new Settings(enabled, capBytes, retentionDays, d, factory, clock, usableSpace, openHook, waitingRoomBytes, maxHeapBytes, collector); }
        /** Test helper: the same settings with the waiting room's memory limit set to {@code w}. */
        Settings withWaitingRoom(long w) { return new Settings(enabled, capBytes, retentionDays, deleteKeptAfterRetention, factory, clock, usableSpace, openHook, w, maxHeapBytes, collector); }
    }

    // ---- Q367 A, as Owner Q405 A rewrote it: the startup memory check fits the waiting room, not the disk ----------
    //
    // The burst memory is the waiting room at its most (the disk hand-off's memory limit, retained bytes by the
    // writer's estimate) plus the database writer's memory queue (WRITER_MEMORY_BYTES). The heap a burst really takes
    // is HEAP_PER_RETAINED_BYTE times that estimate plus a fixed part (BURST_FIXED_HEAP_BYTES: encoding, the spool
    // thread's chunk, and garbage not yet collected). The check keeps it within SAFE_HEAP_SHARE of the server's
    // maximum heap:
    //
    //     HEAP_PER_RETAINED_BYTE * (waitingRoom + WRITER_MEMORY_BYTES) + BURST_FIXED_HEAP_BYTES
    //         <= SAFE_HEAP_SHARE * maxHeap
    //
    // so the largest safe waiting room is (SAFE_HEAP_SHARE * maxHeap - BURST_FIXED_HEAP_BYTES)
    // / HEAP_PER_RETAINED_BYTE - WRITER_MEMORY_BYTES, rounded down to a whole MiB, never below MIN_WAITING_ROOM_BYTES
    // and never above the whole of the cap (handOffMaxBytes(cap), the cap itself since Owner Q397 A). Owner Q405 A:
    // the disk spool keeps its configured size on every server; only the waiting room is fitted, and a refusal there
    // has its own cause and WARN (REFUSED_WAITING_ROOM, waitingRoomWarn). A 512 MiB spool needs 3,648 MiB of heap for
    // a waiting room of the whole 512 MiB.
    //
    // Basis (loaded window 2, 11-25, W\runs\lw2-run1.facts.txt; 512 MiB test heap, 100,000-row B2/H1 bursts on SQLite,
    // MySQL and PostgreSQL at a 512 MiB spool, and one SQLite leg at 128 MiB): peak heap used above a post-GC baseline,
    // over the peak hand-off plus memory-queue retained bytes, was 0.98 to 1.45 at 512 MiB (worst MySQL B2: 309 MiB
    // for 223 MB) and 2.31 at 128 MiB (230 MiB for 104 MB), which shows a fixed part. 1.5 per byte plus 96 MiB covers
    // every leg (the 128 MiB leg: 245 MiB for 230 measured). Peak heap used counts garbage not yet collected, so it is
    // an upper bound. SAFE_HEAP_SHARE is a policy choice, not a measurement.
    //
    // PROVISIONAL (Owner Q370 B, 11-25): HEAP_PER_RETAINED_BYTE and BURST_FIXED_HEAP_BYTES, the clamp's two measured
    // numbers, keep these values only until the B2 latency fix lands; they are set from that fix's re-run.

    /** The database writer's memory queue, in retained bytes (AbstractJdbcStorage.MAX_PENDING_PAYLOAD_BYTES). */
    static final long WRITER_MEMORY_BYTES = 32L * MIB;
    /**
     * The smallest waiting room the startup check fits (Owner Q405 A); the same 64 MiB as the smallest spool size the
     * configuration allows (database.spool.maxMegabytes minimum 64), so a waiting room is never lowered below it.
     */
    static final long MIN_WAITING_ROOM_BYTES = 64L * MIB;
    /** The share of the server's maximum heap the spool's burst memory may take (policy; see the basis above). */
    static final double SAFE_HEAP_SHARE = 0.25;
    /**
     * PROVISIONAL (Q370 B; set after the B2 fix's re-run). Heap bytes per retained byte of the writer's estimate
     * (loaded window 2: worst 1.45 at 512 MiB, rounded up).
     */
    static final double HEAP_PER_RETAINED_BYTE = 1.5;
    /**
     * PROVISIONAL (Q370 B; set after the B2 fix's re-run). The fixed part of a burst's heap (loaded window 2: the
     * 128 MiB leg's 230 MiB needs it; see the basis above).
     */
    static final long BURST_FIXED_HEAP_BYTES = 96L * MIB;

    /** The burst memory, in heap bytes, a waiting room of {@code waitingRoomBytes} allows (the formula above). */
    static long burstHeapBytes(long waitingRoomBytes) {
        return (long) Math.ceil(HEAP_PER_RETAINED_BYTE * (Math.max(0L, waitingRoomBytes) + WRITER_MEMORY_BYTES))
                + BURST_FIXED_HEAP_BYTES;
    }

    /**
     * Owner Q405 A: the waiting room for a spool of {@code capBytes} on a heap of {@code maxHeapBytes}: the whole of
     * the cap ({@link #handOffMaxBytes}) when its burst memory fits {@link #SAFE_HEAP_SHARE} of the heap, else the
     * largest whole-MiB waiting room that fits, at least {@link #MIN_WAITING_ROOM_BYTES} (and never above the whole).
     * A heap of 0 or Long.MAX_VALUE (none read, or no limit) changes nothing. The disk cap is never changed.
     */
    static long fittedWaitingRoomBytes(long capBytes, long maxHeapBytes) {
        long whole = handOffMaxBytes(capBytes);
        if (maxHeapBytes <= 0L || maxHeapBytes == Long.MAX_VALUE || whole <= MIN_WAITING_ROOM_BYTES) return whole;
        double budget = SAFE_HEAP_SHARE * maxHeapBytes;
        if (burstHeapBytes(whole) <= budget) return whole;
        double fitting = (budget - BURST_FIXED_HEAP_BYTES) / HEAP_PER_RETAINED_BYTE - WRITER_MEMORY_BYTES;
        long wholeMib = fitting <= 0d ? 0L : ((long) Math.floor(fitting)) / MIB * MIB;
        // The division above is in floating point; settle the last MiB against burstHeapBytes itself, so the size is
        // exactly the largest whole MiB that fits (a boundary like 416 MiB on 3 GiB is not lost to rounding).
        while (wholeMib + MIB < whole && burstHeapBytes(wholeMib + MIB) <= budget) wholeMib += MIB;
        while (wholeMib > MIN_WAITING_ROOM_BYTES && burstHeapBytes(wholeMib) > budget) wholeMib -= MIB;
        return Math.min(whole, Math.max(MIN_WAITING_ROOM_BYTES, wholeMib));
    }

    /**
     * The least maximum heap, in whole MiB rounded up, on which a spool of {@code capBytes} keeps a waiting room of the
     * whole cap: that waiting room's burst memory over {@link #SAFE_HEAP_SHARE}.
     */
    static long heapMibNeededFor(long capBytes) {
        return (long) Math.ceil(burstHeapBytes(handOffMaxBytes(capBytes)) / SAFE_HEAP_SHARE / MIB);
    }

    /**
     * The -Xmx advice is a whole number of these steps (256 MiB), so it is easy to set. This rounding is the build's
     * choice, not the Owner's: on collectors that report the whole heap it can name up to 255 MiB more than needed.
     */
    static final long XMX_ADVICE_STEP_MIB = 256L;

    /**
     * Owner Q378 A: the -Xmx, in MiB, that the startup WARN advises for a spool of {@code capBytes} on a server running
     * {@code collector}: the least whole {@link #XMX_ADVICE_STEP_MIB} step at which the collector's usable maximum memory
     * ({@link HeapCollector#leastMaxMemoryAt}, what {@link Runtime#maxMemory()} reports for that -Xmx) passes the same
     * startup check unlowered ({@link #fittedWaitingRoomBytes}). Today's {@link #heapMibNeededFor} is usable memory, not
     * -Xmx: Parallel and Serial report less than their -Xmx, so setting -Xmx to it alone could keep the WARN. The check
     * and its threshold are unchanged; only the advised figure is.
     */
    static long xmxMibAdvisedFor(long capBytes, HeapCollector collector) {
        HeapCollector c = collector == null ? HeapCollector.UNKNOWN : collector;
        long whole = handOffMaxBytes(capBytes);
        long step = XMX_ADVICE_STEP_MIB;
        // Start one step below the first step at or above the -Xmx whose usable memory is the needed heap (the needed
        // heap is rounded up to a whole MiB, so the check can pass a little below it), then take the first step that
        // passes; the loop runs a step or two.
        long first = (c.leastXmxMibReporting(heapMibNeededFor(capBytes)) + step - 1L) / step * step;
        long xmxMib = Math.max(step, first - step);
        while (fittedWaitingRoomBytes(capBytes, c.leastMaxMemoryAt(xmxMib * MIB)) < whole) xmxMib += step;
        return xmxMib;
    }

    /**
     * Owner Q405 A: the startup WARN when the waiting room was lowered to fit the heap; null when it was not. The
     * Owner's admin-facing text (Q412 A, owner-texts-q405.md item a). Owner Q420 A: its "dropped only when" sentence
     * names all three drop cases, with this WARN's own waiting-room figure. Owner Q378 A: the -Xmx it names is
     * {@link #xmxMibAdvisedFor} for the server's collector, so following it clears this WARN.
     */
    static String waitingRoomLoweredWarn(Settings settings) {
        if (!settings.waitingRoomLowered()) return null;
        long room = settings.waitingRoomBytes() / MIB;
        long cap = settings.capBytes() / MIB;
        return "EventTrail set aside " + room + " MiB of memory for actions waiting to be written to disk, less than"
                + " the disk spool's " + cap + " MiB, because the server's maximum memory is "
                + settings.maxHeapBytes() / MIB + " MiB. The disk spool keeps its full " + cap + " MiB."
                + " Actions are dropped only when the disk spool is full, when the drive it is on has less than "
                + FLOOR_BYTES / MIB + " MiB free, or when they arrive faster than the disk can take them for long"
                + " enough to fill those " + room + " MiB. To set aside the full " + cap + " MiB, give the server at least "
                + xmxMibAdvisedFor(settings.capBytes(), settings.collector()) + " MiB (-Xmx).";
    }

    /**
     * Owner Q405 A: the status line when the waiting room was lowered; null when it was not. The Owner's admin-facing
     * text (Q412 A, owner-texts-q405.md item b).
     */
    static String waitingRoomLoweredStatusLine(Settings settings) {
        if (!settings.waitingRoomLowered()) return null;
        return "Memory for actions waiting to be written to disk: " + settings.waitingRoomBytes() / MIB
                + " MiB, lowered from " + settings.capBytes() / MIB + " MiB to fit the server's "
                + settings.maxHeapBytes() / MIB + " MiB of memory; the disk spool keeps its "
                + settings.capBytes() / MIB + " MiB";
    }

    /** Test seam: runs on the spool thread before the open (an injected slow open, design-02 13.1). */
    @FunctionalInterface
    interface OpenHook {
        void beforeOpen() throws Exception;
    }

    /**
     * One staged action (design-02 4.3): the rows admission built, their retained bytes (the memory limit's count),
     * their exact disk bytes once committed (the cap test's count, Owner Q397 A), the capture time.
     */
    record Staged(List<SchemaV1Row> rows, long retainedBytes, long diskBytes, long minRowTs, long capturedMillis) {}

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
    /**
     * Owner Q413 (b) A (4:03 PM 11-25): each drop WARN (disk spool full, waiting room full, drive low on space) is
     * written once per episode. The flag is set when the WARN is written and cleared, under {@code stagingLock}, once
     * the spool is empty again ({@link #clearFullFlagsIfEmptyLocked}); two writes of the same WARN are at least
     * {@link #DROP_WARN_REARM_GAP_MILLIS} apart (its {@code ...WarnMillis}, the spool clock when it was last written).
     * All six are read and written only under {@code stagingLock}.
     */
    private boolean fullWarned;
    private boolean waitingRoomWarned;
    private boolean floorWarned;
    private long fullWarnMillis = Long.MIN_VALUE;
    private long waitingRoomWarnMillis = Long.MIN_VALUE;
    private long floorWarnMillis = Long.MIN_VALUE;
    /** Owner Q413 (a) A: the drive's free bytes at the last floor probe. */
    private volatile long lastUsableBytes = Long.MAX_VALUE;
    /** Owner Q413 (a) A: {@link #lastUsableBytes} when the floor WARN was queued, the figure that WARN names. */
    private volatile long floorWarnUsableBytes = Long.MAX_VALUE;
    /**
     * Owner Q405 A (check-q397 MINOR-2): the last room refusal was the disk's ({@code diskFullSeen}) or the waiting
     * room's ({@code waitingRoomFullSeen}); set when one is counted, cleared by the next action staged. The status says
     * FULL from them as well as from the figures, so it agrees with the WARN that fired. Q405 re-check MAJOR-A: also
     * cleared, under {@code stagingLock}, where that part gets room back (the waiting room when staged actions leave,
     * the disk part at a delete or a drop) and both when the spool is empty; set only while the spool holds something.
     */
    private volatile boolean diskFullSeen;
    private volatile boolean waitingRoomFullSeen;
    private volatile boolean storeErrorWritten;
    private volatile boolean unreadableErrorWritten;

    // Lock-free mirrors (design-02 4.2).
    private final AtomicLong stagedActions = new AtomicLong();
    private final AtomicLong stagedRows = new AtomicLong();
    private final AtomicLong stagedRetainedBytes = new AtomicLong();
    /** Owner Q397 A: the staged actions' exact disk bytes (the cap test's count); changed only under stagingLock. */
    private final AtomicLong stagedDiskBytes = new AtomicLong();
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
    /** No-drop item 2: dropped because the disk hand-off was full (the spool thread behind), not the disk cap. */
    final AtomicLong refusedBehindRows = new AtomicLong();
    /** Owner Q413 (a) A: dropped because the drive had less than {@link #FLOOR_BYTES} free ("drive low on space"). */
    final AtomicLong refusedFloorRows = new AtomicLong();
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
        // Q383 A: the cap test's mirrors change only under stagingLock (a leaf; here possibly under spoolLock, the
        // documented order spoolLock -> stagingLock). No I/O inside.
        synchronized (stagingLock) {
            long onDiskBefore = waitingBytes.get() + unreadableBytes.get();
            waitingFrames.set(meta.waitingFrames());
            waitingRows.set(meta.waitingRows());
            waitingBytes.set(meta.waitingBytes());
            unreadableFrames.set(meta.unreadableFrames());
            unreadableRows.set(meta.unreadableRows());
            unreadableBytes.set(meta.unreadableBytes());
            publishedHighWater.set(meta.highWater());
            // Q405 re-check MAJOR-A: as refreshMirrors (a file set aside or replaced gives the disk part room back).
            if (meta.waitingBytes() + meta.unreadableBytes() < onDiskBefore) diskFullSeen = false;
            clearFullFlagsIfEmptyLocked();
        }
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
        synchronized (stagingLock) { publishedHighWater.set(Math.max(publishedHighWater.get(), position)); }
    }

    void rebindOnSpoolThread(boolean resetSequence) throws Exception {
        withConnectionChecked(() -> { file.rebind(binding, resetSequence); return null; });
        if (resetSequence) {
            synchronized (stagingLock) { publishedHighWater.set(0); }
        }
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

    /**
     * The latch: waiting + staged > 0 (design-02 4.2, the Advisor's n-1: one definition). Lock-free. Q383 A: staged is
     * read first. A commit raises waiting before it lowers staged (both in one {@code stagingLock} step), so a read
     * that straddles the commit sees either the old staged count or the new waiting count, never 0 for both.
     */
    boolean latched() {
        long staged = stagedActions.get();
        Runnable gap = latchedGapSeamForTest;
        if (gap != null) gap.run();
        return staged + waitingFrames.get() > 0;
    }

    /** Q383 A test seam: runs between {@link #latched}'s two reads (null in production). */
    volatile Runnable latchedGapSeamForTest;

    /**
     * Per action: can the spool take one action of {@code retainedBytes}? Decided by {@link #refusal}: a few reads
     * under the leaf {@code stagingLock} (Q383 A), no I/O.
     */
    boolean canAccept(long retainedBytes) {
        return refusal(retainedBytes) == null;
    }

    /** The capacity hint's part (design-01 2.2 / L8): could the spool take a small action? */
    boolean canAcceptHint() {
        return canAccept(512L);
    }

    /**
     * {@link #refusal(long, long)} for an action whose disk bytes are taken as its retained bytes (the capacity hint
     * and tests that give one figure). Admission gives the exact disk bytes (Owner Q397 A).
     */
    String refusal(long retainedBytes) {
        return refusal(retainedBytes, retainedBytes);
    }

    /**
     * null when the spool can accept; else the breakdown of a refusal: {@link #REFUSED_DISK_FULL} (the disk cap),
     * {@link #REFUSED_FLOOR} (the drive's free-space floor), {@link #REFUSED_SEQUENCE} (the sequence limit),
     * {@link #REFUSED_WAITING_ROOM} (the waiting room's memory limit, or the test seam {@link #stagingMaxActions}),
     * "unwritable" or "off". No-drop item 2 (Owner Q330 A, the lock wait): a paused replay or a position check still
     * waiting no longer turns the spool away; new actions wait on disk behind the paused ones (the latch), and while
     * the check waits they stay staged until it settles ({@link #loop}). The caller counts a refusal from this one
     * result and never asks again (the B2 mislabel: a second read could find room and say "off").
     *
     * <p>Owner Q397 A: the cap test counts exact disk bytes. {@code diskBytes} is the action's encoded frame length
     * ({@link SpoolFrameCodec#encodedLength}); the bytes waiting on disk and the unreadable ones are frame lengths
     * too, and each staged action counts its own frame length, so "full" means the disk bytes, counted exactly, would
     * pass the cap. Owner Q405 A: the waiting room's memory limit ({@link #handOffByteLimit}, fitted to the heap) is
     * the second room test, with its own cause, so a waiting-room refusal while the disk has room is never "full".</p>
     *
     * <p>Q383 A (the Q379 double count): the cap test reads the staged, waiting, unreadable and high-water mirrors
     * under {@code stagingLock}, and the spool thread changes them only under that lock, a commit in one step
     * ({@link #commitBatch}). So a committed chunk is never counted both as waiting on disk and as staged, and the
     * test never pairs an old staged value with a new waiting one. The lock is a leaf, held here for a few reads; the
     * caller may hold {@code writeLifecycleLock} (the documented order).</p>
     */
    String refusal(long retainedBytes, long diskBytes) {
        Runnable seam = refusalSeamForTest;
        if (seam != null) seam.run();
        if (state != State.READY || closeRequested) return "off";
        if (replayStoppedReason != null) return "off";
        if (damagedReason != null) return "unwritable";
        if (settings.clock().getAsLong() < storePauseUntilMillis) return "unwritable";
        if (belowFloor) return REFUSED_FLOOR;
        synchronized (stagingLock) {
            long actions = stagedActions.get();
            if (publishedHighWater.get() + actions + 1 > MAX_SEQUENCE) return REFUSED_SEQUENCE;
            if (diskBytes > diskRoomLocked()) return REFUSED_DISK_FULL;
            if (stagedRetainedBytes.get() + retainedBytes > handOffByteLimit()) return REFUSED_WAITING_ROOM;
            if (actions + 1 > stagingMaxActions) return REFUSED_WAITING_ROOM;
            return null;
        }
    }

    /** D-563 test seam: runs at the start of each {@link #refusal} read (null in production). */
    volatile Runnable refusalSeamForTest;

    /**
     * No-drop item 2: room left in the disk hand-off. Actions here: one lock-free read of the staged count against
     * {@link #stagingMaxActions} (a test seam; no limit in production). Retained bytes: {@link #handOffFreeBytes},
     * under {@code stagingLock}.
     */
    long handOffFreeActions() {
        return Math.max(0L, stagingMaxActions - stagedActions.get());
    }

    /** Q383 A: under {@code stagingLock}, so a committing chunk is not counted both as waiting and as staged. */
    long handOffFreeBytes() {
        Runnable seam = handOffFreeSeamForTest;
        if (seam != null) seam.run();
        synchronized (stagingLock) {
            return Math.max(0L, handOffByteLimit() - stagedRetainedBytes.get());
        }
    }

    /** Q383 A test seam: runs at the start of each {@link #handOffFreeBytes}, before its lock (null in production). */
    volatile Runnable handOffFreeSeamForTest;

    /**
     * Owner Q397 A: the disk room the cap leaves now, in exact disk bytes (never below 0); under {@code stagingLock},
     * as {@link #handOffFreeBytes}.
     */
    long diskFreeBytes() {
        synchronized (stagingLock) {
            return Math.max(0L, diskRoomLocked());
        }
    }

    /**
     * Stages one action behind the waiting ones; a pointer move under the leaf {@code stagingLock}. The caller
     * holds {@code writeLifecycleLock} and has just checked {@link #canAccept}. Returns false when the hand-off no
     * longer has room (never partly staged). Its disk bytes are worked out here (Owner Q397 A).
     */
    boolean stage(List<SchemaV1Row> rows, long retainedBytes, long capturedMillis) {
        return stageOrRefusal(rows, retainedBytes, capturedMillis) == null;
    }

    /**
     * D-563: {@link #stage}, with the reason when the spool no longer has room under the lock
     * ({@link #REFUSED_DISK_FULL} for the disk, {@link #REFUSED_WAITING_ROOM} for the waiting room or the test seam),
     * so the caller counts that reason and never reads {@link #refusal} a second time. null when staged. The action's
     * disk bytes are worked out here, before the lock (Owner Q397 A).
     */
    String stageOrRefusal(List<SchemaV1Row> rows, long retainedBytes, long capturedMillis) {
        return stageOrRefusal(rows, retainedBytes, SpoolFrameCodec.encodedLength(rows), false, 0L, capturedMillis);
    }

    /** Q370 B item 5: the staged entry's smallest row timestamp: the caller's when it read one, else read here. */
    static long stagedMinTimestamp(List<SchemaV1Row> rows, boolean minRowTsRead, long minRowTs) {
        return minRowTsRead ? minRowTs : SpoolFrameCodec.minTimestamp(rows);
    }

    /**
     * {@link #stageOrRefusal(List, long, long)}; Q370 B item 5: when {@code minRowTsRead}, {@code minRowTs} is the
     * rows' smallest timestamp, read by the caller before {@code writeLifecycleLock}; otherwise it is read here.
     * Owner Q397 A: {@code diskBytes} is the rows' exact frame length ({@link SpoolFrameCodec#encodedLength}), worked
     * out by the caller before {@code writeLifecycleLock}; under the lock it is only added and compared. Owner Q405 A
     * (check-q397 MINOR-1): a negative {@code diskBytes} means "not counted" (the caller saw no spool before its lock);
     * the length is then worked out here, before {@code stagingLock}, so no action is ever staged as 0 disk bytes.
     */
    String stageOrRefusal(List<SchemaV1Row> rows, long retainedBytes, long diskBytes, boolean minRowTsRead,
                          long minRowTs, long capturedMillis) {
        if (diskBytes < 0L) diskBytes = SpoolFrameCodec.encodedLength(rows);
        Staged entry = new Staged(rows, retainedBytes, diskBytes, stagedMinTimestamp(rows, minRowTsRead, minRowTs),
                capturedMillis);
        boolean firstUse;
        synchronized (stagingLock) {
            if (diskBytes > diskRoomLocked()) return REFUSED_DISK_FULL;
            if (stagedRetainedBytes.get() + retainedBytes > handOffByteLimit()) return REFUSED_WAITING_ROOM;
            if (staging.size() + 1 > stagingMaxActions) return REFUSED_WAITING_ROOM;
            if (diskFullSeen) diskFullSeen = false;
            if (waitingRoomFullSeen) waitingRoomFullSeen = false;
            firstUse = !latched();
            staging.addLast(entry);
            stagedActions.incrementAndGet();
            stagedRows.addAndGet(rows.size());
            stagedRetainedBytes.addAndGet(retainedBytes);
            stagedDiskBytes.addAndGet(diskBytes);
            stagingLock.notifyAll();
        }
        spooledActions.incrementAndGet();
        spooledRows.addAndGet(rows.size());
        if (firstUse && !firstUseWarned) {
            firstUseWarned = true;
            firstUseLinePending.set(true);
        }
        return null;
    }

    /**
     * S2 (6.8): stages a group saved at stop; waits for staging room until {@code deadlineNanos}. D-563, Owner Q397 A:
     * room is the cap's disk room in exact disk bytes and the hand-off's memory limit (and the test seam), as for
     * {@link #stage}; the disk bytes are worked out here, before the lock.
     */
    boolean stageAtStop(List<SchemaV1Row> rows, long retainedBytes, long deadlineNanos) {
        long diskBytes = SpoolFrameCodec.encodedLength(rows);
        Staged entry = new Staged(rows, retainedBytes, diskBytes, SpoolFrameCodec.minTimestamp(rows),
                settings.clock().getAsLong());
        synchronized (stagingLock) {
            while (true) {
                if (state != State.READY || damagedReason != null || abandoned || closeRequested) return false;
                if (staging.size() + 1 <= stagingMaxActions && diskBytes <= diskRoomLocked()
                        && stagedRetainedBytes.get() + retainedBytes <= handOffByteLimit()) break;
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
            stagedDiskBytes.addAndGet(diskBytes);
            stagingLock.notifyAll();
        }
        savedAtStopActions.incrementAndGet();
        savedAtStopRows.addAndGet(rows.size());
        return true;
    }

    private final AtomicBoolean firstUseLinePending = new AtomicBoolean();

    private final AtomicBoolean fullLinePending = new AtomicBoolean();

    private final AtomicBoolean waitingRoomLinePending = new AtomicBoolean();

    private final AtomicBoolean floorLinePending = new AtomicBoolean();

    private static final int ROOM_DISK = 0;
    private static final int ROOM_WAITING = 1;
    private static final int ROOM_FLOOR = 2;

    /**
     * Under the admission lock: notes (no logging) that the disk part refused this action for room. D-563: given the
     * refusal the caller already has, never a second {@link #refusal} read. Owner Q405 A: only the disk cap's own
     * refusal ({@link #REFUSED_DISK_FULL}) is the disk-full case; the sequence limit is counted with it but does not
     * write its WARN. Owner Q413 (a) A: the floor ({@link #REFUSED_FLOOR}) writes its own WARN ({@link #floorWarn}).
     * A waiting-room refusal has its own ({@link #markWaitingRoomIfFull}).
     */
    void markFullIfFull(String reason) {
        if (REFUSED_DISK_FULL.equals(reason)) noteRoomRefusal(ROOM_DISK);
        else if (REFUSED_FLOOR.equals(reason)) noteRoomRefusal(ROOM_FLOOR);
    }

    /**
     * Owner Q405 A, under the admission lock: notes (no logging) that the waiting room refused this action and it was
     * dropped. Called only where the action is dropped and counted, so the WARN's "New actions are dropped" is true when
     * it fires (an action the waiting room refused that memory still took is not dropped).
     */
    void markWaitingRoomIfFull(String reason) {
        if (REFUSED_WAITING_ROOM.equals(reason)) noteRoomRefusal(ROOM_WAITING);
    }

    /**
     * Under {@code stagingLock}. Q405 re-check MAJOR-A: the FULL flag (disk or waiting room; the floor has none) is set
     * only while the spool holds something, so a drain that emptied it between the refusal and this note never leaves a
     * FULL flag behind (the steps that give room back clear it under the same lock). Owner Q413 (b) A: an empty spool
     * ends the last episode first ({@link #clearFullFlagsIfEmptyLocked}), so a refusal after it is a new episode; its
     * WARN is queued unless this episode already wrote it, or the same WARN was written less than
     * {@link #DROP_WARN_REARM_GAP_MILLIS} ago (a later refusal in the same episode then writes it once the gap is over).
     */
    private void noteRoomRefusal(int room) {
        synchronized (stagingLock) {
            clearFullFlagsIfEmptyLocked();
            boolean holds = waitingFrames.get() != 0 || !staging.isEmpty();
            long now = settings.clock().getAsLong();
            switch (room) {
                case ROOM_DISK -> {
                    if (holds) diskFullSeen = true;
                    if (!fullWarned && gapOver(fullWarnMillis, now)) {
                        fullWarned = true;
                        fullWarnMillis = now;
                        fullLinePending.set(true);
                    }
                }
                case ROOM_WAITING -> {
                    // Check-q397 pass 3 MINOR-1: the waiting room's flag only while actions wait in it; a commit that
                    // emptied it between the refusal and this note (frames still on disk) leaves no FULL behind.
                    if (!staging.isEmpty()) waitingRoomFullSeen = true;
                    if (!waitingRoomWarned && gapOver(waitingRoomWarnMillis, now)) {
                        waitingRoomWarned = true;
                        waitingRoomWarnMillis = now;
                        waitingRoomLinePending.set(true);
                    }
                }
                default -> {
                    if (!floorWarned && gapOver(floorWarnMillis, now)) {
                        floorWarned = true;
                        floorWarnMillis = now;
                        floorWarnUsableBytes = lastUsableBytes;
                        floorLinePending.set(true);
                    }
                }
            }
        }
    }

    /** Owner Q413 (b) A: never written, or written at least the gap ago (a clock that went back counts as over). */
    private static boolean gapOver(long lastMillis, long now) {
        return lastMillis == Long.MIN_VALUE || now < lastMillis || now - lastMillis >= DROP_WARN_REARM_GAP_MILLIS;
    }

    /**
     * Writes the deferred first-use, disk-full, waiting-room and drive-low WARNs outside every lock (called after
     * admission). Q405 re-check NIT-B: each pending flag is taken with getAndSet, so two producers flushing at once
     * print it once.
     */
    void flushAdmissionLines() {
        if (firstUseLinePending.getAndSet(false)) {
            LOGGER.warn("The database writer is full; new actions now wait on disk in the EventTrail spool and will be "
                    + "written when the database catches up.");
        }
        if (fullLinePending.getAndSet(false)) {
            // Owner Q397 A: the cap test's own exact count, in the Owner's words: counted, the cap, on disk (waiting
            // and unreadable), still being written (the hand-off's exact disk bytes). One consistent read.
            long onDisk;
            long beingWritten;
            synchronized (stagingLock) {
                onDisk = waitingBytes.get() + unreadableBytes.get();
                beingWritten = stagedDiskBytes.get();
            }
            LOGGER.warn("{}", fullWarn(onDisk, beingWritten, settings.capBytes()));
        }
        if (waitingRoomLinePending.getAndSet(false)) {
            LOGGER.warn("{}", waitingRoomWarn(handOffByteLimit()));
        }
        if (floorLinePending.getAndSet(false)) {
            LOGGER.warn("{}", floorWarn(floorWarnUsableBytes));
        }
    }

    /**
     * Owner Q397 A: the disk part's figures in MiB, in this order: counted (on disk plus still being written), the cap,
     * on disk (waiting and unreadable), still being written (the hand-off's exact disk bytes). The disk-full WARN and
     * the status line both use it, so their figures match (Owner Q405 A, check-q397 MINOR-2). Owner rule of 4:52 PM
     * 11-25: the used figure (the "N" of "N of M MiB") is rounded down, as are the two parts after it; when the part
     * shows FULL ({@code full}), N is its limit, so a full spool reads "512 of 512 MiB" ({@link #usedMib}).
     */
    static String diskFigures(long onDiskBytes, long beingWrittenBytes, long capBytes, boolean full) {
        return usedMib(onDiskBytes + beingWrittenBytes, capBytes, full) + " of " + capBytes / MIB + " MiB: "
                + onDiskBytes / MIB + " MiB on disk, " + beingWrittenBytes / MIB + " MiB still being written";
    }

    /**
     * Owner rule of 4:52 PM 11-25: a part's used figure in whole MiB, rounded down; a part shown FULL prints its limit.
     * D-655 (check-q397 pass 3 MINOR-6 (ii)): when the counted bytes are above the limit (a carried-in spool file after
     * the limit was lowered between restarts; SpoolFile keeps it whole), FULL prints the larger of the two, so the
     * parts after it never add up to more than N.
     */
    static long usedMib(long usedBytes, long limitBytes, boolean full) {
        long down = usedBytes / MIB;
        return full ? Math.max(limitBytes / MIB, down) : down;
    }

    /**
     * Owner Q413 (a) A: the drive-low WARN, in the Owner's OK'd words; its figure is the drive's measured free space,
     * rounded down, so it never reads as the floor itself.
     */
    static String floorWarn(long usableBytes) {
        return "EventTrail's disk spool has stopped taking new actions because the drive it is on has less than "
                + FLOOR_BYTES / MIB + " MiB free (" + Math.max(0L, usableBytes) / MIB + " MiB free). New actions are"
                + " held in memory while there is room, then dropped and counted. See /eventtrail status.";
    }

    /**
     * Owner Q397 A: the full WARN, in the Owner's words, with its figures ({@link #diskFigures}). Owner Q405 A: written
     * only for the disk cap's own refusal.
     */
    static String fullWarn(long onDiskBytes, long beingWrittenBytes, long capBytes) {
        return "EventTrail's disk spool is full (" + diskFigures(onDiskBytes, beingWrittenBytes, capBytes, true)
                + "). New actions are held in memory while there is room, then dropped and counted."
                + " See /eventtrail status.";
    }

    /**
     * Owner Q405 A: the waiting-room WARN, in the Owner's words byte for byte; its one figure is the waiting room's
     * memory limit in MiB ({@link #handOffByteLimit}), the same figure the status line shows.
     */
    static String waitingRoomWarn(long waitingRoomBytes) {
        return "EventTrail is receiving actions faster than it can write them to disk, and the " + waitingRoomBytes / MIB
                + " MiB of memory set aside for them is full. New actions are dropped and counted until it catches up."
                + " See /eventtrail status.";
    }

    // ---- the spool thread (design-02 4.4) -------------------------------------------------------------------

    private void wake() {
        synchronized (stagingLock) { stagingLock.notifyAll(); }
    }

    private void loop() {
        while (true) {
            synchronized (stagingLock) {
                // No-drop item 2: while the position check waits (not settled), staged actions stay staged: a frame
                // committed before the check settles would be counted as carried in (markSettled), so the spool
                // thread waits for the settle (markSettled wakes it) or the close (which commits them).
                while ((staging.isEmpty() || !positionSettled) && tasks.isEmpty() && unreadableMarks.isEmpty()
                        && !closeRequested && !deleteDue() && !shrinkDue) {
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
            if (!staging.isEmpty() && positionSettled) commitBatch();
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
        lastUsableBytes = usable;
        belowFloor = usable < FLOOR_BYTES;
        nextFloorProbeMillis = settings.clock().getAsLong() + FLOOR_PROBE_MILLIS;
    }

    /**
     * design-02 4.4 steps 2 to 6. No-drop item 2: at most {@link #COMMIT_CHUNK_ACTIONS} staged actions per
     * transaction, oldest first, so hand-off room comes back after each chunk rather than after the whole backlog.
     */
    private void commitBatch() {
        List<Staged> batch;
        synchronized (stagingLock) {
            batch = new ArrayList<>(Math.min(staging.size(), COMMIT_CHUNK_ACTIONS));
            for (Staged entry : staging) {
                if (batch.size() >= COMMIT_CHUNK_ACTIONS) break;
                batch.add(entry);
            }
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
        committedThisRunRows.addAndGet(rowsOf(stored));
        if (wasEmpty && !stored.isEmpty()) oldestAppendedMillis = appendedAt;
        // Q383 A (the Q379 double count): the chunk moves from staged to waiting in ONE step under stagingLock: the
        // waiting mirrors, the published high-water and the staged decrements. The cap test (refusal,
        // handOffFreeBytes, stageOrRefusal) reads under the same lock, so it never sees the chunk counted both as
        // waiting and as staged; before Q383 that moment answered a spurious "full" (an action then overtook the
        // waiting ones by row 4, or was dropped with memory full). Waiting still moves before staged, and latched()
        // reads staged first, so the lock-free latch never reads 0 across the step. No I/O here: refreshMirrors reads
        // the file's in-memory meta (SpoolFile.meta() returns a field); the insert above has already committed.
        synchronized (stagingLock) {
            refreshMirrors();
            publishedHighWater.set(newHigh);
            Runnable between = betweenCommitMirrorsForTest;
            if (between != null) between.run();
            removeStagedLocked(batch, false);
        }
        if (codecFailed > 0) countCouldNotStore(codecFailed, codecFailedRows, "encode-failed", AFTER_CONTINUES);
    }

    /**
     * Check finding (B), Q383 A test seam: runs on the spool thread inside the commit's one {@code stagingLock} step,
     * after the mirror update and before the staged entries leave (null in production). Other threads cannot read the
     * mirrors' cap test until the step ends.
     */
    volatile Runnable betweenCommitMirrorsForTest;

    /** A drop: the batch leaves staging without reaching the file, so both parts get room back. */
    private void removeStaged(List<Staged> batch) {
        synchronized (stagingLock) {
            removeStagedLocked(batch, true);
        }
    }

    /**
     * Under {@code stagingLock}: the batch, the oldest staged entries, leaves staging; no I/O. Q405 re-check MAJOR-A:
     * the waiting room always gets room back here, so its FULL flag ends; the disk part only when {@code freedDisk} (a
     * drop; a commit moves the bytes to the file, no room back); both end when the spool is empty.
     */
    private void removeStagedLocked(List<Staged> batch, boolean freedDisk) {
        for (int i = 0; i < batch.size(); i++) {
            Staged head = staging.pollFirst();
            if (head != batch.get(i)) throw new IllegalStateException("EventTrail spool staging has a second consumer");
            stagedActions.decrementAndGet();
            stagedRows.addAndGet(-head.rows().size());
            stagedRetainedBytes.addAndGet(-head.retainedBytes());
            stagedDiskBytes.addAndGet(-head.diskBytes());
        }
        if (!batch.isEmpty()) {
            waitingRoomFullSeen = false;
            if (freedDisk) diskFullSeen = false;
        }
        clearFullFlagsIfEmptyLocked();
        stagingLock.notifyAll();
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

    /**
     * Spool thread. Q383 A: under {@code stagingLock} (a leaf; re-entered from the commit step), so the cap test reads
     * waiting and unreadable as one set. No I/O: the meta is the file's in-memory copy.
     */
    private void refreshMirrors() {
        SpoolFile f = file;
        if (f == null) return;
        SpoolFile.Meta meta = f.meta();
        synchronized (stagingLock) {
            long onDiskBefore = waitingBytes.get() + unreadableBytes.get();
            waitingFrames.set(meta.waitingFrames());
            waitingRows.set(meta.waitingRows());
            waitingBytes.set(meta.waitingBytes());
            unreadableFrames.set(meta.unreadableFrames());
            unreadableRows.set(meta.unreadableRows());
            unreadableBytes.set(meta.unreadableBytes());
            // Q405 re-check MAJOR-A: a delete gave the disk part room back, so its FULL flag ends here.
            if (meta.waitingBytes() + meta.unreadableBytes() < onDiskBefore) diskFullSeen = false;
            clearFullFlagsIfEmptyLocked();
        }
    }

    /**
     * Q405 re-check MAJOR-A, under {@code stagingLock}: nothing waits and nothing is staged, so neither part is full;
     * both FULL flags end (a later action goes straight to memory and would never reach the stage that clears them).
     * Owner Q413 (b) A: the spool is empty again, so each drop WARN re-arms for the next episode.
     */
    private void clearFullFlagsIfEmptyLocked() {
        if (waitingFrames.get() == 0 && staging.isEmpty()) {
            diskFullSeen = false;
            waitingRoomFullSeen = false;
            fullWarned = false;
            waitingRoomWarned = false;
            floorWarned = false;
        }
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
        // No-drop item 2: commits are chunked, so the bound covers a full hand-off in chunks, plus the old 64 rounds.
        // D-563: the hand-off has no fixed size now, so the rounds follow what is staged at the close (nothing is
        // staged after the close is requested: stage is refused and stageAtStop gives up).
        int rounds = closeCommitRounds(stagedActions.get());
        int guard = 0;
        while (!staging.isEmpty() && damagedReason == null && guard++ < rounds) commitBatch();
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
                      long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting,
                      long stagedRetainedBytes, long refusedBehindRows, long handOffByteLimit,
                      long unreadableBytes, long stagedDiskBytes, boolean diskFullSeen, boolean waitingRoomFullSeen,
                      long refusedFloorRows) {
        /** The shape before Owner Q413 (a) A: no "drive low on space" drops. */
        SpoolStats(State state, String offReason, String damagedReason, String replayStoppedReason,
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
                   long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting,
                   long stagedRetainedBytes, long refusedBehindRows, long handOffByteLimit,
                   long unreadableBytes, long stagedDiskBytes, boolean diskFullSeen, boolean waitingRoomFullSeen) {
            this(state, offReason, damagedReason, replayStoppedReason, replayPaused, settled, storePauseRemainingSeconds,
                    storeFailureReason, capBytes, waitingFrames, waitingRows, waitingBytes, oldestSeconds, stagedActions,
                    stagedRows, spooledRows, writtenFromSpoolRows, carriedInActions, carriedInWrittenActions,
                    carriedInWrittenRows, refusedFullRows, refusedUnwritableRows, refusedOffRows, admittedPastActions,
                    couldNotStoreActions, couldNotStoreRows, unreadableActions, unreadableRowsAsStored, expiredActions,
                    expiredRows, skippedActions, positionBehindLostActions, asideSets, asideBytes, asideActions,
                    asideUnknownSets, heldSets, heldActions, heldUnknownSets, savedAtStopActions, waitingThisRunRows,
                    unresolvedThisRunRows, disabledWaiting, stagedRetainedBytes, refusedBehindRows, handOffByteLimit,
                    unreadableBytes, stagedDiskBytes, diskFullSeen, waitingRoomFullSeen, 0L);
        }

        /**
         * The shape before Owner Q405 A: no unreadable or staged disk bytes, and no refusal seen (the status then reads
         * FULL from the figures alone).
         */
        SpoolStats(State state, String offReason, String damagedReason, String replayStoppedReason,
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
                   long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting,
                   long stagedRetainedBytes, long refusedBehindRows, long handOffByteLimit) {
            this(state, offReason, damagedReason, replayStoppedReason, replayPaused, settled, storePauseRemainingSeconds,
                    storeFailureReason, capBytes, waitingFrames, waitingRows, waitingBytes, oldestSeconds, stagedActions,
                    stagedRows, spooledRows, writtenFromSpoolRows, carriedInActions, carriedInWrittenActions,
                    carriedInWrittenRows, refusedFullRows, refusedUnwritableRows, refusedOffRows, admittedPastActions,
                    couldNotStoreActions, couldNotStoreRows, unreadableActions, unreadableRowsAsStored, expiredActions,
                    expiredRows, skippedActions, positionBehindLostActions, asideSets, asideBytes, asideActions,
                    asideUnknownSets, heldSets, heldActions, heldUnknownSets, savedAtStopActions, waitingThisRunRows,
                    unresolvedThisRunRows, disabledWaiting, stagedRetainedBytes, refusedBehindRows, handOffByteLimit,
                    0L, 0L, false, false);
        }

        /** The shape before D-563: the hand-off's byte limit read as the cap's, with nothing waiting on disk. */
        SpoolStats(State state, String offReason, String damagedReason, String replayStoppedReason,
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
                   long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting,
                   long stagedRetainedBytes, long refusedBehindRows) {
            this(state, offReason, damagedReason, replayStoppedReason, replayPaused, settled, storePauseRemainingSeconds,
                    storeFailureReason, capBytes, waitingFrames, waitingRows, waitingBytes, oldestSeconds, stagedActions,
                    stagedRows, spooledRows, writtenFromSpoolRows, carriedInActions, carriedInWrittenActions,
                    carriedInWrittenRows, refusedFullRows, refusedUnwritableRows, refusedOffRows, admittedPastActions,
                    couldNotStoreActions, couldNotStoreRows, unreadableActions, unreadableRowsAsStored, expiredActions,
                    expiredRows, skippedActions, positionBehindLostActions, asideSets, asideBytes, asideActions,
                    asideUnknownSets, heldSets, heldActions, heldUnknownSets, savedAtStopActions, waitingThisRunRows,
                    unresolvedThisRunRows, disabledWaiting, stagedRetainedBytes, refusedBehindRows,
                    handOffMaxBytes(capBytes));
        }

        /** The shape before no-drop item 2: an empty hand-off byte count and no "behind" drops. */
        SpoolStats(State state, String offReason, String damagedReason, String replayStoppedReason,
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
                   long waitingThisRunRows, long unresolvedThisRunRows, long disabledWaiting) {
            this(state, offReason, damagedReason, replayStoppedReason, replayPaused, settled, storePauseRemainingSeconds,
                    storeFailureReason, capBytes, waitingFrames, waitingRows, waitingBytes, oldestSeconds, stagedActions,
                    stagedRows, spooledRows, writtenFromSpoolRows, carriedInActions, carriedInWrittenActions,
                    carriedInWrittenRows, refusedFullRows, refusedUnwritableRows, refusedOffRows, admittedPastActions,
                    couldNotStoreActions, couldNotStoreRows, unreadableActions, unreadableRowsAsStored, expiredActions,
                    expiredRows, skippedActions, positionBehindLostActions, asideSets, asideBytes, asideActions,
                    asideUnknownSets, heldSets, heldActions, heldUnknownSets, savedAtStopActions, waitingThisRunRows,
                    unresolvedThisRunRows, disabledWaiting, 0L, 0L, handOffMaxBytes(capBytes));
        }
    }

    volatile long disabledWaiting;

    SpoolStats stats() {
        long now = settings.clock().getAsLong();
        long oldest = oldestAppendedMillis;
        long committed = committedThisRunRows.get();
        long resolvedThisRun = writtenFromSpoolRows.get() + unreadableThisRunRows.get() + expiredThisRunRows.get()
                + poisonThisRunRows.get();
        // Owner Q405 A (check-q397 MINOR-2): the disk part's figures and the waiting room's, read in one stagingLock
        // hold, as the disk-full WARN reads them, so the status shows the same counted figure.
        long waitingNow;
        long unreadableNow;
        long stagedDiskNow;
        long stagedActionsNow;
        long stagedRetainedNow;
        long waitingFramesNow;
        boolean diskFullNow;
        boolean waitingRoomFullNow;
        synchronized (stagingLock) {
            waitingNow = waitingBytes.get();
            unreadableNow = unreadableBytes.get();
            stagedDiskNow = stagedDiskBytes.get();
            stagedActionsNow = stagedActions.get();
            stagedRetainedNow = stagedRetainedBytes.get();
            // Q405 re-check NIT-C: the waiting count in the same hold, so a committing chunk is never counted twice;
            // MAJOR-A: the FULL flags too, so they agree with the figures beside them.
            waitingFramesNow = waitingFrames.get();
            diskFullNow = diskFullSeen;
            waitingRoomFullNow = waitingRoomFullSeen;
        }
        return new SpoolStats(state, offReason, damagedReason, replayStoppedReason, replayPaused, positionSettled,
                Math.max(0L, (storePauseUntilMillis - now + 999L) / 1000L), storeFailureReason, settings.capBytes(),
                waitingFramesNow, waitingRows.get(), waitingNow,
                oldest < 0 ? -1L : Math.max(0L, (now - oldest) / 1000L), stagedActionsNow, stagedRows.get(),
                spooledRows.get(), writtenFromSpoolRows.get(), carriedInActions.get(), carriedInWrittenActions.get(),
                carriedInWrittenRows.get(), refusedFullRows.get(), refusedUnwritableRows.get(), refusedOffRows.get(),
                admittedPastActions.get(), couldNotStoreActions.get(), couldNotStoreRows.get(), unreadableActions.get(),
                unreadableRowsAsStored.get(), expiredActions.get(), expiredRows.get(), skippedPastPositionActions.get(),
                positionBehindLostActions.get(), asideSets, asideBytes, asideActions, asideUnknownSets, heldSets,
                heldActions, heldUnknownSets, savedAtStopActions.get(), Math.max(0L, committed - resolvedThisRun),
                unreadableThisRunRows.get() + expiredThisRunRows.get(), disabledWaiting, stagedRetainedNow,
                refusedBehindRows.get(), handOffByteLimit(), unreadableNow, stagedDiskNow, diskFullNow,
                waitingRoomFullNow, refusedFloorRows.get());
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
                            + " actions wait, new actions wait "
                            // NIT-3 (own check 1): a paused replay still writes new actions to disk; while the
                            // position check waits they stay in the in-memory hand-off, then go to memory.
                            + (s.settled() ? "on disk behind them"
                            : "behind them (in the hand-off to disk, then in memory)"));
                } else if (s.storePauseRemainingSeconds() > 0) {
                    lines.add("Disk spool: not accepting for " + s.storePauseRemainingSeconds()
                            + "s (could not store: " + s.storeFailureReason() + ")");
                } else if (diskPartFull(s)) {
                    // Owner Q405 A (check-q397 MINOR-2): the disk-full WARN's own figures (diskFigures), counted the
                    // same way, so the two match. Owner Q412 A: the wording of owner-texts-q405.md item c.
                    lines.add("Disk spool: FULL (" + diskFigures(onDisk(s), s.stagedDiskBytes(), s.capBytes(), true)
                            + ") - new actions are held in memory while there is room, then dropped and counted");
                } else if (s.waitingFrames() + s.stagedActions() == 0 && s.unreadableBytes() == 0L) {
                    lines.add("Disk spool: ready, empty (limit " + limit + " MiB)");
                } else {
                    lines.add("Disk spool: in use - " + count(s.waitingFrames() + s.stagedActions())
                            + " actions waiting (" + diskFigures(onDisk(s), s.stagedDiskBytes(), s.capBytes(), false)
                            + ")"
                            + (s.oldestSeconds() >= 0 ? ", oldest " + s.oldestSeconds() + "s" : ""));
                }
            }
        }
        boolean waitingRoomFull = waitingRoomFull(s);
        if (s.state() == State.READY && (s.stagedActions() > 0 || waitingRoomFull)) {
            // No-drop item 2: the in-memory hand-off to the disk, shown apart from the memory queue and the disk.
            // Owner Q405 A: the waiting room, used / its memory limit (the figure the waiting-room WARN names); the
            // action count has no bound. Owner rule of 4:52 PM 11-25: the used figure rounded down; FULL prints the
            // limit, so a full room reads "416 of 416" (usedMib).
            String figures = usedMib(s.stagedRetainedBytes(), s.handOffByteLimit(), waitingRoomFull) + " of "
                    + (s.handOffByteLimit() / MIB) + " MiB, "
                    + count(s.stagedActions()) + " actions";
            lines.add(waitingRoomFull
                    ? "Memory for actions waiting to be written to disk: FULL (" + figures
                            + ") - new actions are dropped and counted until the disk catches up"
                    : "Memory for actions waiting to be written to disk: " + figures);
        }
        lines.add("Spooled / written from the spool since startup: " + count(s.spooledRows()) + " / "
                + count(s.writtenFromSpoolRows() + s.carriedInWrittenRows()) + " rows; carried over from an earlier run: "
                + count(s.carriedInActions()) + " actions");
        if (s.refusedFullRows() + s.refusedBehindRows() + s.refusedFloorRows() + s.refusedUnwritableRows()
                + s.refusedOffRows() > 0) {
            // Owner Q405 A: the waiting room's drops ("behind"), named only when not 0. Owner Q413 (a) A: the floor's
            // drops ("drive low on space") likewise, after it.
            List<String> names = new ArrayList<>();
            List<String> figures = new ArrayList<>();
            names.add("disk spool full");
            figures.add(count(s.refusedFullRows()));
            if (s.refusedBehindRows() > 0) {
                names.add("waiting memory full");
                figures.add(count(s.refusedBehindRows()));
            }
            if (s.refusedFloorRows() > 0) {
                names.add("drive low on space");
                figures.add(count(s.refusedFloorRows()));
            }
            names.add("spool unwritable");
            figures.add(count(s.refusedUnwritableRows()));
            names.add("spool off");
            figures.add(count(s.refusedOffRows()));
            lines.add("Dropped (not stored): " + String.join(" / ", names) + ": " + String.join(" / ", figures)
                    + " rows");
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

    /** The disk part's bytes on disk: waiting and unreadable (Owner Q397 A). */
    private static long onDisk(SpoolStats s) {
        return s.waitingBytes() + s.unreadableBytes();
    }

    /**
     * Owner Q405 A (check-q397 MINOR-2): the disk part is full when the last room refusal counted was the disk's, or
     * when not even the smallest action's frame ({@link SpoolFrameCodec#ROW_FIXED_BYTES}) fits the counted figure.
     */
    static boolean diskPartFull(SpoolStats s) {
        return s.diskFullSeen()
                || onDisk(s) + s.stagedDiskBytes() + SpoolFrameCodec.ROW_FIXED_BYTES > s.capBytes();
    }

    /**
     * Owner Q405 A: the waiting room is full when the last room refusal counted was the waiting room's, or when not even
     * the smallest action ({@link #MIN_ACTION_RETAINED_BYTES}) fits its memory limit.
     */
    static boolean waitingRoomFull(SpoolStats s) {
        return s.waitingRoomFullSeen()
                || s.stagedRetainedBytes() + MIN_ACTION_RETAINED_BYTES > s.handOffByteLimit();
    }

    /**
     * Owner Q405 A: the status lines with, right after the first line, the line saying the waiting room was lowered at
     * startup and why, so admins who do not read logs see it. Without a lowering, the lines of
     * {@link #statusLines(SpoolStats)}.
     */
    static List<String> statusLines(SpoolStats s, Settings settings) {
        List<String> lines = statusLines(s);
        // A disabled spool uses no memory, so nothing was lowered for it.
        String lowered = settings == null || s.state() == State.DISABLED ? null : waitingRoomLoweredStatusLine(settings);
        if (lowered == null) return lines;
        List<String> withLowered = new ArrayList<>(lines);
        withLowered.add(Math.min(1, withLowered.size()), lowered);
        return List.copyOf(withLowered);
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
