package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.util.EventTrailFailure;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Same-file mutation admission and physical lifecycle exclusion. A mutator owns
 * admission before borrowing, but does not take the native gate until its borrow
 * has returned: the pool's independent factory must be able to provision it.
 * Reads do not take either gate, and neither does physical creation/setup: a read
 * that needs a new pool connection must not wait behind a mutator whose native
 * statement is blocked on an external lock (T33-L1). Creation stays counted,
 * seal-checked and ownership-verified. Physical close keeps the native gate,
 * because the last close of a WAL file checkpoints and removes the WAL.
 * Cancellation of an owned statement also bypasses both gates; the mutator
 * retains them until cancellation retirement and cleanup.
 */
final class SQLiteMutationCoordinator {
    private static final AtomicInteger RETIREMENT_IDS = new AtomicInteger();
    private final Ownership ownership;
    private final boolean enabled;
    private final BooleanSupplier operationsQuiescent;
    private final ThreadFactory retirementFactory;
    private final ReentrantLock admission = new ReentrantLock(true);
    private final ReentrantLock nativeExecution = new ReentrantLock(true);
    private final Object lifecycle = new Object();
    private final Set<PhysicalConnection> physical = Collections.newSetFromMap(new IdentityHashMap<>());
    private int mutations;
    private int creations;
    private int retirements;
    private boolean sealed;
    private boolean releaseRequested;
    private boolean released;
    private Throwable failure;
    private volatile EventTrailFailure accessFailure;

    SQLiteMutationCoordinator(SQLiteFileOwnership ownership, boolean enabled,
                              BooleanSupplier operationsQuiescent) {
        this(new Ownership() {
            @Override public void verify() throws IOException { ownership.verifyCurrent(); }
            @Override public void release() throws IOException { ownership.releaseOnlyAfterQuiescent(); }
            @Override public void poison(Throwable failure) { ownership.poison(failure); }
        }, enabled, operationsQuiescent);
    }

    /** Deterministic lifecycle fault seam; file-backed tests exercise the real owner. */
    SQLiteMutationCoordinator(Ownership ownership, boolean enabled, BooleanSupplier operationsQuiescent) {
        this(ownership, enabled, operationsQuiescent, task -> {
            Thread helper = new Thread(task, "EventTrail-SQLite-Retire-" + RETIREMENT_IDS.incrementAndGet());
            helper.setDaemon(true);
            return helper;
        });
    }

    SQLiteMutationCoordinator(Ownership ownership, boolean enabled, BooleanSupplier operationsQuiescent,
                              ThreadFactory retirementFactory) {
        this.ownership = ownership;
        this.enabled = enabled;
        this.operationsQuiescent = operationsQuiescent;
        this.retirementFactory = retirementFactory;
    }

