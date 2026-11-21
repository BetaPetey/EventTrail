package io.github.betapetey.eventtrail.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One registered statement's cancellation lifetime, never a pooled-connection owner.
 * The operation owns sticky cancellation and must retire this lease before it
 * commits or returns its connection. No driver call runs under the metadata lock.
 * Driver cancel/close can themselves be uncooperative; waiting for their actual
 * acknowledgement is not a universal wall-clock shutdown guarantee.
 */
final class JdbcStatementCancellation {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final long RETRY_MILLIS = 25L;
    private final Statement statement;
    private final ThreadFactory threadFactory;
    private final Object lifecycle = new Object();
    private boolean retired;
    private boolean cancellationStarted;
    private boolean cancellationComplete;
    private Result result;
    private JdbcShutdownDiagnostics.Execution diagnostics;

    /** Bound once by the owning operation, before this existing lease is published. */
    void observeExecution(JdbcShutdownDiagnostics.Execution execution) { diagnostics = execution; }
    JdbcShutdownDiagnostics.Execution diagnosticExecution() { return diagnostics; }

    JdbcStatementCancellation(Statement statement) {
        this(statement, task -> {
            Thread worker = new Thread(task, "EventTrail-JDBC-Cancel-" + THREAD_IDS.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        });
    }

    /** Test seam for owned helper creation/start failures, without global thread policy changes. */
    JdbcStatementCancellation(Statement statement, ThreadFactory threadFactory) {
        this.statement = Objects.requireNonNull(statement, "statement");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    boolean owns(Statement candidate) {
        return statement == candidate;
    }

    /**
     * Retires the exact lease and waits for every already-claimed driver callback.
     * Once this returns, neither a saved lease reference nor a helper thread can
     * subsequently interrupt a successor borrower of the same physical connection.
     */
    void retire() {
        boolean interrupted = false;
        synchronized (lifecycle) {
            retired = true;
            while (cancellationStarted && !cancellationComplete) {
                try {
                    lifecycle.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (diagnostics != null) JdbcShutdownDiagnostics.retired(diagnostics);
    }

    Result cancelAndClose() {
        boolean interrupted = false;
        synchronized (lifecycle) {
            if (cancellationStarted) {
                while (!cancellationComplete) {
                    try {
                        lifecycle.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return result;
            }
            if (retired) return new Result(false, 0, false, null);
            cancellationStarted = true;
        }

        Result completed = null;
        try {
            completed = cancelOwnedStatement();
            return completed;
        } catch (Throwable failure) {
            // Even an unexpected helper failure must not escape the storage
            // close boundary and prevent its remaining pool cleanup.
            completed = new Result(true, 0, false, failure);
            return completed;
        } finally {
            synchronized (lifecycle) {
                result = completed;
                cancellationComplete = true;
                lifecycle.notifyAll();
            }
            // Existing retirement handoff is complete before optional diagnostic work.
            if (diagnostics != null) JdbcShutdownDiagnostics.settled(diagnostics, completed);
        }
    }

    private Result cancelOwnedStatement() {
        Failures failures = new Failures();
        RetryCancellation retry = new RetryCancellation(failures);
        Thread worker = null;
        boolean closed = false;
        try {
            // Preserve the initial public JDBC cancellation, including drivers
            // for which it is sufficient. Failure cannot skip the close attempt.
            retry.cancelOnce();
            try {
                worker = Objects.requireNonNull(threadFactory.newThread(() -> {
                    try (EventTrailJdbcLogScope.Scope ignored = EventTrailJdbcLogScope.open()) {
                        retry.run();
                    } catch (Throwable failure) {
                        failures.record(failure);
                    }
                }), "JDBC cancellation helper factory returned null");
                worker.start();
            } catch (Throwable failure) {
                failures.record(failure);
            }
        } finally {
            try {
                try {
                    statement.close();
                } catch (Throwable failure) {
                    failures.record(failure);
                }
            } finally {
                // Thread.start can start a helper and then throw. A returned
                // worker is stopped/joined even when start or close failed.
                retry.stopAndJoin(worker);
            }
        }
        try {
            closed = statement.isClosed();
        } catch (Throwable failure) {
            failures.record(failure);
        }
        if (!closed && failures.first() == null) {
            failures.record(new SQLException("JDBC statement remained open after cancellation and close"));
        }
        return new Result(true, retry.attempts, closed, failures.first());
    }

    /** Bounded causal retention: first failure plus distinct cleanup/status failures. */
    private static final class Failures {
        private final Throwable[] retained = new Throwable[4];
        private int count;

        synchronized void record(Throwable failure) {
            for (int index = 0; index < count; index++) {
                if (retained[index] == failure) return;
            }
            if (count == retained.length) return;
            if (count > 0) retained[0].addSuppressed(failure);
            retained[count++] = failure;
        }

        synchronized Throwable first() {
            return count == 0 ? null : retained[0];
        }
    }

    record Result(boolean claimed, int cancelAttempts, boolean closed, Throwable failure) {}

    private final class RetryCancellation implements Runnable {
        private final Object wakeup = new Object();
        private final Failures failures;
        private boolean stop;
        // The first call is on the owner; thread start/join orders later access.
        private int attempts;
        private boolean cancellationFailureRecorded;

        private RetryCancellation(Failures failures) {
            this.failures = failures;
        }

        void cancelOnce() {
            attempts++;
            JdbcShutdownDiagnostics.CancelCall diagnosticCall = diagnostics == null
                    ? null : JdbcShutdownDiagnostics.beginCancel(diagnostics, attempts);
            try {
                statement.cancel();
                if (diagnosticCall != null) JdbcShutdownDiagnostics.cancelReturned(diagnosticCall, statement, null);
            } catch (Throwable failure) {
                if (diagnosticCall != null) JdbcShutdownDiagnostics.cancelReturned(diagnosticCall, statement, failure);
                if (!cancellationFailureRecorded) {
                    cancellationFailureRecorded = true;
                    failures.record(failure);
                }
            } finally {
                if (diagnosticCall != null) JdbcShutdownDiagnostics.endCancel(diagnosticCall);
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    synchronized (wakeup) {
                        if (stop) return;
                        try {
                            wakeup.wait(RETRY_MILLIS);
                        } catch (InterruptedException ignored) {
                            // This owned helper has no external cancellation
                            // authority; stopAndJoin controls its lifetime.
                        }
                        if (stop) return;
                    }
                    cancelOnce();
                }
            } catch (Throwable failure) {
                failures.record(failure);
            }
        }

        void stopAndJoin(Thread worker) {
            synchronized (wakeup) {
                stop = true;
                wakeup.notifyAll();
            }
            if (worker == null) return;
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
