package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.core.ExplosionPartLossCounter;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Explosion split (Owner decision B; explosion-split-design-02 2.1-2.3 with Advisor M-2 to M-5): offers the parts of
 * explosions stored in parts to storage, in order, one part per offer, over as many server ticks as the writer needs.
 *
 * <ul>
 *   <li>One FIFO backlog of waiting parts, bounded by {@link ExplosionSplitLimits#BACKLOG_MAX_ROWS} rows and
 *       {@link ExplosionSplitLimits#BACKLOG_MAX_BYTES}; over a bound, the ending explosion's parts that do not fit
 *       are dropped and counted.</li>
 *   <li>Each part is offered through the non-counting admission ({@code tryLogAllWhenRoom}) with the headroom
 *       ({@link ExplosionSplitLimits#HEADROOM_ROWS}, {@link ExplosionSplitLimits#HEADROOM_BYTES}). ADMITTED: next part.
 *       NO_ROOM: the part keeps its place until the next tick; nothing is counted. REFUSED, or a throw: that part and
 *       every later part of its explosion are dropped and counted, and are never offered again (F12).</li>
 *   <li>Age is counted in drain passes (server ticks), never wall-clock time (M-4): an explosion's untried parts are
 *       dropped for age only when a NO_ROOM answer comes at or past {@link ExplosionSplitLimits#BACKLOG_MAX_AGE_TICKS}
 *       ticks after it ended.</li>
 *   <li>The backlog belongs to ONE server run (M-2): {@link #beginRun} at server start, {@link #stopPass} at server
 *       stop before EventTrail's own shutdown, {@link #endRun} at ServerStopped. Parts left at stop, at
 *       ServerStopped or at the next start are dropped and counted, never offered to another run's database; parts
 *       of an explosion that ends while no run is open are dropped and counted too.</li>
 *   <li>Counting (condition 4): every part not stored is counted exactly once, under one cause, in
 *       {@link ExplosionPartLossCounter} (the coordinator's figure, shown by status and the stop line). The first loss
 *       of a server run writes one WARN; an explosion of which no part was stored gets its own rate-limited WARN
 *       (Advisor m-2).</li>
 *   <li>{@link #tick} catches every Throwable except a VirtualMachineError, so the drain never throws into the game's
 *       tick loop (M-5 a).</li>
 * </ul>
 *
 * <p>Owned by the server thread; every method is synchronized for an unusual off-thread finalization. The stop pass
 * releases the lock while it waits.</p>
 */
public final class ExplosionPartPublisher {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final SecureRandom EXPLOSION_IDS = new SecureRandom();
    /** How often the stop pass asks again while the writer has no room. */
    static final long STOP_POLL_MILLIS = 20L;

    /** One part offer; production: the runtime's non-counting admission. */
    @FunctionalInterface
    public interface PartSink {
        ILogStorage.OfferResult offer(List<AuditRecord> rows, long retainedBytes, int headroomRows, long headroomBytes);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** The bounds, from {@link ExplosionSplitLimits} in production; smaller in tests. */
    record Limits(int backlogMaxRows, long backlogMaxBytes, long maxAgeTicks, int headroomRows, long headroomBytes,
                  long stopWaitMillis) {
        static Limits production() {
            return new Limits(ExplosionSplitLimits.BACKLOG_MAX_ROWS, ExplosionSplitLimits.BACKLOG_MAX_BYTES,
                    ExplosionSplitLimits.BACKLOG_MAX_AGE_TICKS, ExplosionSplitLimits.HEADROOM_ROWS,
                    ExplosionSplitLimits.HEADROOM_BYTES, ExplosionSplitLimits.STOP_WAIT_MILLIS);
        }
    }

    private static final class ProductionHolder {
        private static final ExplosionPartPublisher INSTANCE = new ExplosionPartPublisher(
                (rows, retainedBytes, headroomRows, headroomBytes) -> EventTrailManager.getInstance()
                        .tryLogAllWhenRoom(rows, retainedBytes, headroomRows, headroomBytes),
                EventTrailManager.getInstance().explosionPartLosses(),
                Limits.production(),
                System::nanoTime,
                Thread::sleep,
                LOGGER::warn);
    }

    private final PartSink sink;
    private final ExplosionPartLossCounter losses;
    private final Limits limits;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final Consumer<String> warnings;
    private final RateLimitedDiagnosticGate nothingStoredWarnings = new RateLimitedDiagnosticGate(60_000);

    private final ArrayDeque<Entry> backlog = new ArrayDeque<>();
    private long backlogRows;
    private long backlogBytes;
    private boolean open;
    private long ticks;
    private boolean warnedThisRun;

    ExplosionPartPublisher(
            PartSink sink,
            ExplosionPartLossCounter losses,
            Limits limits,
            LongSupplier nanoClock,
            Sleeper sleeper,
            Consumer<String> warnings) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.losses = Objects.requireNonNull(losses, "losses");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** The server's publisher (one per process; its backlog belongs to one server run at a time). */
    public static ExplosionPartPublisher production() {
        return ProductionHolder.INSTANCE;
    }

    /** 64 random bits naming one explosion; from a source that is not seeded the same way twice (Advisor answer 5). */
    public static long newExplosionId() {
        return EXPLOSION_IDS.nextLong();
    }

    /**
     * Server start (M-2): parts still waiting from an earlier run (its stop paths did not run) are dropped and
     * counted, never offered to this run's database, with one WARN; then this run accepts parts.
     */
    public synchronized void beginRun() {
        guarded(() -> {
            Totals leftover = dropAllLocked(ExplosionPartLossCounter.Cause.STOP, false);
            if (leftover.parts > 0) {
                warn("EventTrail dropped " + leftover.parts + " explosion part(s) (" + leftover.blockRows
                        + " block row(s)) of " + leftover.explosions + " explosion(s) still waiting from the previous"
                        + " server run; they were never offered to this run's database. Lookups show those"
                        + " explosions INCOMPLETE.");
            }
        });
        warnedThisRun = false;
        ticks = 0L;
        open = true;
    }

    /**
     * One server tick (ServerTickEvent.Post): counts the drain pass and offers waiting parts. Never throws into the
     * tick except a VirtualMachineError (M-5 a).
     */
    public synchronized void tick() {
        if (!open) return;
        ticks++;
        guarded(this::drainLocked);
    }

    /**
     * Takes the parts of one ended explosion (built and validated by ExplosionAuditSession#planParts) into the
     * backlog, in order, and offers them at once. The rows the explosion budget left out are counted here. Parts that
     * do not fit the backlog's bounds are dropped and counted; with no run open, all are. Called inside the mixin's
     * finishSession guard; never re-offers anything.
     */
    public synchronized void submit(ExplosionParts explosion) {
        Objects.requireNonNull(explosion, "explosion");
        losses.countLeftOutByBudget(explosion.leftOutBySize());
        Entry entry = new Entry(explosion, ticks);
        if (!open) {
            loseLocked(entry, ExplosionPartLossCounter.Cause.STOP, 0, explosion.parts().size(), false);
            finishLocked(entry);
            return;
        }
        if (!backlog.isEmpty()) {
            try {
                guarded(this::drainLocked);
            } catch (VirtualMachineError fatal) {
                // Review M-A (2): an earlier explosion's offer raised it before this explosion reached the backlog;
                // this explosion's parts are counted (never offered), then the error propagates.
                try {
                    loseLocked(entry, ExplosionPartLossCounter.Cause.STOP, 0, explosion.parts().size(), false, false);
                } catch (Throwable ignored) {
                    // Counting is best effort while a VirtualMachineError unwinds.
                }
                throw fatal;
            }
        }
        int accepted = 0;
        long rows = backlogRows;
        long bytes = backlogBytes;
        for (ExplosionParts.Part part : explosion.parts()) {
            if (rows + part.rows().size() > limits.backlogMaxRows()
                    || bytes + part.retainedBytes() > limits.backlogMaxBytes()) {
                break;
            }
            rows += part.rows().size();
            bytes += part.retainedBytes();
            accepted++;
        }
        entry.limit = accepted;
        if (accepted < explosion.parts().size()) {
            loseLocked(entry, ExplosionPartLossCounter.Cause.BACKLOG_CAP, accepted, explosion.parts().size(), false);
        }
        if (accepted == 0) {
            finishLocked(entry);
            return;
        }
        backlogRows = rows;
        backlogBytes = bytes;
        backlog.addLast(entry);
        guarded(this::drainLocked);
    }

    /**
     * Review M-A (1): an explosion to be stored in parts whose parts could not be built (ExplosionAuditSession#planParts
     * threw), so none of its {@code listedRows} rows will be stored: counted once, as one BUILD_FAILED part. Adds to
     * the counter's adders only; the caller writes its own not-recorded WARN.
     */
    public void countUnbuilt(long listedRows) {
        losses.countExplosion();
        losses.countParts(ExplosionPartLossCounter.Cause.BUILD_FAILED, 1, listedRows);
    }

    /**
     * Server stopping, before EventTrail's own shutdown (M-2; Owner decision 2): keeps offering parts for up to
     * {@link ExplosionSplitLimits#STOP_WAIT_MILLIS} while the writer has no room, then drops and counts every part
     * still waiting, so the coordinator's stop line includes them. This run then accepts no more parts.
     */
    public void stopPass() {
        long waitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, limits.stopWaitMillis()));
        long started = nanoClock.getAsLong();
        while (true) {
            synchronized (this) {
                if (!open || backlog.isEmpty()) break;
                guarded(this::drainLocked);
                if (backlog.isEmpty()) break;
            }
            if (nanoClock.getAsLong() - started >= waitNanos) break;
            try {
                sleeper.sleep(STOP_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (this) {
            guarded(() -> dropAllLocked(ExplosionPartLossCounter.Cause.STOP, true));
            open = false;
        }
    }

    /** ServerStopped (M-2): anything still waiting (an abnormal stop skipped the stop pass) is dropped and counted. */
    public synchronized void endRun() {
        guarded(() -> dropAllLocked(ExplosionPartLossCounter.Cause.STOP, true));
        open = false;
    }

    synchronized boolean isOpen() {
        return open;
    }

    synchronized int waitingParts() {
        int count = 0;
        for (Entry entry : backlog) count += entry.limit - entry.next;
        return count;
    }

    synchronized long waitingRows() {
        return backlogRows;
    }

    synchronized long ticks() {
        return ticks;
    }

    private void drainLocked() {
        while (!backlog.isEmpty()) {
            Entry entry = backlog.peekFirst();
            int at = entry.next;
            ExplosionParts.Part part = entry.parts[at];
            ILogStorage.OfferResult result;
            try {
                result = sink.offer(part.rows(), part.retainedBytes(), limits.headroomRows(), limits.headroomBytes());
                if (result == null) result = ILogStorage.OfferResult.REFUSED;
            } catch (VirtualMachineError fatal) {
                // Never re-offered: this part and the rest of its explosion are counted, then the error propagates.
                try {
                    refuseLocked(entry, at);
                } catch (Throwable ignored) {
                    // Counting is best effort while a VirtualMachineError unwinds.
                }
                throw fatal;
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                LOGGER.debug("EventTrail explosion part offer failed ({})",
                        EventTrailDiagnostics.safeFailureSummary(failure));
                result = ILogStorage.OfferResult.REFUSED;
            }
            switch (result) {
                case ADMITTED -> {
                    entry.next = at + 1;
                    entry.admitted++;
                    release(part);
                    // Review N-2: the writer has the part's rows now; the publisher no longer holds them.
                    entry.parts[at] = null;
                    if (entry.next >= entry.limit) {
                        backlog.pollFirst();
                        finishLocked(entry);
                    }
                }
                case NO_ROOM -> {
                    if (ticks - entry.enqueuedAtTick >= limits.maxAgeTicks()) {
                        loseLocked(entry, ExplosionPartLossCounter.Cause.AGE, at, entry.limit, true);
                        entry.next = entry.limit;
                        backlog.pollFirst();
                        finishLocked(entry);
                    } else {
                        return;
                    }
                }
                case REFUSED -> refuseLocked(entry, at);
            }
        }
    }

    /** The part at {@code at} was refused (or its offer threw): it and every later part are dropped, never re-offered. */
    private void refuseLocked(Entry entry, int at) {
        loseLocked(entry, ExplosionPartLossCounter.Cause.REFUSED, at, at + 1, true);
        if (at + 1 < entry.limit) {
            loseLocked(entry, ExplosionPartLossCounter.Cause.AFTER_REFUSAL, at + 1, entry.limit, true);
        }
        entry.next = entry.limit;
        if (backlog.peekFirst() == entry) backlog.pollFirst();
        finishLocked(entry);
    }

    /** Drops every waiting part of every explosion, counted under {@code cause}. */
    private Totals dropAllLocked(ExplosionPartLossCounter.Cause cause, boolean warnFirstLoss) {
        Totals totals = new Totals();
        while (!backlog.isEmpty()) {
            Entry entry = backlog.pollFirst();
            if (entry.next < entry.limit) {
                totals.explosions++;
                totals.parts += entry.limit - entry.next;
                totals.blockRows += blockRows(entry, entry.next, entry.limit);
                loseLocked(entry, cause, entry.next, entry.limit, true, warnFirstLoss);
                entry.next = entry.limit;
            }
            finishLocked(entry);
        }
        backlogRows = 0L;
        backlogBytes = 0L;
        return totals;
    }

    private void loseLocked(Entry entry, ExplosionPartLossCounter.Cause cause, int from, int to, boolean inBacklog) {
        loseLocked(entry, cause, from, to, inBacklog, true);
    }

    /**
     * Counts parts [from, to) of {@code entry} as not stored for {@code cause}: once per part, the explosion once.
     * Parts still in the backlog leave its bounds.
     */
    private void loseLocked(Entry entry, ExplosionPartLossCounter.Cause cause, int from, int to,
                            boolean inBacklog, boolean warnFirstLoss) {
        if (to <= from) return;
        long rows = blockRows(entry, from, to);
        losses.countParts(cause, to - from, rows);
        if (!entry.lossCounted) {
            entry.lossCounted = true;
            losses.countExplosion();
        }
        for (int i = from; i < to; i++) {
            if (inBacklog && entry.parts[i] != null) release(entry.parts[i]);
            // Review N-2: a counted part is never offered, so its rows are not held either.
            entry.parts[i] = null;
        }
        if (warnFirstLoss && !warnedThisRun) {
            warnedThisRun = true;
            warn("EventTrail could not store " + (to - from) + " of the " + entry.parts.length
                    + " part(s) of an explosion in " + entry.dimensionId + " at " + entry.center.toShortString()
                    + " (explosion " + entry.displayId + ", " + rows + " block row(s)): " + cause.text()
                    + ". Lookups show that explosion INCOMPLETE. Further explosion parts not stored in this server run"
                    + " are counted without another warning and reported in /eventtrail status and when the server"
                    + " stops.");
        }
    }

    /** An explosion leaves the publisher; Advisor m-2: one rate-limited WARN when none of its parts was stored. */
    private void finishLocked(Entry entry) {
        if (entry.admitted > 0) return;
        try {
            nothingStoredWarnings.acquire().ifPresent(emission -> warn(
                    "EventTrail stored no part of an explosion in " + entry.dimensionId + " at "
                            + entry.center.toShortString() + " (explosion " + entry.displayId + ", "
                            + entry.parts.length + " part(s), " + entry.listedRows
                            + " block row(s)); none of its rows was written; suppressed "
                            + emission.suppressedSinceLastEmission() + " such warning(s) since the last warning"));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change what was counted.
        }
    }

    private void release(ExplosionParts.Part part) {
        backlogRows = Math.max(0L, backlogRows - part.rows().size());
        backlogBytes = Math.max(0L, backlogBytes - part.retainedBytes());
    }

    private static long blockRows(Entry entry, int from, int to) {
        long rows = 0L;
        for (int i = from; i < to; i++) {
            ExplosionParts.Part part = entry.parts[i];
            if (part != null) rows += part.blockRows();
        }
        return rows;
    }

    private void warn(String message) {
        try {
            warnings.accept(message);
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change what was counted or stored.
        }
    }

    /** Runs {@code work}; contains every Throwable except a VirtualMachineError (M-5 a). */
    private static void guarded(Runnable work) {
        try {
            work.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            try {
                LOGGER.error("EventTrail explosion part publishing failed ({})",
                        EventTrailDiagnostics.safeFailureSummary(failure));
            } catch (Throwable ignored) {
                // Logging is best effort.
            }
        }
    }

    private static final class Entry {
        private final ResourceLocation dimensionId;
        private final BlockPos center;
        private final String displayId;
        private final int listedRows;
        /**
         * The explosion's parts, part i at index i - 1. Review N-2: a slot is cleared once its part is admitted or
         * counted, so the publisher holds only the rows of parts still waiting, never those the writer already has.
         */
        private final ExplosionParts.Part[] parts;
        private final long enqueuedAtTick;
        /** Parts [0, limit) were taken into the backlog; parts from limit on were dropped at submit. */
        private int limit;
        /** The next part to offer. */
        private int next;
        private int admitted;
        private boolean lossCounted;

        private Entry(ExplosionParts explosion, long enqueuedAtTick) {
            this.dimensionId = explosion.dimensionId();
            this.center = explosion.center();
            this.displayId = explosion.displayId();
            this.listedRows = explosion.listedRows();
            this.parts = explosion.parts().toArray(new ExplosionParts.Part[0]);
            this.enqueuedAtTick = enqueuedAtTick;
            this.limit = parts.length;
        }
    }

    private static final class Totals {
        private long explosions;
        private long parts;
        private long blockRows;
    }
}