    AbstractJdbcStorage.MutationScope begin(long deadlineNanos) throws SQLException {
        synchronized (lifecycle) {
            requireUsable();
            mutations++;
        }
        boolean acquired = false;
        boolean published = false;
        try {
            if (enabled) {
                long remaining = deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
                if (remaining <= 0 || !admission.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                    throw new SQLException("SQLite mutation admission exceeded its work deadline", "57014");
                }
            }
            acquired = true;
            synchronized (lifecycle) { requireUsable(); }
            published = true;
            return new Mutation(deadlineNanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("SQLite mutation admission was interrupted", "57014", interrupted);
        } catch (SQLException | RuntimeException | Error problem) {
            throw problem;
        } finally {
            if (!published) {
                if (acquired && enabled) admission.unlock();
                mutationFinished();
            }
        }
    }

    DataSource dataSource(ConnectionFactory factory, ConnectionConfigurer configure) {
        return new DataSource() {
            private PrintWriter logWriter;
            private int loginTimeout;
            @Override public Connection getConnection() throws SQLException { return create(factory, configure); }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
            @Override public PrintWriter getLogWriter() { return logWriter; }
            @Override public void setLogWriter(PrintWriter writer) { logWriter = writer; }
            @Override public void setLoginTimeout(int seconds) { loginTimeout = seconds; }
            @Override public int getLoginTimeout() { return loginTimeout; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException("SQLite datasource has no parent logger");
            }
            @Override public <T> T unwrap(Class<T> type) throws SQLException {
                if (type.isInstance(this)) return type.cast(this);
                throw new SQLException("Not a wrapper for " + type.getName());
            }
            @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
        };
    }

    private Connection create(ConnectionFactory factory, ConnectionConfigurer configure) throws SQLException {
        synchronized (lifecycle) {
            requireUsable();
            creations++;
        }
        PhysicalConnection tracked = null;
        boolean factoryEntered = false;
        try {
            // No native gate here (T33-L1). Opening and connection-local setup
            // (busy timeout, synchronous, temp_store) neither write nor
            // checkpoint the file; the borrower's first read then takes an
            // ordinary WAL read snapshot.
            synchronized (lifecycle) { requireUsable(); }
            verifyOwnership();
            factoryEntered = true;
            Connection connection = factory.open();
            if (connection == null) throw new SQLException("SQLite connection factory returned null");
            tracked = new PhysicalConnection(connection);
            synchronized (lifecycle) { physical.add(tracked); }
            configure.configure(connection);
            if (connection.isClosed()) throw new SQLException("SQLite physical connection closed during setup", "08003");
            synchronized (lifecycle) { requireUsable(); }
            return tracked.proxy();
        } catch (SQLException | RuntimeException | Error problem) {
            if (tracked != null) {
                // Failure path only: this close still takes the native gate.
                try { tracked.closePhysical(); }
                catch (Throwable cleanup) { if (cleanup != problem) problem.addSuppressed(cleanup); }
            } else if (factoryEntered) {
                // An opaque factory exception cannot prove that native opening
                // failed before allocating a handle it never returned to us.
                poison(problem);
            }
            throw problem;
        } finally {
            synchronized (lifecycle) {
                creations--;
                lifecycle.notifyAll();
            }
            attemptRelease();
        }
    }

    void verifyOwnership() throws SQLException {
        try { ownership.verify(); }
        catch (IOException failure) {
            poison(failure);
            throw new SQLException("SQLite physical file ownership could not be verified", "08003",
                    new EventTrailFailure.FailureIOException(accessFailure, failure));
        }
    }

    /** Cached terminal policy fact only; no metadata probe, native call, lock, or callback. */
    EventTrailFailure accessFailureSnapshot() { return accessFailure; }

    void seal() {
        synchronized (lifecycle) { sealed = true; lifecycle.notifyAll(); }
    }

    void requestRelease() {
        synchronized (lifecycle) { sealed = true; releaseRequested = true; }
        attemptRelease();
    }

    void attemptRelease() {
        synchronized (lifecycle) {
            if (!releaseRequested || released || failure != null || mutations != 0 || creations != 0 || retirements != 0
                    || !physical.isEmpty() || !operationsQuiescent.getAsBoolean()) return;
            try {
                // Spool r9c FINAL item (c): for SQLiteStorage's database, the only call that lets
                // SQLiteFileOwnership drop its OWNERS entry (the disk spool's spool.db has its own owner,
                // released by SpoolFile). The entry leaves only inside the release whose normal return
                // sets ownershipReleased(), after quiescence was proven and every resource closed. Snapshot
                // readers take this monitor, so they never see the folder freed while ownershipReleased()
                // is false; a successor's acquire does not take it and may win in the short gap before
                // released is set, which is safe because nothing of this owner is open by then. A failed
                // release poisons instead, and a poisoned owner keeps its entry until the JVM exits.
                ownership.release();
                released = true;
            } catch (IOException problem) {
                failure = problem;
                accessFailure = safeAccessFailure(problem);
                ownership.poison(problem);
            }
        }
    }

    void poison(Throwable problem) {
        synchronized (lifecycle) {
            if (failure == null) failure = problem;
            accessFailure = safeAccessFailure(failure);
            ownership.poison(problem);
        }
    }

    private static EventTrailFailure safeAccessFailure(Throwable failure) {
        return EventTrailFailure.find(failure).orElseGet(() -> new EventTrailFailure(
                EventTrailFailure.Code.STORAGE_QUIESCENCE_UNPROVEN, EventTrailFailure.Phase.CLEANUP,
                EventTrailFailure.Backend.SQLITE, EventTrailFailure.FileRole.UNKNOWN));
    }

    Snapshot snapshot() {
        synchronized (lifecycle) {
            return new Snapshot(enabled, mutations, creations, physical.size(), retirements, sealed, released, failure != null);
        }
    }

    private void requireUsable() throws SQLException {
        if (failure != null) {
            throw new SQLException("SQLite ownership is unproven; a replacement owner is not permitted", "08003",
                    new EventTrailFailure.FailureIOException(accessFailure, failure));
        }
        if (sealed || released) {
            throw new SQLException("SQLite ownership is closing; a replacement owner is not permitted", "08003");
        }
    }

    private void mutationFinished() {
        synchronized (lifecycle) { mutations--; lifecycle.notifyAll(); }
        attemptRelease();
    }

    private final class Mutation implements AbstractJdbcStorage.MutationScope {
        private final long deadline;
        private boolean nativeHeld;
        private boolean closed;
        Mutation(long deadline) { this.deadline = deadline; }

        @Override public void enterNative() throws SQLException {
            if (closed || nativeHeld) throw new SQLException("SQLite mutation scope is not enterable");
            try {
                if (enabled) {
                    long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                    if (remaining <= 0 || !nativeExecution.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                        throw new SQLException("SQLite mutation exceeded its native work deadline", "57014");
                    }
                    nativeHeld = true;
                }
                synchronized (lifecycle) { requireUsable(); }
                verifyOwnership();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("SQLite native mutation was interrupted", "57014", interrupted);
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (nativeHeld) nativeExecution.unlock();
            if (enabled) admission.unlock();
            mutationFinished();
        }
    }

    private final class PhysicalConnection {
        private final Connection delegate;
        private boolean closeAttempted;
        private volatile boolean closeAcknowledged;
        private Throwable closeFailure;
        private final AtomicBoolean abortDispatched = new AtomicBoolean();
        PhysicalConnection(Connection delegate) { this.delegate = delegate; }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("close") && method.getParameterCount() == 0) {
                            closePhysical();
                            return null;
                        }
                        if (name.equals("abort") && method.getParameterCount() == 1) {
                            requestAbort((Executor) arguments[0]);
                            return null;
                        }
                        if (name.equals("equals")) return proxy == arguments[0];
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("toString")) return "EventTrail-owned SQLite physical connection";
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }

