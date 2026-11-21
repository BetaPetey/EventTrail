package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.EventTrailIds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Shared EventTrail query worker and server-thread handoff. Query tasks must contain
 * database or pure-Java work only; session resolution and callbacks run through
 * the supplied server executor.
 */
public final class EventTrailQueryDispatcher {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    static final int QUERY_THREADS = 2;
    static final int QUERY_QUEUE_CAPACITY = 32;
    static final int MAX_OUTSTANDING_PER_PLAYER = 2;
    static final long REQUEST_DEADLINE_MILLIS = 30_000L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final String BUSY_MESSAGE = "EventTrail is busy processing lookups. Try again shortly.";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final Set<CompletableFuture<?>> ACTIVE_TASKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<UUID, PlayerAdmission> PLAYER_ADMISSIONS = new HashMap<>();

    /**
     * 6 b: the one timer thread that gives up queued lookups at their deadline; owned by the lifecycle. Its name
     * does not start with "EventTrail-Query-", which names the query workers only (lookup-lane-01 review-01 NIT-2).
     */
    static final String DEADLINE_THREAD_NAME = "EventTrail-QueryDeadline";
    private static final long WAIT_NOTICE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60L);
    // 6 b: at most one "waited" WARN every 60 s, with the number of lookups given up since the last one.
    private static final Object WAIT_NOTICE_LOCK = new Object();
    private static boolean waitNoticeWritten;
    private static long waitNoticeNanos;
    private static long givenUpSinceNotice;

    private static ThreadPoolExecutor queryExecutor;
    /** 6 b: non-null exactly while the lifecycle is RUNNING; guarded by LIFECYCLE_LOCK. */
    private static ScheduledThreadPoolExecutor deadlineTimer;
    private static LifecycleState lifecycleState = LifecycleState.STOPPED;
    private static long lifecycleGeneration;
    private static long playerRequestGeneration;

    private EventTrailQueryDispatcher() {}

    /** Start the query workers for a server lifecycle; repeated starts are idempotent. */
    public static void start() {
        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleState == LifecycleState.RUNNING
                    && queryExecutor != null
                    && !queryExecutor.isShutdown()) {
                return;
            }
            startLocked();
        }
    }

    /** Stop accepting work and make a bounded attempt to interrupt this lifecycle's workers. */
    public static void shutdown() {
        ThreadPoolExecutor executor;
        ArrayList<CompletableFuture<?>> tasks;
        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleState == LifecycleState.STOPPED && queryExecutor == null && deadlineTimer == null) return;
            lifecycleState = LifecycleState.STOPPED;
            lifecycleGeneration++;
            executor = queryExecutor;
            queryExecutor = null;
            // 6 b: the timer stops here, before the "no executor" return below, so no path leaves it running.
            // shutdownNow never waits, so it is safe under the lock; a late expiry finds its future done (or the
            // lifecycle stopped) and dispatches nothing.
            stopDeadlineTimerLocked();
            PLAYER_ADMISSIONS.values().forEach(PlayerAdmission::supersede);
            PLAYER_ADMISSIONS.clear();
            tasks = new ArrayList<>(ACTIVE_TASKS);
        }

        tasks.forEach(task -> task.cancel(true));
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("EventTrail query workers did not terminate within {} seconds; late results will be discarded",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for EventTrail query workers to stop; late results will be discarded");
        }
    }

    static <T> CompletableFuture<T> submit(Supplier<T> databaseTask) {
        return submitTracked(databaseTask, REQUEST_DEADLINE_MILLIS).future();
    }

    static <T> CompletableFuture<T> submitAndDispatch(
            Supplier<T> databaseTask, Executor serverExecutor, BiConsumer<T, Throwable> completion) {
        Submission<T> submission = submitTracked(databaseTask, REQUEST_DEADLINE_MILLIS);
        submission.future().whenComplete((result, error) -> dispatchIfCurrent(
                submission.lifecycleGeneration(), serverExecutor, () -> completion.accept(result, error)));
        return submission.future();
    }

    static <T, S> CompletableFuture<T> submitAndDispatch(
            Supplier<T> databaseTask,
            Executor serverExecutor,
            Supplier<S> currentSession,
            S expectedSession,
            BiConsumer<S, T> success,
            BiConsumer<S, Throwable> failure) {
        Submission<T> submission = submitTracked(databaseTask, REQUEST_DEADLINE_MILLIS);
        submission.future().whenComplete((result, error) -> dispatchIfCurrent(
                submission.lifecycleGeneration(), serverExecutor, () -> {
                    S current = currentSession.get();
                    if (current == null || current != expectedSession) return;
                    if (error == null) success.accept(current, result);
                    else failure.accept(current, error);
                }));
        return submission.future();
    }

    /**
     * Admit player-owned work with a finite per-player bound and latest-request
     * ownership. The synchronous accepted flag lets callers clear old UI state
     * only after the replacement request is truly admitted.
     */
    static <T, S> PlayerSubmission<T> submitPlayerAndDispatch(
            UUID playerId,
            Supplier<T> databaseTask,
            Executor serverExecutor,
            Supplier<S> currentSession,
            S expectedSession,
            BiConsumer<S, T> success,
            BiConsumer<S, Throwable> failure) {
        return submitPlayerAndDispatch(playerId, databaseTask, () -> {}, REQUEST_DEADLINE_MILLIS,
                serverExecutor, currentSession, expectedSession, success, failure);
    }

    static <T, S> PlayerSubmission<T> submitPlayerAndDispatch(
            UUID playerId,
            Supplier<T> databaseTask,
            Runnable acceptedAction,
            Executor serverExecutor,
            Supplier<S> currentSession,
            S expectedSession,
            BiConsumer<S, T> success,
            BiConsumer<S, Throwable> failure) {
        return submitPlayerAndDispatch(playerId, databaseTask, acceptedAction, REQUEST_DEADLINE_MILLIS,
                serverExecutor, currentSession, expectedSession, success, failure);
    }

    static <T, S> PlayerSubmission<T> submitPlayerAndDispatch(
            UUID playerId,
            Supplier<T> databaseTask,
            Runnable acceptedAction,
            long deadlineMillis,
            Executor serverExecutor,
            Supplier<S> currentSession,
            S expectedSession,
            BiConsumer<S, T> success,
            BiConsumer<S, Throwable> failure) {
        PlayerTrackedSubmission<T> tracked = submitPlayerTracked(playerId, databaseTask, deadlineMillis);
        if (!tracked.accepted()) return new PlayerSubmission<>(tracked.future(), false);

        acceptedAction.run();
        tracked.future().whenComplete((result, error) -> dispatchPlayerIfCurrent(
                tracked.ticket(), serverExecutor, () -> {
                    S current = currentSession.get();
                    if (current == null || current != expectedSession) return;
                    if (error == null) success.accept(current, result);
                    else failure.accept(current, error);
                }));
        return new PlayerSubmission<>(tracked.future(), true);
    }

    /** Test seam for proving database work and Minecraft callbacks use different executors. */
    static <T, S> CompletableFuture<T> submitAndDispatch(
            Supplier<T> databaseTask,
            Executor queryExecutor,
            Executor serverExecutor,
            Supplier<S> currentSession,
            S expectedSession,
            BiConsumer<S, T> success,
            BiConsumer<S, Throwable> failure) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(databaseTask, queryExecutor);
        future.whenComplete((result, error) -> serverExecutor.execute(() -> {
            S current = currentSession.get();
            if (current == null || current != expectedSession) return;
            if (error == null) success.accept(current, result);
            else failure.accept(current, error);
        }));
        return future;
    }

    static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        Set<CompletableFuture<?>> tasks;
        synchronized (LIFECYCLE_LOCK) {
            PlayerAdmission admission = PLAYER_ADMISSIONS.remove(playerId);
            if (admission == null) return;
            admission.supersede();
            tasks = new HashSet<>(admission.tasks);
        }
        tasks.forEach(task -> task.cancel(true));
    }

    static boolean isExpectedRejection(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof QueryAdmissionException
                    || current instanceof RejectedExecutionException) return true;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    static String busyMessage() {
        return BUSY_MESSAGE;
    }

    static boolean isRunning() {
        synchronized (LIFECYCLE_LOCK) {
            return lifecycleState == LifecycleState.RUNNING
                    && queryExecutor != null
                    && !queryExecutor.isShutdown();
        }
    }

    static long lifecycleGeneration() {
        synchronized (LIFECYCLE_LOCK) {
            return lifecycleGeneration;
        }
    }

    static int activeTaskCount() {
        return ACTIVE_TASKS.size();
    }

    /** Cached bounded workload census; includes investigations not yet dispatched to JDBC. */
    public static boolean hasOutstandingWork() {
        synchronized (LIFECYCLE_LOCK) {
            return !ACTIVE_TASKS.isEmpty() || queryExecutor != null
                    && (queryExecutor.getActiveCount() != 0 || !queryExecutor.getQueue().isEmpty());
        }
    }

    static int queuedTaskCount() {
        synchronized (LIFECYCLE_LOCK) {
            return queryExecutor == null ? 0 : queryExecutor.getQueue().size();
        }
    }

    static int playerOutstandingCount(UUID playerId) {
        synchronized (LIFECYCLE_LOCK) {
            PlayerAdmission admission = PLAYER_ADMISSIONS.get(playerId);
            return admission == null ? 0 : admission.outstanding;
        }
    }

    static int playerAdmissionCount() {
        synchronized (LIFECYCLE_LOCK) {
            return PLAYER_ADMISSIONS.size();
        }
    }

    private static <T> Submission<T> submitTracked(Supplier<T> databaseTask, long deadlineMillis) {
        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleState != LifecycleState.RUNNING
                    || queryExecutor == null
                    || queryExecutor.isShutdown()) {
                return rejectedSubmission("EventTrail query workers are not accepting work during server shutdown");
            }

            long generation = lifecycleGeneration;
            CompletableFuture<T> future = new CompletableFuture<>();
            QueuedWork<T> work = boundedWork(databaseTask, future, deadlineNanos(deadlineMillis), deadlineMillis);
            try {
                queryExecutor.execute(work);
            } catch (RejectedExecutionException rejected) {
                future.completeExceptionally(new QueryAdmissionException("EventTrail query queue is full", rejected));
                return new Submission<>(future, generation);
            }
            trackFuture(future);
            armExpiryLocked(work);
            return new Submission<>(future, generation);
        }
    }

    private static <T> PlayerTrackedSubmission<T> submitPlayerTracked(
            UUID playerId, Supplier<T> databaseTask, long deadlineMillis) {
        if (playerId == null) {
            CompletableFuture<T> failed = CompletableFuture.failedFuture(
                    new QueryAdmissionException("Player-owned query has no player identity"));
            return new PlayerTrackedSubmission<>(failed, null, false);
        }

        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleState != LifecycleState.RUNNING
                    || queryExecutor == null
                    || queryExecutor.isShutdown()) {
                CompletableFuture<T> failed = CompletableFuture.failedFuture(
                        new QueryAdmissionException("EventTrail query workers are not accepting work"));
                return new PlayerTrackedSubmission<>(failed, null, false);
            }

            PlayerAdmission admission = PLAYER_ADMISSIONS.computeIfAbsent(
                    playerId, ignored -> new PlayerAdmission());
            if (admission.outstanding >= MAX_OUTSTANDING_PER_PLAYER) {
                CompletableFuture<T> failed = CompletableFuture.failedFuture(
                        new QueryAdmissionException("Player query limit reached"));
                return new PlayerTrackedSubmission<>(failed, null, false);
            }

            RequestTicket previous = admission.current;
            RequestTicket ticket = new RequestTicket(
                    playerId, ++playerRequestGeneration, lifecycleGeneration, deadlineNanos(deadlineMillis));
            admission.current = ticket;
            admission.outstanding++;
            if (previous != null) previous.current = false;

            CompletableFuture<T> future = new CompletableFuture<>();
            QueuedWork<T> work = boundedWork(databaseTask, future, ticket.deadlineNanos, deadlineMillis);
            try {
                queryExecutor.execute(work);
            } catch (RejectedExecutionException rejected) {
                ticket.current = false;
                admission.current = previous;
                if (previous != null) previous.current = true;
                admission.outstanding--;
                if (admission.outstanding == 0) PLAYER_ADMISSIONS.remove(playerId, admission);
                future.completeExceptionally(new QueryAdmissionException("EventTrail query queue is full", rejected));
                return new PlayerTrackedSubmission<>(future, null, false);
            }

            admission.tasks.add(future);
            trackFuture(future);
            armExpiryLocked(work);
            return new PlayerTrackedSubmission<>(future, ticket, true);
        }
    }

    /** Called with LIFECYCLE_LOCK held and the lifecycle RUNNING (queryExecutor is non-null). */
    private static <T> QueuedWork<T> boundedWork(
            Supplier<T> databaseTask, CompletableFuture<T> future, long deadlineNanos, long deadlineMillis) {
        return new QueuedWork<>(databaseTask, future, deadlineNanos, deadlineMillis, queryExecutor);
    }

    /**
     * 6 b: schedules the give-up of a just-queued task at its deadline. Called with LIFECYCLE_LOCK held after
     * the task was accepted; the timer exists because the lifecycle is RUNNING. If it cannot be scheduled, the
     * dequeue-time deadline check in {@link QueuedWork#run()} still applies (the old behaviour).
     */
    private static void armExpiryLocked(QueuedWork<?> work) {
        ScheduledThreadPoolExecutor timer = deadlineTimer;
        if (timer == null || work.deadlineNanos == Long.MAX_VALUE) return;
        long delayNanos = Math.max(0L, work.deadlineNanos - System.nanoTime());
        try {
            work.arm(timer.schedule(work::expire, delayNanos, TimeUnit.NANOSECONDS));
        } catch (RejectedExecutionException timerStopped) {
            // Not reachable while RUNNING under the lock; the dequeue-time check remains the backstop.
        }
    }

    private static void noteWaitExpired(long deadlineMillis) {
        long givenUp;
        synchronized (WAIT_NOTICE_LOCK) {
            givenUpSinceNotice++;
            long now = System.nanoTime();
            if (waitNoticeWritten && now - waitNoticeNanos < WAIT_NOTICE_INTERVAL_NANOS) return;
            waitNoticeWritten = true;
            waitNoticeNanos = now;
            givenUp = givenUpSinceNotice;
            givenUpSinceNotice = 0L;
        }
        LOGGER.warn("EventTrail lookups waited more than {} for a free worker; {} given up since the last notice",
                deadlineMillis % 1_000L == 0L ? (deadlineMillis / 1_000L) + " s" : deadlineMillis + " ms", givenUp);
    }

    /**
     * One queued lookup. Its state goes QUEUED -> STARTED (a worker took it) or QUEUED -> EXPIRED (the timer gave
     * it up at its deadline); one compare-and-set decides, so a lookup either runs or is given up, never both and
     * never neither (6 b). A given-up task leaves the queue at once and its future fails with
     * {@link QueryDeadlineException}, which the three call sites already turn into the busy text.
     */
    private static final class QueuedWork<T> implements Runnable {
        private static final int QUEUED = 0;
        private static final int STARTED = 1;
        private static final int EXPIRED = 2;

        private final Supplier<T> databaseTask;
        private final CompletableFuture<T> future;
        private final long deadlineNanos;
        private final long deadlineMillis;
        private final ThreadPoolExecutor executor;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private volatile ScheduledFuture<?> expiry;

        private QueuedWork(Supplier<T> databaseTask, CompletableFuture<T> future, long deadlineNanos,
                           long deadlineMillis, ThreadPoolExecutor executor) {
            this.databaseTask = databaseTask;
            this.future = future;
            this.deadlineNanos = deadlineNanos;
            this.deadlineMillis = Math.max(1L, deadlineMillis);
            this.executor = executor;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, STARTED)) return;
            // The armed expiry is cancelled here or by arm() itself, whichever runs second (both fields volatile).
            cancelExpiry();
            if (future.isDone()) return;
            if (System.nanoTime() >= deadlineNanos) {
                // Backstop for a task whose expiry could not be scheduled, or that a worker took after its deadline
                // before the timer fired (the old dequeue-time check). Counted in the "waited" notice too.
                if (future.completeExceptionally(new QueryDeadlineException())) noteWaitExpired(deadlineMillis);
                return;
            }
            try {
                future.complete(databaseTask.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        }

        private void arm(ScheduledFuture<?> scheduled) {
            expiry = scheduled;
            if (state.get() != QUEUED) scheduled.cancel(false);
        }

        private void cancelExpiry() {
            ScheduledFuture<?> scheduled = expiry;
            if (scheduled != null) scheduled.cancel(false);
        }

        /** Timer thread only. Never blocks: queue removal, one completion, at most one log line. */
        private void expire() {
            if (!state.compareAndSet(QUEUED, EXPIRED)) return;
            executor.remove(this);
            if (future.completeExceptionally(new QueryDeadlineException())) noteWaitExpired(deadlineMillis);
        }
    }

    private static long deadlineNanos(long deadlineMillis) {
        long boundedMillis = Math.max(1L, deadlineMillis);
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(boundedMillis);
        long deadline = now + nanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private static void trackFuture(CompletableFuture<?> future) {
        ACTIVE_TASKS.add(future);
        future.whenComplete((result, error) -> ACTIVE_TASKS.remove(future));
    }

    private static <T> Submission<T> rejectedSubmission(String reason) {
        CompletableFuture<T> failed = CompletableFuture.failedFuture(new QueryAdmissionException(reason));
        return new Submission<>(failed, -1L);
    }

    private static void dispatchIfCurrent(long generation, Executor serverExecutor, Runnable callback) {
        if (!isCurrentGeneration(generation)) return;
        try {
            serverExecutor.execute(() -> {
                if (isCurrentGeneration(generation)) callback.run();
            });
        } catch (RejectedExecutionException ignored) {
            // The Minecraft server stopped accepting work between both lifecycle checks.
        }
    }

    private static void dispatchPlayerIfCurrent(
            RequestTicket ticket, Executor serverExecutor, Runnable callback) {
        if (!isCurrent(ticket)) {
            releasePlayer(ticket);
            return;
        }
        try {
            serverExecutor.execute(() -> {
                try {
                    if (isCurrent(ticket)) callback.run();
                } finally {
                    releasePlayer(ticket);
                }
            });
        } catch (RejectedExecutionException ignored) {
            releasePlayer(ticket);
        }
    }

    private static boolean isCurrentGeneration(long generation) {
        synchronized (LIFECYCLE_LOCK) {
            return generation >= 0L
                    && lifecycleState == LifecycleState.RUNNING
                    && lifecycleGeneration == generation;
        }
    }

    private static boolean isCurrent(RequestTicket ticket) {
        synchronized (LIFECYCLE_LOCK) {
            if (ticket == null || !ticket.current
                    || lifecycleState != LifecycleState.RUNNING
                    || lifecycleGeneration != ticket.lifecycleGeneration) return false;
            PlayerAdmission admission = PLAYER_ADMISSIONS.get(ticket.playerId);
            return admission != null && admission.current == ticket;
        }
    }

    private static void releasePlayer(RequestTicket ticket) {
        if (ticket == null) return;
        synchronized (LIFECYCLE_LOCK) {
            PlayerAdmission admission = PLAYER_ADMISSIONS.get(ticket.playerId);
            if (admission == null || ticket.released) return;
            ticket.released = true;
            admission.outstanding = Math.max(0, admission.outstanding - 1);
            admission.tasks.removeIf(CompletableFuture::isDone);
            if (admission.outstanding == 0) PLAYER_ADMISSIONS.remove(ticket.playerId, admission);
        }
    }

    private static void startLocked() {
        lifecycleGeneration++;
        PLAYER_ADMISSIONS.clear();
        queryExecutor = new ThreadPoolExecutor(
                QUERY_THREADS, QUERY_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUERY_QUEUE_CAPACITY), queryThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        queryExecutor.prestartAllCoreThreads();
        // 6 b: one deadline timer per lifecycle, started with the workers. A left-over timer (only possible if
        // the old executor was shut down from outside) is stopped first, so exactly one is ever alive.
        stopDeadlineTimerLocked();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, DEADLINE_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        timer.prestartAllCoreThreads();
        deadlineTimer = timer;
        lifecycleState = LifecycleState.RUNNING;
    }

    /** Called with LIFECYCLE_LOCK held. Non-blocking: queued expiries are dropped and the thread is interrupted. */
    private static void stopDeadlineTimerLocked() {
        ScheduledThreadPoolExecutor timer = deadlineTimer;
        deadlineTimer = null;
        if (timer != null) timer.shutdownNow();
    }

    private static ThreadFactory queryThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "EventTrail-Query-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum LifecycleState { RUNNING, STOPPED }

    static class QueryAdmissionException extends RejectedExecutionException {
        QueryAdmissionException(String message) { super(message); }
        QueryAdmissionException(String message, Throwable cause) { super(message, cause); }
    }

    static final class QueryDeadlineException extends QueryAdmissionException {
        QueryDeadlineException() { super("EventTrail query deadline expired before execution"); }
    }

    static record PlayerSubmission<T>(CompletableFuture<T> future, boolean accepted) {}
    private record Submission<T>(CompletableFuture<T> future, long lifecycleGeneration) {}
    private record PlayerTrackedSubmission<T>(
            CompletableFuture<T> future, RequestTicket ticket, boolean accepted) {}

    private static final class RequestTicket {
        private final UUID playerId;
        private final long requestGeneration;
        private final long lifecycleGeneration;
        private final long deadlineNanos;
        private boolean current = true;
        private boolean released;

        private RequestTicket(
                UUID playerId, long requestGeneration, long lifecycleGeneration, long deadlineNanos) {
            this.playerId = playerId;
            this.requestGeneration = requestGeneration;
            this.lifecycleGeneration = lifecycleGeneration;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static final class PlayerAdmission {
        private RequestTicket current;
        private int outstanding;
        private final Set<CompletableFuture<?>> tasks = new HashSet<>();

        private void supersede() {
            if (current != null) current.current = false;
        }
    }
}
