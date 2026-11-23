package io.github.betapetey.eventtrail.storage;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * INTERNAL, startup-only qualification flight recorder; not public configuration
 * or a shutdown control API. Ports preserve this activation/wire contract.
 * All observation is bounded and optional. No observer is called by storage.
 */
public final class JdbcShutdownDiagnostics {
    public static final String ACTIVATION_PROPERTY = "eventtrail.internal.jdbcDiagnosticsNonce";
    public static final String SCHEMA = "eventtrail-jdbc-shutdown-diagnostics-v1";
    private static final String STARTUP_NONCE = startupNonce();
    private static final Recorder ACTIVE = isValidNonce(STARTUP_NONCE)
            ? new Recorder(STARTUP_NONCE, 8192, 2048) : null;

    private JdbcShutdownDiagnostics() {}

    /** Called by the port bootstrap before configuration/startup events. No runtime setter exists. */
    public static void initialize() { /* Class initialization freezes activation exactly once. */ }
    public static boolean enabled() { return ACTIVE != null; }
    public static String nonce() { return ACTIVE == null ? null : ACTIVE.nonce; }
    public static boolean malformedActivation() { return STARTUP_NONCE != null && ACTIVE == null; }

    private static String startupNonce() {
        try { return System.getProperty(ACTIVATION_PROPERTY); }
        catch (SecurityException denied) { return ""; }
    }

