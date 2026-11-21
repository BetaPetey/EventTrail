package io.github.betapetey.eventtrail.storage;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Statement;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A qualification-only, fail-closed observation of pgJDBC 42.7.13's first cancel.
 *
 * <p>The caller owns startup activation, authenticated driver bytes, a fresh
 * one-shot statement/execution identity, pre-force binding, and the exact first
 * production cancel call. This class never cancels, closes, waits, or controls
 * an execution. Its positive result excludes the bound timer as the cause; it
 * is not a complete lifecycle PASS or an acknowledgement from PostgreSQL.
 * Server/holder identity, competing actors, original terminal error, ordering,
 * retirement, accounting and cleanup still need independent evidence.</p>
 */
final class PgJdbcCancellationWitness {
    static final String DRIVER_VERSION = "42.7.13";

    private PgJdbcCancellationWitness() {}

    /** Value identities supplied by the activated, run-bound production recorder. */
    record Identity(long operationId, long generationId, long leaseId, long statementId,
                    long physicalConnectionId, int backendPid, String nonce) {
        private boolean valid() {
            // The canonical startup boundary, not this backend adapter, validates the nonce format.
            return operationId > 0 && generationId > 0 && leaseId > 0 && statementId > 0
                    && physicalConnectionId > 0 && backendPid > 0
                    && nonce != null && !nonce.isEmpty();
        }
    }

    enum Failure {
        NONE, DRIVER_IDENTITY_MISMATCH, DRIVER_ACCESS_UNAVAILABLE, INVALID_IDENTITY,
        STATEMENT_MISMATCH, CONNECTION_MISMATCH, BACKEND_SESSION_MISMATCH,
        STATE_NOT_EXECUTING, TIMER_ABSENT, MISSING_BINDING, ALREADY_OBSERVED,
        ABNORMAL_CANCEL_RETURN, INVALID_FORCE_EPOCH, BINDING_MISMATCH,
        REFERENT_COLLECTED, STATE_NOT_IDLE, TIMER_CHANGED
    }

    /** Contains no JDBC, timer, throwable, access mechanism or classloader reference. */
    record Result(Identity identity, long forceEpoch, Failure failure,
                  boolean stateIdle, boolean timerPresent, boolean sameTimer) {
        boolean timeoutCauseExcluded() {
            return identity != null && identity.valid() && forceEpoch > 0
                    && failure == Failure.NONE && stateIdle && timerPresent && sameTimer;
        }
    }

    record Prebind(Binding binding, Failure failure) {}

    /**
     * Setup is called only after qualification activation. The helper must have
     * authenticated the pinned binary and its containing artifact before passing
     * its actual Class objects and exact code-source value. Matching strings or
     * a version alone do not authenticate bytes. No hashing/file I/O occurs here.
     */
    static Access prepare(Class<?> statementClass, Class<?> preparedStatementClass,
                          Class<?> connectionClass, String authenticatedCodeSource) {
        try {
            if (!named(statementClass, "org.postgresql.jdbc.PgStatement")
                    || !named(preparedStatementClass, "org.postgresql.jdbc.PgPreparedStatement")
                    || !named(connectionClass, "org.postgresql.jdbc.PgConnection")
                    || !Statement.class.isAssignableFrom(preparedStatementClass)
                    || preparedStatementClass.getSuperclass() != statementClass
                    || authenticatedCodeSource == null || authenticatedCodeSource.isEmpty()) {
                return Access.unavailable(Failure.DRIVER_IDENTITY_MISMATCH);
            }
            ClassLoader loader = statementClass.getClassLoader();
            if (loader == null || !sameOrigin(statementClass, loader, authenticatedCodeSource)
                    || !sameOrigin(preparedStatementClass, loader, authenticatedCodeSource)
                    || !sameOrigin(connectionClass, loader, authenticatedCodeSource)) {
                return Access.unavailable(Failure.DRIVER_IDENTITY_MISMATCH);
            }
            Class<?> infoClass = Class.forName("org.postgresql.util.DriverInfo", false, loader);
            if (!sameOrigin(infoClass, loader, authenticatedCodeSource)
                    || !DRIVER_VERSION.equals(infoClass.getField("DRIVER_VERSION").get(null))) {
                return Access.unavailable(Failure.DRIVER_IDENTITY_MISMATCH);
            }

            Field stateField = statementClass.getDeclaredField("statementState");
            Field timerField = statementClass.getDeclaredField("cancelTimerTask");
            Class<?> stateClass = stateField.getType();
            if (!Modifier.isVolatile(stateField.getModifiers())
                    || !Modifier.isVolatile(timerField.getModifiers())
                    || timerField.getType() != TimerTask.class
                    || !named(stateClass, "org.postgresql.jdbc.StatementCancelState")
                    || !stateClass.isEnum() || !sameOrigin(stateClass, loader, authenticatedCodeSource)) {
                return Access.unavailable(Failure.DRIVER_IDENTITY_MISMATCH);
            }
            Object idle = null;
            Object inQuery = null;
            for (Object value : stateClass.getEnumConstants()) {
                String name = ((Enum<?>) value).name();
                if (name.equals("IDLE")) idle = value;
                if (name.equals("IN_QUERY")) inQuery = value;
            }
            if (idle == null || inQuery == null) {
                return Access.unavailable(Failure.DRIVER_IDENTITY_MISMATCH);
            }
            return new Access(preparedStatementClass, connectionClass,
                    updater(statementClass, "STATE_UPDATER"),
                    updater(statementClass, "CANCEL_TIMER_UPDATER"), idle, inQuery,
                    statementClass.getMethod("getConnection"),
                    connectionClass.getMethod("getBackendPID"), Failure.NONE);
        } catch (Throwable unavailable) {
            // Observation cannot replace an original JDBC exception or break shutdown.
            return Access.unavailable(Failure.DRIVER_ACCESS_UNAVAILABLE);
        }
    }