        private void requestAbort(Executor executor) throws SQLException {
            if (executor == null) throw new SQLException("SQLite abort requires an executor");
            if (closeAcknowledged || !abortDispatched.compareAndSet(false, true)) return;
            synchronized (lifecycle) {
                if (closeAcknowledged || released) return;
                retirements++;
            }
            AtomicBoolean finished = new AtomicBoolean();
            Runnable finish = () -> {
                if (!finished.compareAndSet(false, true)) return;
                synchronized (lifecycle) { retirements--; lifecycle.notifyAll(); }
                attemptRelease();
            };
            Thread helper = null;
            try {
                helper = retirementFactory.newThread(() -> {
                    try (EventTrailJdbcLogScope.Scope ignored = EventTrailJdbcLogScope.open()) {
                        // Never let Runnable::run execute gated retirement on
                        // the canceling thread. Hikari may shut its supplied
                        // assassin executor immediately after abort returns, so
                        // this internal pool adapter owns the retirement thread
                        // directly instead of submitting to that expiring pool.
                        // One helper per physical handle; no inline fallback.
                        closePhysical();
                    } catch (Throwable failure) {
                        poison(failure);
                    } finally { finish.run(); }
                });
                if (helper == null) throw new IllegalStateException("SQLite retirement factory returned null");
                helper.setDaemon(true);
                helper.start();
            } catch (Throwable failure) {
                poison(failure);
                // start() can launch the task and then throw. Its actual
                // finally remains the acknowledgement while that task is live.
                if (helper == null || !helper.isAlive()) finish.run();
                throw new SQLException("SQLite retirement dispatch failed; native ownership remains unproven", failure);
            }
        }

        void closePhysical() throws SQLException {
            if (enabled) nativeExecution.lock();
            try {
                synchronized (this) {
                    if (closeAcknowledged) return;
                    if (closeAttempted) {
                        throw new SQLException("SQLite physical close previously failed; native release remains unproven", closeFailure);
                    }
                    closeAttempted = true;
                    try {
                        delegate.close();
                        if (!delegate.isClosed()) throw new SQLException("SQLite physical close returned without closing its handle");
                        closeAcknowledged = true;
                    } catch (SQLException | RuntimeException | Error problem) {
                        // Xerial sets isClosed before native _close. A throwing
                        // first close can therefore never be repaired by a later
                        // isClosed=true or successful no-op second close.
                        closeFailure = problem;
                        poison(problem);
                        throw problem;
                    }
                }
                synchronized (lifecycle) { physical.remove(this); lifecycle.notifyAll(); }
            } finally {
                if (enabled) nativeExecution.unlock();
                attemptRelease();
            }
        }
    }

    @FunctionalInterface interface ConnectionFactory { Connection open() throws SQLException; }
    @FunctionalInterface interface ConnectionConfigurer { void configure(Connection connection) throws SQLException; }
    interface Ownership {
        void verify() throws IOException;
        void release() throws IOException;
        void poison(Throwable failure);
    }
    record Snapshot(boolean enabled, int mutationScopes, int creations, int physicalConnections, int retirementHelpers,
                    boolean sealed, boolean ownershipReleased, boolean poisoned) {}
}