    /** Syntax/common-value guard, not an entropy estimator. The launcher supplies 16 RNG bytes. */
    public static boolean isValidNonce(String value) {
        if (value == null || value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        for (int period = 1; period <= 16; period *= 2) {
            boolean repeated = true;
            for (int i = period; i < 32; i++) {
                if (value.charAt(i) != value.charAt(i % period)) { repeated = false; break; }
            }
            if (repeated) return false;
        }
        return !value.equals("00112233445566778899aabbccddeeff")
                && !value.equals("ffeeddccbbaa99887766554433221100");
    }

    public enum Kind { REGISTERED, EXECUTION_ENTER, EXECUTION_RETURN, EXECUTION_NOT_ENTERED, READ_SCOPE_ENTER, READ_SCOPE_RETURN, TIMER_BOUND,
        FORCE_BOUNDARY, CANCEL_TARGET, CANCEL_RESULT, CANCELLATION_SETTLED, LEASE_RETIRED }
    public enum Failure { NONE, CAPACITY, OBSERVATION, DUPLICATE_ENTER, DUPLICATE_RETURN,
        NONCE_MISMATCH, REUSED_STATEMENT, INVALID_TIMER_BINDING, NESTED_CANCEL_CONTEXT,
        TERMINAL_CHAIN_TRUNCATED, TERMINAL_CHAIN_CYCLE }

    /** Value identities only. driverStatementId is our weak-identity key for the raw Query/Statement object;
     * Connector/J Query.getId() is NOT identity authority (it can remain zero with profiling disabled). */
    public record Ids(long operationId, long generationId, long leaseId, long statementId,
                      long physicalConnectionId, long backendSessionId, long driverStatementId,
                      String backend, String role) {}
    public record CancelContext(String nonce, long operationId, long generationId, long leaseId,
                                long statementId, long physicalConnectionId, long backendSessionId,
                                long forceEpoch, long driverStatementId, int attempt, long threadId) {}
    public record SqlFailure(String type, String sqlState, int vendorCode,
                             boolean postgreSqlServerUserCancellation) {}
    public record PgProof(long timerBindingId, String failure, boolean stateIdle,
                          boolean timerPresent, boolean sameTimer, String readOrder) {}
    public record Detail(String outcome, int timeoutSeconds, long deadlineNanos,
                         long timerBindingId, boolean claimed, int attempts, boolean closed,
                         List<SqlFailure> sqlFailures, PgProof pgProof) {
        static Detail simple(String outcome) {
            return new Detail(outcome, 0, 0, 0, false, 0, false, List.of(), null);
        }
    }
    public record Event(Kind kind, long sequence, long nanoTime, long epochMillis, Ids ids,
                        long forceEpoch, int attempt, long threadId, String mechanism, Detail detail) {}
    public record Snapshot(String schema, String nonce, Failure failure, long reservedRecords,
                           boolean complete, List<Event> records) {}
    public record TimerBinding(long generationId, long bindingId, boolean bound, String failure) {}

    public static CancelContext currentCancelContext() {
        return ACTIVE == null ? null : ACTIVE.cancelContext.get();
    }
    public static Snapshot snapshot() { return ACTIVE == null ? null : ACTIVE.snapshot(); }
    /** Native OTel timeout origin may look up an already-registered exact Query object, never register one. */
    public static long queryIdentity(Object nativeQueryTarget) {
        return ACTIVE == null ? 0 : ACTIVE.identities.find(nativeQueryTarget);
    }

    /** Qualification prepares authenticated driver access before any relevant execution, never in cancel. */
    public static boolean preparePostgreSql(Class<?> statement, Class<?> prepared,
                                           Class<?> connection, String authenticatedCodeSource) {
        if (ACTIVE == null) return false;
        if (ACTIVE.generations.get() != 0 || ACTIVE.pgAccess != null) {
            ACTIVE.fail(Failure.OBSERVATION); return false;
        }
        ACTIVE.pgAccess = PgJdbcCancellationWitness.prepare(statement, prepared, connection, authenticatedCodeSource);
        if (ACTIVE.pgAccess.failure() != PgJdbcCancellationWitness.Failure.NONE) {
            ACTIVE.fail(Failure.OBSERVATION); return false;
        }
        return true;
    }

    /** Read-only relationship lookup; no statement registration or execution control. */
    public static long generationFor(Statement statement) {
        if (ACTIVE == null) return 0;
        long statementId = ACTIVE.identities.find(statement);
        long found = 0;
        for (int i = 0; i < ACTIVE.executions.length(); i++) {
            Execution execution = ACTIVE.executions.get(i);
            if (execution != null && execution.ids.statementId() == statementId && statementId != 0) {
                if (found != 0) { ACTIVE.fail(Failure.REUSED_STATEMENT); return 0; }
                found = execution.ids.generationId();
            }
        }
        return found;
    }

    /** Passive prebinding after server-side authentication; it cannot hold the JDBC operation open. */
    public static TimerBinding prebindPostgreSql(Statement statement, long generationId) {
        if (ACTIVE == null) return new TimerBinding(generationId, 0, false, "DISABLED");
        try {
            Execution execution = ACTIVE.execution(generationId);
            if (execution == null || ACTIVE.pgAccess == null || execution.returned
                    || execution.enterSequence == 0 || execution.operation.forceEpoch != 0
                    || generationFor(statement) != generationId || execution.pgBinding != null) {
                return new TimerBinding(generationId, 0, false, "INVALID_PREBIND_STATE");
            }
            PgJdbcCancellationWitness.Prebind prebind = ACTIVE.pgAccess.prebind(statement, execution.pgIdentity());
            if (prebind.binding() == null) {
                return new TimerBinding(generationId, 0, false, prebind.failure().name());
            }
            execution.timerBindingId = ACTIVE.identities.next();
            execution.pgBinding = prebind.binding();
            ACTIVE.emit(Kind.TIMER_BOUND, execution, 0, 0, "POSTGRESQL_TIMER",
                    new Detail("PREBOUND", 0, 0, execution.timerBindingId, false, 0, false, List.of(), null));
            return new TimerBinding(generationId, execution.timerBindingId, true, "NONE");
        } catch (Throwable failure) {
            ACTIVE.fail(Failure.OBSERVATION);
            return new TimerBinding(generationId, 0, false, "OBSERVATION_FAILED");
        }
    }

    static Operation operation(Object owner, String role) {
        if (ACTIVE == null) return null;
        try { return new Operation(ACTIVE, ACTIVE.identities.id(owner), role); }
        catch (Throwable failure) { ACTIVE.fail(Failure.OBSERVATION); return null; }
    }
    static Execution register(Operation operation, Object lease, Statement statement, Connection connection) {
        if (operation == null) return null;
        try { return operation.recorder.register(operation, lease, statement, connection); }
        catch (Throwable failure) { operation.recorder.fail(Failure.OBSERVATION); return null; }
    }
    static void enter(Execution execution, int timeoutSeconds) {
        if (execution == null) return;
        try {
            if (execution.enterSequence != 0) { execution.recorder().fail(Failure.DUPLICATE_ENTER); return; }
            execution.enterSequence = execution.recorder().emit(execution.ids.role().equals("READ")
                    ? Kind.READ_SCOPE_ENTER : Kind.EXECUTION_ENTER, execution, 0, 0, "JDBC_EXECUTE",
                    new Detail("ENTER", timeoutSeconds, 0, 0, false, 0, false, List.of(), null));
        } catch (Throwable failure) { execution.recorder().fail(Failure.OBSERVATION); }
    }
    static void returned(Execution execution, Throwable failure) {
        if (execution == null) return;
        try {
            if (execution.returned) { execution.recorder().fail(Failure.DUPLICATE_RETURN); return; }
            execution.returned = true;
            execution.recorder().emit(execution.ids.role().equals("READ") ? Kind.READ_SCOPE_RETURN : Kind.EXECUTION_RETURN,
                    execution, execution.operation.forceEpoch, 0,
                    "JDBC_EXECUTE", terminal(failure, execution.recorder()));
        } catch (Throwable observationFailure) { execution.recorder().fail(Failure.OBSERVATION); }
    }
    static void force(Operation operation, long deadlineNanos, boolean interrupted) {
        if (operation == null) return;
        try {
            long epoch = operation.recorder.identities.next();
            operation.forceEpoch = epoch;
            operation.recorder.emit(Kind.FORCE_BOUNDARY, operation.latest, epoch, 0, "NATURAL_SHUTDOWN",
                    new Detail(interrupted ? "INTERRUPTED" : "DEADLINE", 0, deadlineNanos, 0,
                            false, 0, false, List.of(), null));
        } catch (Throwable failure) { operation.recorder.fail(Failure.OBSERVATION); }
    }
    static CancelCall beginCancel(Execution execution, int attempt) {
        if (execution == null) return null;
        Recorder recorder = execution.recorder();
        try {
            if (recorder.cancelContext.get() != null) recorder.fail(Failure.NESTED_CANCEL_CONTEXT);
            Ids ids = execution.ids;
            CancelContext context = new CancelContext(recorder.nonce, ids.operationId(), ids.generationId(),
                    ids.leaseId(), ids.statementId(), ids.physicalConnectionId(), ids.backendSessionId(),
                    execution.operation.forceEpoch, ids.driverStatementId(), attempt, Thread.currentThread().threadId());
            recorder.emit(Kind.CANCEL_TARGET, execution, context.forceEpoch(), attempt, "STATEMENT_CANCEL", Detail.simple("SELECTED"));
            recorder.cancelContext.set(context);
            return new CancelCall(execution, context);
        } catch (Throwable failure) { recorder.fail(Failure.OBSERVATION); recorder.cancelContext.remove(); return null; }
    }
    /** FIRST normal return: the ordered driver reads precede recording, retries, close, and context removal. */
    static void cancelReturned(CancelCall call, Statement selectedStatement, Throwable failure) {
        if (call == null) return;
        Execution execution = call.execution;
        try {
            PgProof pg = null;
            if (call.context.attempt() == 1 && execution.ids.backend().equals("postgresql")) {
                PgJdbcCancellationWitness.Result result = PgJdbcCancellationWitness.afterFirstCancel(
                        execution.pgBinding, selectedStatement, execution.pgIdentity(), call.context.forceEpoch(), failure == null);
                pg = new PgProof(execution.timerBindingId, result.failure().name(), result.stateIdle(),
                        result.timerPresent(), result.sameTimer(), "STATE_THEN_TIMER");
            }
            Detail terminal = terminal(failure, execution.recorder());
            execution.recorder().emit(Kind.CANCEL_RESULT, execution, call.context.forceEpoch(), call.context.attempt(),
                    "STATEMENT_CANCEL", new Detail(failure == null ? "NORMAL_RETURN" : "THREW", 0, 0,
                            execution.timerBindingId, false, 0, false, terminal.sqlFailures(), pg));
        } catch (Throwable observationFailure) { execution.recorder().fail(Failure.OBSERVATION); }
    }
    static void endCancel(CancelCall call) {
        if (call != null) call.execution.recorder().cancelContext.remove();
    }
    static void settled(Execution execution, JdbcStatementCancellation.Result result) {
        if (execution == null) return;
        try {
            if (result == null) { execution.recorder().fail(Failure.OBSERVATION); return; }
            execution.recorder().emit(Kind.CANCELLATION_SETTLED, execution, execution.operation.forceEpoch, 0,
                    "STATEMENT_CANCEL_AND_CLOSE", new Detail(result.failure() == null ? "NORMAL" : "DRIVER_FAILURE",
                            0, 0, 0, result.claimed(), result.cancelAttempts(), result.closed(),
                            terminal(result.failure(), execution.recorder()).sqlFailures(), null));
        } catch (Throwable failure) { execution.recorder().fail(Failure.OBSERVATION); }
    }
    static void retired(Execution execution) {
        if (execution == null) return;
        try {
            if (execution.enterSequence == 0) execution.recorder().emit(Kind.EXECUTION_NOT_ENTERED,
                    execution, execution.operation.forceEpoch, 0, "STATEMENT_LEASE", Detail.simple("NOT_ENTERED"));
            execution.recorder().emit(Kind.LEASE_RETIRED, execution, execution.operation.forceEpoch, 0,
                    "LEASE_RETIRE", Detail.simple("RETIRED"));
        }
        catch (Throwable failure) { execution.recorder().fail(Failure.OBSERVATION); }
    }

    static CancelCall abortTarget(Operation operation, Connection connection) {
        if (operation == null) return null;
        try {
            NativeIdentity nativeId = nativeIdentity(null, connection);
            long physical = operation.recorder.identities.id(nativeId.connection);
            Execution execution = operation.latest;
            if (execution == null || execution.ids.physicalConnectionId() != physical) {
                execution = new Execution(operation, new Ids(operation.id, 0, 0, 0, physical,
                        nativeId.sessionId, 0, nativeId.backend, operation.role));
            }
            Ids ids = execution.ids;
            CancelContext context = new CancelContext(operation.recorder.nonce, ids.operationId(), ids.generationId(),
                    ids.leaseId(), ids.statementId(), physical, ids.backendSessionId(), operation.forceEpoch,
                    ids.driverStatementId(), 0, Thread.currentThread().threadId());
            operation.recorder.emit(Kind.CANCEL_TARGET, execution, context.forceEpoch(), 0,
                    "CONNECTION_ABORT", Detail.simple("SELECTED"));
            return new CancelCall(execution, context);
        } catch (Throwable failure) { operation.recorder.fail(Failure.OBSERVATION); return null; }
    }
    static void abortReturned(CancelCall call, AbstractJdbcStorage.ConnectionAbortResult result) {
        if (call == null) return;
        try {
            call.execution.recorder().emit(Kind.CANCEL_RESULT, call.execution, call.context.forceEpoch(), 0,
                    "CONNECTION_ABORT", new Detail("abortReturned=" + result.abortReturned()
                            + ";closedByAbort=" + result.closedByAbort() + ";closeInvoked=" + result.closeInvoked(),
                            0, 0, 0, true, 1, result.closed(), terminal(result.failure(), call.execution.recorder()).sqlFailures(), null));
        } catch (Throwable failure) { call.execution.recorder().fail(Failure.OBSERVATION); }
    }

    private static Detail terminal(Throwable error, Recorder recorder) throws ReflectiveOperationException {
        if (error == null) return Detail.simple("SUCCESS");
        List<SqlFailure> sql = new ArrayList<>();
        terminalNode(error, recorder, new IdentityHashMap<>(), sql);
        return new Detail(error instanceof SQLException ? "SQL_EXCEPTION" : error.getClass().getName(),
                0, 0, 0, false, 0, false, List.copyOf(sql), null);
    }

    private static void terminalNode(Throwable current, Recorder recorder,
                                     IdentityHashMap<Throwable, Boolean> states, List<SqlFailure> sql)
            throws ReflectiveOperationException {
        if (current == null) return;
        Boolean previous = states.get(current);
        if (previous != null) {
            if (!previous) recorder.fail(Failure.TERMINAL_CHAIN_CYCLE);
            return; // A completed node aliased by cause/next is not a cycle or another allocation.
        }
        if (states.size() >= 32) { recorder.fail(Failure.TERMINAL_CHAIN_TRUNCATED); return; }
        states.put(current, false);
        if (current instanceof SQLException failure) {
            boolean serverCancel = false;
            if (current.getClass().getName().equals("org.postgresql.util.PSQLException")) {
                Object server = current.getClass().getMethod("getServerErrorMessage").invoke(current);
                if (server != null) {
                    Object message = server.getClass().getMethod("getMessage").invoke(server);
                    serverCancel = "57014".equals(failure.getSQLState())
                            && "canceling statement due to user request".equals(message);
                }
            }
            sql.add(new SqlFailure(current.getClass().getName(), failure.getSQLState(), failure.getErrorCode(), serverCancel));
            terminalNode(failure.getNextException(), recorder, states, sql);
        }
        terminalNode(current.getCause(), recorder, states, sql);
        Throwable[] suppressed = current.getSuppressed();
        if (suppressed.length > 32) recorder.fail(Failure.TERMINAL_CHAIN_TRUNCATED);
        for (int i = 0; i < Math.min(suppressed.length, 32); i++) terminalNode(suppressed[i], recorder, states, sql);
        states.put(current, true);
    }

    static final class Operation {
        final Recorder recorder; final long id; final String role;
        volatile long forceEpoch; volatile Execution latest;
        Operation(Recorder recorder, long id, String role) { this.recorder = recorder; this.id = id; this.role = role; }
    }
    static final class Execution {
        final Operation operation; final Ids ids;
        volatile long enterSequence; volatile boolean returned;
        volatile PgJdbcCancellationWitness.Binding pgBinding; volatile long timerBindingId;
        Execution(Operation operation, Ids ids) { this.operation = operation; this.ids = ids; }
        Recorder recorder() { return operation.recorder; }
        PgJdbcCancellationWitness.Identity pgIdentity() {
            return new PgJdbcCancellationWitness.Identity(ids.operationId(), ids.generationId(), ids.leaseId(),
                    ids.statementId(), ids.physicalConnectionId(), Math.toIntExact(ids.backendSessionId()), recorder().nonce);
        }
    }
    static final class CancelCall {
        final Execution execution; final CancelContext context;
        CancelCall(Execution execution, CancelContext context) { this.execution = execution; this.context = context; }
    }
    static final class Recorder {
        final String nonce;
        final AtomicReferenceArray<Event> records;
        final AtomicReferenceArray<Execution> executions;
        final AtomicLong reserved = new AtomicLong();
        final AtomicLong generations = new AtomicLong();
        final AtomicReference<Failure> failure = new AtomicReference<>(Failure.NONE);
        final IdentityTable identities;
        // Deliberately NOT InheritableThreadLocal: timeout worker threads never inherit attribution.
        final ThreadLocal<CancelContext> cancelContext = new ThreadLocal<>();
        volatile PgJdbcCancellationWitness.Access pgAccess;
        Recorder(String nonce, int capacity, int identityCapacity) {
            if (!isValidNonce(nonce) || capacity < 1 || identityCapacity < 1) throw new IllegalArgumentException();
            this.nonce = nonce; records = new AtomicReferenceArray<>(capacity);
            executions = new AtomicReferenceArray<>(capacity);
            identities = new IdentityTable(identityCapacity, this);
        }
        void fail(Failure reason) { failure.compareAndSet(Failure.NONE, reason); }
        Execution execution(long generation) {
            return generation > 0 && generation <= executions.length() ? executions.get((int) generation - 1) : null;
        }
        Execution register(Operation operation, Object lease, Statement statement, Connection connection) throws Exception {
            NativeIdentity nativeIdentity = nativeIdentity(statement, connection);
            return registerIdentities(operation, identities.id(lease), identities.id(statement),
                    identities.id(nativeIdentity.connection), nativeIdentity.sessionId,
                    nativeIdentity.statement == null ? 0 : identities.id(nativeIdentity.statement), nativeIdentity.backend);
        }
        Execution registerIdentities(Operation operation, long lease, long statement, long connection,
                                     long session, long nativeStatement, String backend) {
            long generation = generations.incrementAndGet();
            if (generation > executions.length()) { fail(Failure.CAPACITY); return null; }
            Ids ids = new Ids(operation.id, generation, lease, statement, connection, session, nativeStatement, backend, operation.role);
            Execution execution = new Execution(operation, ids);
            executions.set((int) generation - 1, execution);
            operation.latest = execution;
            emit(Kind.REGISTERED, execution, operation.forceEpoch, 0, "STATEMENT_LEASE", Detail.simple("REGISTERED"));
            return execution;
        }
        long emit(Kind kind, Execution execution, long forceEpoch, int attempt, String mechanism, Detail detail) {
            long index = reserved.getAndIncrement();
            if (index >= records.length()) { fail(Failure.CAPACITY); return 0; }
            Event event = new Event(kind, index + 1, System.nanoTime(), System.currentTimeMillis(),
                    execution == null ? null : execution.ids, forceEpoch, attempt, Thread.currentThread().threadId(), mechanism, detail);
            records.set((int) index, event);
            return index + 1;
        }
        Snapshot snapshot() {
            long count = reserved.get();
            boolean complete = count <= records.length();
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < Math.min(count, records.length()); i++) {
                Event event = records.get(i);
                if (event == null) complete = false; else events.add(event);
            }
            return new Snapshot(SCHEMA, nonce, failure.get(), count, complete, List.copyOf(events));
        }
    }