    private static boolean named(Class<?> type, String name) {
        return type != null && type.getName().equals(name);
    }

    private static boolean sameOrigin(Class<?> type, ClassLoader loader, String source) {
        return type.getClassLoader() == loader
                && type.getProtectionDomain().getCodeSource() != null
                && type.getProtectionDomain().getCodeSource().getLocation() != null
                && type.getProtectionDomain().getCodeSource().getLocation().toExternalForm().equals(source);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReferenceFieldUpdater<Object, Object> updater(Class<?> type, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                || field.getType() != AtomicReferenceFieldUpdater.class || !field.trySetAccessible()) {
            throw new IllegalAccessException("Pinned driver updater is unavailable");
        }
        Object value = field.get(null);
        if (!(value instanceof AtomicReferenceFieldUpdater<?, ?>)) {
            throw new IllegalAccessException("Pinned driver updater is unavailable");
        }
        return (AtomicReferenceFieldUpdater<Object, Object>) value;
    }

    /** Prepared reflection is used only during prebinding; hot reads use pgJDBC's own updaters. */
    static final class Access {
        private final Class<?> preparedStatementClass;
        private final Class<?> connectionClass;
        private final AtomicReferenceFieldUpdater<Object, Object> stateUpdater;
        private final AtomicReferenceFieldUpdater<Object, Object> timerUpdater;
        private final Object idle;
        private final Object inQuery;
        private final Method getConnection;
        private final Method getBackendPid;
        private final Failure failure;

        private Access(Class<?> preparedStatementClass, Class<?> connectionClass,
                       AtomicReferenceFieldUpdater<Object, Object> stateUpdater,
                       AtomicReferenceFieldUpdater<Object, Object> timerUpdater,
                       Object idle, Object inQuery, Method getConnection, Method getBackendPid,
                       Failure failure) {
            this.preparedStatementClass = preparedStatementClass;
            this.connectionClass = connectionClass;
            this.stateUpdater = stateUpdater;
            this.timerUpdater = timerUpdater;
            this.idle = idle;
            this.inQuery = inQuery;
            this.getConnection = getConnection;
            this.getBackendPid = getBackendPid;
            this.failure = failure;
        }

        private static Access unavailable(Failure failure) {
            return new Access(null, null, null, null, null, null, null, null, failure);
        }

        Failure failure() { return failure; }

