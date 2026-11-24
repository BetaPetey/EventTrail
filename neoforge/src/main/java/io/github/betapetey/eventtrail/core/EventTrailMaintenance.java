package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.storage.RetentionPace;
import io.github.betapetey.eventtrail.storage.RetentionResult;
import io.github.betapetey.eventtrail.storage.TableShrink;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One lifecycle-owned retention worker, with cooperative player-priority idle admission. While players are online
 * the same worker deletes expired rows at {@link RetentionPace#GENTLE}; operator purge and shrink requests run on
 * it too, one at a time. The server thread only publishes activity and submits: it never runs SQL or waits.
 * At retention.days = 0 the worker runs no retention pass at all, only those operator requests.
 */
public final class EventTrailMaintenance {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final int MAINTENANCE_WORKERS = 1;
    static final int MAINTENANCE_QUEUE_CAPACITY = 1;
    static final long IDLE_GRACE_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long IDLE_RECHECK_NANOS = TimeUnit.MINUTES.toNanos(30);
    /** While players are online and expired rows may remain: the gap between two gentle passes. */
    static final long BUSY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    enum PassKind { STARTUP, IDLE, BUSY, COMMAND }

    /** How an operator request was taken. */
    public enum Admission { QUEUED, BUSY, UNAVAILABLE }
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final Object LIFECYCLE_LOCK = new Object();

    private static Lifecycle current;
    // At most one predecessor can still own a worker. Pending successor lifetimes allocate no worker.
    private static ThreadPoolExecutor retiredExecutor;
    private static long lifecycleGeneration;
    private static volatile String lastWorkerThreadName;

    private EventTrailMaintenance() {}

    public static boolean start(ILogStorage storage, int retentionDays) {
        return start(storage, retentionDays, false, 0);
    }

    /** Initial player activity is installed before the startup worker can admit its first batch. */
    public static boolean start(ILogStorage storage, int retentionDays, boolean fullyStarted, int recognizedHumanPlayers) {
        return start(storage, retentionDays, fullyStarted, recognizedHumanPlayers,
                System::nanoTime, System::currentTimeMillis);
    }

    static boolean start(ILogStorage storage, int retentionDays, boolean fullyStarted, int recognizedHumanPlayers,
                         LongSupplier nanoClock, LongSupplier wallClock) {
        if (recognizedHumanPlayers < 0) throw new IllegalArgumentException("Recognized human count must not be negative");
        Objects.requireNonNull(nanoClock, "nanoClock");
        Objects.requireNonNull(wallClock, "wallClock");
        // retention.days = 0 (keep forever): the worker runs operator requests only, never a retention pass.
        if (storage == null || retentionDays < 0 || !storage.isReady()) return false;
        synchronized (LIFECYCLE_LOCK) {
            if (current != null) return false;
            reapRetiredLocked();
            Lifecycle created = new Lifecycle(++lifecycleGeneration, storage, retentionDays,
                    fullyStarted, recognizedHumanPlayers, nanoClock, wallClock);
            current = created;
            tryStartPendingLocked(created);
            return true;
        }
    }

    /** Capture once after start; the handle cannot publish into a successor generation. */
    public static Activity activity() {
        synchronized (LIFECYCLE_LOCK) {
            return current == null ? null : new Activity(current.generation);
        }
    }

    /** Cached facts only, bound to the storage instance selected by the caller's current runtime view. */
    public static RetentionSnapshot retentionSnapshot(ILogStorage expectedStorage, int configuredDays) {
        if (configuredDays < 0) throw new IllegalArgumentException("Negative configured retention days");
        synchronized (LIFECYCLE_LOCK) {
            Lifecycle lifecycle = current;
            if (lifecycle == null || lifecycle.storage != expectedStorage) {
                return new RetentionSnapshot(configuredDays, lifecycleGeneration, false, false, null, null, null);
            }
            boolean observed = lifecycle.lastOutcome != null;
            return new RetentionSnapshot(configuredDays, lifecycle.generation, true, lifecycle.passInFlight,
                    observed ? lifecycle.lastCutoffTimestamp : null,
                    observed ? new RetentionResult(lifecycle.lastDeletedCount, lifecycle.lastOutcome) : null,
                    observed ? lifecycle.lastRetentionCompletedAtMillis : null);
        }
    }

    /** A null result/cutoff/time trio means no pass observed, never a successful zero-row pass. */
    public record RetentionSnapshot(int configuredDays, long generation, boolean lifecycleActive, boolean passInFlight,
                                    Long lastCutoffTimestamp, RetentionResult lastResult, Long completedAtMillis) {
        public RetentionSnapshot {
            if (configuredDays < 0 || (lastResult == null) != (lastCutoffTimestamp == null)
                    || (lastResult == null) != (completedAtMillis == null)
                    || completedAtMillis != null && completedAtMillis < 0L) {
                throw new IllegalArgumentException("Inconsistent retention snapshot");
            }
        }
    }

    /** Main-thread activity publication and a constant-time due-work check; neither performs storage I/O. */
    public static final class Activity {
        private final long generation;

        private Activity(long generation) { this.generation = generation; }

        public void update(boolean fullyStarted, int recognizedHumanPlayers) {
            if (recognizedHumanPlayers < 0) throw new IllegalArgumentException("Recognized human count must not be negative");
            synchronized (LIFECYCLE_LOCK) {
                Lifecycle lifecycle = current;
                if (lifecycle == null || lifecycle.generation != generation) return;
                if (lifecycle.recognizedHumanPlayers != recognizedHumanPlayers) lifecycle.activityEpoch++;
                if (lifecycle.fullyStarted && !fullyStarted) lifecycle.activityEpoch++;
                lifecycle.recognizedHumanPlayers = recognizedHumanPlayers;
                lifecycle.fullyStarted = fullyStarted;
                if (!fullyStarted || recognizedHumanPlayers > 0) lifecycle.graceArmed = false;
                armGraceLocked(lifecycle);
            }
        }

        public void tick() {
            synchronized (LIFECYCLE_LOCK) {
                Lifecycle lifecycle = current;
                if (lifecycle == null || lifecycle.generation != generation) return;
                reapRetiredLocked();
                if (!lifecycle.startupDispatched) {
                    tryStartPendingLocked(lifecycle);
                    return;
                }
                // Constant time: the remaining checks read fields only; any work goes to the worker.
                armGraceLocked(lifecycle);
                if (lifecycle.passInFlight) return;
                if (lifecycle.pendingCommand != null) {
                    submitCommandLocked(lifecycle);
                    return;
                }
                if (busyEligibleLocked(lifecycle)) {
                    submitLocked(lifecycle, PassKind.BUSY);
                    return;
                }
                if (!idleEligibleLocked(lifecycle)) return;
                long now = lifecycle.nanoClock.getAsLong();
                if (lifecycle.recheckArmed && now - lifecycle.recheckDueNanos < 0L) return;
                submitLocked(lifecycle, PassKind.IDLE);
            }
        }
    }

    // ---- operator requests (purge and shrink commands) --------------------------------------------------------------

    /** One operator request; {@code done} runs on the retention worker once, with the outcome. */
    private record Command(Kind kind, int days, TableShrink.Plan confirmed, Consumer<Object> done) {
        enum Kind { PURGE, PLAN_SHRINK, SHRINK }
    }

    /** The cleanup now, for history older than {@code days}; at the gentle pace while players are online. */
    public static Admission requestPurge(ILogStorage expectedStorage, int days, Consumer<RetentionResult> done) {
        if (days <= 0) throw new IllegalArgumentException("Purge days must be positive");
        Objects.requireNonNull(done, "done");
        return request(expectedStorage, new Command(Command.Kind.PURGE, days, null, result -> done.accept((RetentionResult) result)));
    }

    /** Measures what a shrink needs; nothing is changed. */
    public static Admission requestShrinkPlan(ILogStorage expectedStorage, Consumer<TableShrink.Plan> done) {
        Objects.requireNonNull(done, "done");
        return request(expectedStorage, new Command(Command.Kind.PLAN_SHRINK, 0, null, plan -> done.accept((TableShrink.Plan) plan)));
    }

    /** Runs a confirmed shrink; the storage measures again first and refuses when the space no longer fits. */
    public static Admission requestShrink(ILogStorage expectedStorage, TableShrink.Plan confirmed,
                                          Consumer<TableShrink.Result> done) {
        Objects.requireNonNull(confirmed, "confirmed");
        Objects.requireNonNull(done, "done");
        return request(expectedStorage, new Command(Command.Kind.SHRINK, 0, confirmed, result -> done.accept((TableShrink.Result) result)));
    }

    /** The running lifecycle's generation, or -1 with none; a confirm from an older generation is void. */
    public static long lifecycleGenerationOrNone() {
        synchronized (LIFECYCLE_LOCK) {
            return current == null ? -1L : current.generation;
        }
    }

    /** True while an operator request waits or runs (status and command replies only). */
    public static boolean operatorRequestPending() {
        synchronized (LIFECYCLE_LOCK) {
            Lifecycle lifecycle = current;
            return lifecycle != null && (lifecycle.pendingCommand != null || lifecycle.commandInFlight);
        }
    }

    /** Constant time, no I/O: the request waits in one field and the next tick or the free worker starts it. */
    private static Admission request(ILogStorage expectedStorage, Command command) {
        synchronized (LIFECYCLE_LOCK) {
            Lifecycle lifecycle = current;
            if (lifecycle == null || lifecycle.storage != expectedStorage) return Admission.UNAVAILABLE;
            if (lifecycle.pendingCommand != null || lifecycle.commandInFlight) return Admission.BUSY;
            lifecycle.pendingCommand = command;
            if (lifecycle.startupDispatched && !lifecycle.passInFlight) submitCommandLocked(lifecycle);
            return Admission.QUEUED;
        }
    }

    private static void submitCommandLocked(Lifecycle lifecycle) {
        if (lifecycle != current || lifecycle.passInFlight || lifecycle.pendingCommand == null) return;
        Command command = lifecycle.pendingCommand;
        lifecycle.pendingCommand = null;
        lifecycle.commandInFlight = true;
        lifecycle.passInFlight = true;
        lifecycle.taskCompleted = false;
        long epoch = lifecycle.activityEpoch;
        lifecycle.activeTask = lifecycle.executor.submit(() -> runPass(lifecycle, epoch, PassKind.COMMAND, command));
    }

    private static void tryStartPendingLocked(Lifecycle lifecycle) {
        if (lifecycle != current || lifecycle.startupDispatched || retiredExecutor != null) return;
        lifecycle.executor = new ThreadPoolExecutor(MAINTENANCE_WORKERS, MAINTENANCE_WORKERS, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAINTENANCE_QUEUE_CAPACITY),
                maintenanceThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        lifecycle.startupDispatched = true;
        submitLocked(lifecycle, PassKind.STARTUP);
    }

    /** Startup, idle and busy retention passes; none at retention.days = 0, where only operator requests run. */
    private static void submitLocked(Lifecycle lifecycle, PassKind kind) {
        if (lifecycle != current || lifecycle.passInFlight || lifecycle.retentionDays <= 0) return;
        lifecycle.passInFlight = true;
        lifecycle.taskCompleted = false;
        long epoch = lifecycle.activityEpoch;
        lifecycle.activeTask = lifecycle.executor.submit(() -> runPass(lifecycle, epoch, kind, null));
    }

    private static boolean busyEligibleLocked(Lifecycle lifecycle) {
        return lifecycle.startupSettled && lifecycle.fullyStarted && lifecycle.recognizedHumanPlayers > 0
                && lifecycle.busyArmed && lifecycle.nanoClock.getAsLong() - lifecycle.busyDueNanos >= 0L;
    }

    /** A busy pass continues only while players are online; a stop or a not-started server withdraws it. */
    private static boolean mayStartBusyBatch(Lifecycle lifecycle) {
        synchronized (LIFECYCLE_LOCK) {
            return current == lifecycle && lifecycle.fullyStarted && lifecycle.recognizedHumanPlayers > 0;
        }
    }

    /** The pace for the next part of a purge: gentle while anyone is online. */
    private static RetentionPace purgePaceLocked(Lifecycle lifecycle) {
        return lifecycle.recognizedHumanPlayers > 0 ? RetentionPace.GENTLE : RetentionPace.STANDARD;
    }

    private static void armGraceLocked(Lifecycle lifecycle) {
        if (lifecycle.startupSettled && lifecycle.fullyStarted && lifecycle.recognizedHumanPlayers == 0
                && !lifecycle.graceArmed) {
            lifecycle.graceArmed = true;
            lifecycle.idleGraceDueNanos = lifecycle.nanoClock.getAsLong() + IDLE_GRACE_NANOS;
        }
    }

    private static boolean idleEligibleLocked(Lifecycle lifecycle) {
        return lifecycle.startupSettled && lifecycle.fullyStarted && lifecycle.recognizedHumanPlayers == 0
                && lifecycle.graceArmed && lifecycle.nanoClock.getAsLong() - lifecycle.idleGraceDueNanos >= 0L;
    }

    private static boolean mayStartBatch(Lifecycle lifecycle, long epoch, boolean idle) {
        synchronized (LIFECYCLE_LOCK) {
            return current == lifecycle && lifecycle.activityEpoch == epoch && lifecycle.recognizedHumanPlayers == 0
                    && (!idle || idleEligibleLocked(lifecycle));
        }
    }

    /** Shutdown may interrupt; a player join only revokes admission and never interrupts current native SQL. */
    public static void stop() {
        ThreadPoolExecutor stoppingExecutor;
        Future<?> stoppingTask;
        synchronized (LIFECYCLE_LOCK) {
            Lifecycle stopping = current;
            if (stopping == null) return;
            current = null;
            lifecycleGeneration++;
            stopping.activityEpoch++;
            stopping.graceArmed = false;
            stoppingExecutor = stopping.executor;
            stoppingTask = stopping.activeTask;
            // A pending successor has no executor; do not discard its still-live predecessor guard.
            if (stoppingExecutor != null) retiredExecutor = stoppingExecutor;
        }
        if (stoppingTask != null) stoppingTask.cancel(true);
        if (stoppingExecutor == null) return;
        stoppingExecutor.shutdownNow();
        try {
            if (!stoppingExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("EventTrail maintenance worker did not terminate within {} seconds; successor retention waits for actual termination",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for EventTrail maintenance to stop");
        }
        synchronized (LIFECYCLE_LOCK) { reapRetiredLocked(); }
    }

    private static void reapRetiredLocked() {
        if (retiredExecutor != null && retiredExecutor.isTerminated()) retiredExecutor = null;
    }

    private static void runPass(Lifecycle lifecycle, long epoch, PassKind kind, Command command) {
        lastWorkerThreadName = Thread.currentThread().getName();
        boolean idle = kind == PassKind.IDLE;
        long cutoff = 0L;
        RetentionResult result = new RetentionResult(0L, RetentionResult.Outcome.FAILED);
        // A measure or a shrink deletes nothing: it reports no retention result and keeps the last one.
        boolean retentionPass = command == null || command.kind() == Command.Kind.PURGE;
        // Set before the work, so even an Error that escapes it leaves the operator a reply.
        Object commandOutcome = failedOutcome(command, result);
        try {
            if (command != null && command.kind() == Command.Kind.PLAN_SHRINK) {
                commandOutcome = lifecycle.storage.planShrink();
            } else if (command != null && command.kind() == Command.Kind.SHRINK) {
                commandOutcome = lifecycle.storage.shrink(command.confirmed());
            } else if (command != null) {
                cutoff = lifecycle.wallClock.getAsLong() - command.days() * 24L * 60L * 60L * 1000L;
                LOGGER.info("Running the EventTrail purge an operator confirmed, for history older than {} days",
                        command.days());
                result = purge(lifecycle, cutoff);
                commandOutcome = result;
                logResult(lifecycle, result);
            } else {
                cutoff = automaticCutoff(lifecycle.retentionDays, lifecycle.wallClock.getAsLong());
                LOGGER.info("Running bounded EventTrail {} retention maintenance for history older than {} days",
                        kind.name().toLowerCase(java.util.Locale.ROOT), lifecycle.retentionDays);
                BooleanSupplier admission = kind == PassKind.BUSY ? () -> mayStartBusyBatch(lifecycle)
                        : () -> mayStartBatch(lifecycle, epoch, idle);
                result = Objects.requireNonNull(lifecycle.storage.deleteOlderThanWithResult(cutoff, admission,
                        kind == PassKind.BUSY ? RetentionPace.GENTLE : RetentionPace.STANDARD), "retention result");
                logResult(lifecycle, result);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("EventTrail retention maintenance failed for lifecycle {}; cleanup remains eligible for a later idle check ({}).",
                    lifecycle.generation, EventTrailDiagnostics.safeFailureSummary(failure));
            commandOutcome = failedOutcome(command, result);
        } finally {
            try {
                boolean stillCurrent;
                synchronized (LIFECYCLE_LOCK) {
                    stillCurrent = current == lifecycle;
                    if (stillCurrent && retentionPass) {
                        lifecycle.lastOutcome = result.outcome();
                        lifecycle.lastDeletedCount = result.deletedCount();
                        lifecycle.lastCutoffTimestamp = cutoff;
                        lifecycle.lastRetentionCompletedAtMillis = Math.max(0L, lifecycle.wallClock.getAsLong());
                    }
                }
                if (stillCurrent && retentionPass) {
                    try {
                        // Separate observation/result boundary. Startup is never idle-admitted, even if empty.
                        // The current maintenance body remains owned/in-flight until this call settles.
                        lifecycle.storage.afterRetentionMaintenance(cutoff, result,
                                () -> mayStartBatch(lifecycle, epoch, true));
                    } catch (RuntimeException observationFailure) {
                        LOGGER.warn("EventTrail maintenance observation failed separately from retention lifecycle {} ({}).",
                                lifecycle.generation, EventTrailDiagnostics.safeFailureSummary(observationFailure));
                    }
                }
            } finally {
                synchronized (LIFECYCLE_LOCK) {
                    // Old completions report their own outcome but cannot arm or modify a successor.
                    if (current == lifecycle) {
                        lifecycle.passInFlight = false;
                        lifecycle.commandInFlight = false;
                        lifecycle.taskCompleted = true;
                        lifecycle.activeTask = null;
                        lifecycle.completedPasses++;
                        if (retentionPass) {
                            // Rows may remain after a budget or a yield: the next gentle pass comes soon.
                            boolean rowsMayRemain = result.outcome() == RetentionResult.Outcome.BUDGET_EXHAUSTED
                                    || result.outcome() == RetentionResult.Outcome.YIELDED;
                            lifecycle.busyArmed = true;
                            lifecycle.busyDueNanos = lifecycle.nanoClock.getAsLong()
                                    + (rowsMayRemain ? BUSY_INTERVAL_NANOS : IDLE_RECHECK_NANOS);
                        }
                        if (kind == PassKind.BUSY) {
                            lifecycle.completedBusyPasses++;
                        } else if (kind == PassKind.COMMAND) {
                            lifecycle.completedCommandPasses++;
                        } else if (!idle) {
                            lifecycle.startupSettled = true;
                        } else {
                            lifecycle.completedIdlePasses++;
                            if (result.outcome() == RetentionResult.Outcome.EXHAUSTED
                                    || result.outcome() == RetentionResult.Outcome.FAILED
                                    || result.outcome() == RetentionResult.Outcome.INTERRUPTED) {
                                lifecycle.recheckArmed = true;
                                lifecycle.recheckDueNanos = lifecycle.nanoClock.getAsLong() + IDLE_RECHECK_NANOS;
                            } else {
                                lifecycle.recheckArmed = false;
                            }
                        }
                        armGraceLocked(lifecycle);
                    }
                }
                // After the bookkeeping, so a follow-up request (shrink after purge) finds the worker free.
                if (command != null) {
                    try {
                        command.done().accept(commandOutcome);
                    } catch (RuntimeException replyFailure) {
                        LOGGER.warn("EventTrail could not deliver an operator maintenance reply ({}).",
                                EventTrailDiagnostics.safeFailureSummary(replyFailure));
                    }
                }
            }
        }
    }

    /**
     * The cutoff of an automatic (startup, idle or busy) retention pass. A second guard behind the one in
     * submitLocked: at retention.days = 0 (keep forever) the cutoff would be now, so no automatic pass may run.
     */
    static long automaticCutoff(int retentionDays, long nowMillis) {
        if (retentionDays <= 0) {
            throw new IllegalStateException("no automatic retention pass at retention.days = " + retentionDays);
        }
        return nowMillis - retentionDays * 24L * 60L * 60L * 1000L;
    }

    /** The reply to an operator request whose work failed unexpectedly; null for a retention pass. */
    private static Object failedOutcome(Command command, RetentionResult result) {
        if (command == null) return null;
        return switch (command.kind()) {
            case PLAN_SHRINK -> TableShrink.Plan.refuse(TableShrink.Backend.UNSUPPORTED,
                    "the measure failed unexpectedly; see the server log");
            case SHRINK -> TableShrink.Result.notRun("the shrink failed unexpectedly");
            case PURGE -> result;
        };
    }

    /**
     * The confirmed purge: bounded parts until no expired row is left. Each part takes the gentle pace while
     * players are online; a standard part yields at a join and the next part continues gently. Only a stop ends it.
     */
    private static RetentionResult purge(Lifecycle lifecycle, long cutoff) {
        long deleted = 0L;
        while (true) {
            RetentionPace pace;
            synchronized (LIFECYCLE_LOCK) {
                if (current != lifecycle) return new RetentionResult(deleted, RetentionResult.Outcome.INTERRUPTED);
                pace = purgePaceLocked(lifecycle);
            }
            boolean gentle = pace == RetentionPace.GENTLE;
            RetentionResult part = Objects.requireNonNull(lifecycle.storage.deleteOlderThanWithResult(cutoff,
                    () -> mayContinuePurge(lifecycle, gentle), pace), "purge result");
            deleted += part.deletedCount();
            boolean continues = part.outcome() == RetentionResult.Outcome.BUDGET_EXHAUSTED
                    || part.outcome() == RetentionResult.Outcome.YIELDED;
            if (!continues || Thread.currentThread().isInterrupted()) {
                return new RetentionResult(deleted, part.outcome() == RetentionResult.Outcome.YIELDED
                        ? RetentionResult.Outcome.INTERRUPTED : part.outcome());
            }
        }
    }

    private static boolean mayContinuePurge(Lifecycle lifecycle, boolean gentle) {
        synchronized (LIFECYCLE_LOCK) {
            return current == lifecycle && (gentle || lifecycle.recognizedHumanPlayers == 0);
        }
    }

    private static void logResult(Lifecycle lifecycle, RetentionResult result) {
        boolean retired;
        synchronized (LIFECYCLE_LOCK) {
            retired = current != lifecycle;
        }
        if (retired) {
            LOGGER.info("EventTrail retention maintenance lifecycle {} finished after stop: deleted={}, outcome={}; no continuation belongs to this retired lifecycle.",
                    lifecycle.generation, result.deletedCount(), result.outcome());
            return;
        }
        switch (result.outcome()) {
            case EXHAUSTED -> {
                if (result.deletedCount() > 0L) LOGGER.info("EventTrail retention maintenance removed {} old log entries; no further expired rows were observed for this cutoff", result.deletedCount());
                else LOGGER.info("EventTrail retention maintenance found no expired log entries for this cutoff");
            }
            case BUDGET_EXHAUSTED -> LOGGER.info("EventTrail retention maintenance removed {} old log entries and reached its work budget; expired rows may remain. Further work waits for idle eligibility.", result.deletedCount());
            case YIELDED -> LOGGER.info("EventTrail retention maintenance yielded after removing {} old log entries; expired rows may remain. Further work waits for idle eligibility.", result.deletedCount());
            case INTERRUPTED -> LOGGER.info("EventTrail retention maintenance was interrupted after removing {} old log entries; expired rows may remain. A later idle check may retry cleanup.", result.deletedCount());
            case FAILED -> LOGGER.warn("EventTrail retention maintenance failed after removing {} old log entries; expired rows may remain. A later idle check may retry cleanup.", result.deletedCount());
        }
    }

    private static ThreadFactory maintenanceThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "EventTrail-Maintenance-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    static MaintenanceStats stats() {
        synchronized (LIFECYCLE_LOCK) {
            reapRetiredLocked();
            Lifecycle lifecycle = current;
            ThreadPoolExecutor executor = lifecycle != null && lifecycle.executor != null ? lifecycle.executor : retiredExecutor;
            return new MaintenanceStats(MAINTENANCE_WORKERS, MAINTENANCE_QUEUE_CAPACITY,
                    executor == null ? 0 : executor.getQueue().size(), executor == null ? 0 : executor.getActiveCount(),
                    lifecycleGeneration, lifecycle != null, lifecycle != null && lifecycle.taskCompleted,
                    executor != null && executor.isShutdown(), executor != null && executor.isTerminated(),
                    executor == null || executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy,
                    lastWorkerThreadName, lifecycle != null && lifecycle.startupSettled,
                    lifecycle != null && lifecycle.fullyStarted, lifecycle == null ? 0 : lifecycle.recognizedHumanPlayers,
                    lifecycle != null && lifecycle.graceArmed, lifecycle != null && lifecycle.passInFlight,
                    lifecycle == null ? 0 : lifecycle.completedPasses, lifecycle == null ? 0 : lifecycle.completedIdlePasses,
                    lifecycle == null ? null : lifecycle.lastOutcome, lifecycle == null ? 0 : lifecycle.lastDeletedCount,
                    lifecycle == null ? 0 : lifecycle.lastCutoffTimestamp, lifecycle == null ? 0 : lifecycle.idleGraceDueNanos,
                    lifecycle == null || !lifecycle.recheckArmed ? 0 : lifecycle.recheckDueNanos,
                    lifecycle != null && !lifecycle.startupDispatched && retiredExecutor != null,
                    lifecycle == null ? 0 : lifecycle.completedBusyPasses,
                    lifecycle == null ? 0 : lifecycle.completedCommandPasses,
                    lifecycle == null || !lifecycle.busyArmed ? 0 : lifecycle.busyDueNanos);
        }
    }

    record MaintenanceStats(int workers, int queueCapacity, int queued, int activeWorkers, long generation,
                            boolean running, boolean taskCompleted, boolean shutdown, boolean terminated, boolean abortPolicy,
                            String lastWorkerThreadName, boolean startupSettled, boolean fullyStarted, int recognizedHumanPlayers,
                            boolean graceArmed, boolean passInFlight, long completedPasses, long completedIdlePasses,
                            RetentionResult.Outcome lastOutcome, long lastDeletedCount, long lastCutoffTimestamp,
                            long idleGraceDueNanos, long recheckDueNanos, boolean waitingForRetired,
                            long completedBusyPasses, long completedCommandPasses, long busyDueNanos) {}

    private static final class Lifecycle {
        final long generation;
        final ILogStorage storage;
        final int retentionDays;
        // No public mutation API. Focused/loaded test harnesses can install controlled clocks for this generation only.
        volatile LongSupplier nanoClock;
        volatile LongSupplier wallClock;
        boolean fullyStarted;
        int recognizedHumanPlayers;
        long activityEpoch;
        ThreadPoolExecutor executor;
        Future<?> activeTask;
        boolean startupDispatched;
        boolean startupSettled;
        boolean passInFlight;
        boolean taskCompleted;
        boolean graceArmed;
        long idleGraceDueNanos;
        boolean recheckArmed;
        long recheckDueNanos;
        long completedPasses;
        long completedIdlePasses;
        long completedBusyPasses;
        long completedCommandPasses;
        boolean busyArmed;
        long busyDueNanos;
        Command pendingCommand;
        boolean commandInFlight;
        RetentionResult.Outcome lastOutcome;
        long lastDeletedCount;
        long lastCutoffTimestamp;
        long lastRetentionCompletedAtMillis;

        Lifecycle(long generation, ILogStorage storage, int retentionDays, boolean fullyStarted, int recognizedHumanPlayers,
                  LongSupplier nanoClock, LongSupplier wallClock) {
            this.generation = generation; this.storage = storage; this.retentionDays = retentionDays;
            this.fullyStarted = fullyStarted; this.recognizedHumanPlayers = recognizedHumanPlayers;
            this.nanoClock = nanoClock; this.wallClock = wallClock;
        }
    }
}