    /** Fixed weak identity table: no identity hash is accepted as unique and no JDBC object is kept alive. */
    static final class IdentityTable {
        record Entry(WeakReference<Object> object, long id) {}
        final AtomicReferenceArray<Entry> entries; final AtomicLong sequence = new AtomicLong(); final Recorder recorder;
        IdentityTable(int capacity, Recorder recorder) { entries = new AtomicReferenceArray<>(capacity); this.recorder = recorder; }
        long next() { return sequence.incrementAndGet(); }
        long find(Object object) {
            if (object == null) return 0;
            int start = Math.floorMod(System.identityHashCode(object), entries.length());
            for (int n = 0; n < entries.length(); n++) {
                Entry entry = entries.get((start + n) % entries.length());
                if (entry == null) return 0;
                if (entry.object().get() == object) return entry.id();
            }
            return 0;
        }
        long id(Object object) {
            if (object == null) { recorder.fail(Failure.OBSERVATION); return 0; }
            int start = Math.floorMod(System.identityHashCode(object), entries.length());
            for (int n = 0; n < entries.length(); n++) {
                int slot = (start + n) % entries.length();
                Entry current = entries.get(slot);
                if (current == null) {
                    Entry created = new Entry(new WeakReference<>(object), next());
                    if (entries.compareAndSet(slot, null, created)) return created.id();
                    current = entries.get(slot);
                }
                if (current.object().get() == object) return current.id();
            }
            recorder.fail(Failure.CAPACITY); return 0;
        }
    }
    private record NativeIdentity(Object connection, long sessionId, Object statement, String backend) {}
    private static NativeIdentity nativeIdentity(Statement statement, Connection connection) throws Exception {
        Connection logical = connection == null ? statement.getConnection() : connection;
        ClassLoader loader = JdbcShutdownDiagnostics.class.getClassLoader();
        for (String backend : new String[] {"postgresql", "mysql"}) {
            String connectionType = backend.equals("postgresql") ? "org.postgresql.jdbc.PgConnection" : "com.mysql.cj.jdbc.ConnectionImpl";
            Class<?> type = Class.forName(connectionType, false, loader);
            if (!logical.isWrapperFor(type)) continue;
            Object physical = logical.unwrap(type);
            long session = ((Number) type.getMethod(backend.equals("postgresql") ? "getBackendPID" : "getId").invoke(physical)).longValue();
            Object query = null;
            if (backend.equals("mysql") && statement != null) {
                Class<?> statementType = Class.forName("com.mysql.cj.jdbc.StatementImpl", false, loader);
                query = statement.unwrap(statementType);
            }
            return new NativeIdentity(physical, session, query, backend);
        }
        throw new IllegalArgumentException("Internal JDBC diagnostics require the qualified external driver");
    }
}