        /**
         * Run once for a generation already authenticated as blocked, before
         * natural force/first cancel. The recorder supplies the authoritative
         * prebind ordering event; this method takes no timestamps or waits.
         */
        Prebind prebind(Statement selectedStatement, Identity identity) {
            if (failure != Failure.NONE) return new Prebind(null, failure);
            if (identity == null || !identity.valid()) return new Prebind(null, Failure.INVALID_IDENTITY);
            if (selectedStatement == null) return new Prebind(null, Failure.STATEMENT_MISMATCH);
            try {
                Object rawStatement = selectedStatement.unwrap(preparedStatementClass);
                if (rawStatement == null || rawStatement.getClass() != preparedStatementClass) {
                    return new Prebind(null, Failure.STATEMENT_MISMATCH);
                }
                Object connection = getConnection.invoke(rawStatement);
                if (connection == null || connection.getClass() != connectionClass) {
                    return new Prebind(null, Failure.CONNECTION_MISMATCH);
                }
                Object pid = getBackendPid.invoke(connection);
                if (!(pid instanceof Integer) || ((Integer) pid).intValue() != identity.backendPid()) {
                    return new Prebind(null, Failure.BACKEND_SESSION_MISMATCH);
                }
                Object state = stateUpdater.get(rawStatement);
                Object timer = timerUpdater.get(rawStatement);
                if (state != inQuery) return new Prebind(null, Failure.STATE_NOT_EXECUTING);
                if (!(timer instanceof TimerTask)) return new Prebind(null, Failure.TIMER_ABSENT);
                return new Prebind(new Binding(this, identity, selectedStatement, rawStatement,
                        connection, timer), Failure.NONE);
            } catch (Throwable unavailable) {
                return new Prebind(null, Failure.DRIVER_ACCESS_UNAVAILABLE);
            }
        }
    }

    /** TimerTask itself points strongly to PgStatement, so even the timer must be weakly retained. */
    static final class Binding {
        private final Access access;
        private final Identity identity;
        private final WeakReference<Statement> selectedStatement;
        private final WeakReference<Object> rawStatement;
        private final WeakReference<Object> connection;
        private final WeakReference<Object> timer;
        private final AtomicBoolean observed = new AtomicBoolean();

        private Binding(Access access, Identity identity, Statement selectedStatement,
                        Object rawStatement, Object connection, Object timer) {
            this.access = access;
            this.identity = identity;
            this.selectedStatement = new WeakReference<>(selectedStatement);
            this.rawStatement = new WeakReference<>(rawStatement);
            this.connection = new WeakReference<>(connection);
            this.timer = new WeakReference<>(timer);
        }
    }

    /**
     * Call immediately after the FIRST existing Statement.cancel, before close,
     * retry-worker creation or other diagnostic publication. Never poll/retry a
     * failed witness. The two driver reads below are deliberately unconditional
     * and ordered STATE THEN TIMER; combining/reversing them breaks the proof.
     */
    static Result afterFirstCancel(Binding binding, Statement selectedStatement,
                                   Identity identity, long forceEpoch, boolean returnedNormally) {
        if (binding == null) return failed(identity, forceEpoch, Failure.MISSING_BINDING);
        if (!binding.observed.compareAndSet(false, true)) {
            return failed(identity, forceEpoch, Failure.ALREADY_OBSERVED);
        }
        if (!returnedNormally) return failed(identity, forceEpoch, Failure.ABNORMAL_CANCEL_RETURN);
        Object raw = binding.rawStatement.get();
        if (raw == null) return failed(identity, forceEpoch, Failure.REFERENT_COLLECTED);
        try {
            Object state = binding.access.stateUpdater.get(raw);
            Object timer = binding.access.timerUpdater.get(raw);

            boolean stateIdle = state == binding.access.idle;
            boolean timerPresent = timer != null;
            boolean sameTimer = timerPresent && timer == binding.timer.get();
            Failure failure;
            if (forceEpoch <= 0) failure = Failure.INVALID_FORCE_EPOCH;
            else if (!binding.identity.equals(identity)) failure = Failure.BINDING_MISMATCH;
            else if (selectedStatement == null || selectedStatement != binding.selectedStatement.get()) {
                failure = Failure.STATEMENT_MISMATCH;
            } else if (binding.connection.get() == null) failure = Failure.REFERENT_COLLECTED;
            else if (!stateIdle) failure = Failure.STATE_NOT_IDLE;
            else if (!timerPresent) failure = Failure.TIMER_ABSENT;
            else if (!sameTimer) failure = Failure.TIMER_CHANGED;
            else failure = Failure.NONE;
            return new Result(identity, forceEpoch, failure, stateIdle, timerPresent, sameTimer);
        } catch (Throwable unavailable) {
            return failed(identity, forceEpoch, Failure.DRIVER_ACCESS_UNAVAILABLE);
        }
    }

    private static Result failed(Identity identity, long forceEpoch, Failure failure) {
        return new Result(identity, forceEpoch, failure, false, false, false);
    }
}
