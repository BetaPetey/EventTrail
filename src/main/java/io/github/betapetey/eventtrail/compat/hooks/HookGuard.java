package io.github.betapetey.eventtrail.compat.hooks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;

/**
 * The single boundary between an optional-mod hook handler and EventTrail.
 *
 * <p>Every compat hook handler hands its EventTrail work, and for wrap hooks
 * the mod's own call, to this class. The mod's call is held in a once-only
 * {@link Original}, so on every path the mod's code runs exactly once and its
 * result or exception comes back unchanged, whatever EventTrail's work does:</p>
 * <ul>
 *   <li>EventTrail fails before the mod's call: the guard makes the call;</li>
 *   <li>EventTrail fails after the mod's call: the guard returns the recorded
 *       result;</li>
 *   <li>the mod's call throws: the same exception object is re-thrown, and
 *       only the mod's own exception is ever re-thrown;</li>
 *   <li>the key is OFF or its hook family is stopped: only the mod's call runs
 *       (except through the denial entries, below).</li>
 * </ul>
 * <p>EventTrail failures are reported to {@link AdapterFailSafe} and never
 * escape. The value a wrap handler returns is always the mod's own recorded
 * result; EventTrail never changes it.</p>
 *
 * <p>Denial entries: {@link #wrapDenial} and {@link #observeDenial} are for
 * denial-only hooks, whose work only takes credit away (a negative frame, or an
 * exclusion of historical credit). Their body runs even while the key is OFF
 * or its hook family is stopped, because silencing a denial can turn a missing
 * row into a wrong one. Everything else is the same as {@link #wrap} and
 * {@link #observe}: failures are contained and reported, and the mod's call
 * runs exactly once. Only the denial-only hook classes may call them (checked
 * by the hook scan test).</p>
 *
 * <p>Every skip (a reported EventTrail failure, an invalid outcome, or EventTrail
 * failures attached to the mod's exception), for any key, also discards the
 * calling thread's open exact container action, if one is open, through
 * {@link OpenActionAbort}, so the enclosing bracket commits no part of that
 * action (lead decision L15 G1). A mod's own exception passing through is not a
 * skip.</p>
 *
 * <p>This class runs inside other mods' methods. It therefore has no static
 * initialiser and no static fields other than compile-time constants (the same
 * holds for {@link Original}), names no optional-mod class, and every
 * call into the rest of EventTrail is inside a catch-all, so even a failure to
 * initialise {@link AdapterFailSafe} cannot skip, repeat or replace the mod's
 * call. Handlers must not call {@code Operation.call} themselves; they pass
 * {@code original} here exactly once (checked by the hook scan test).</p>
 *
 * <p>Last resort: if the active check itself fails (for example
 * {@link AdapterFailSafe} cannot initialise), the hook runs only the mod's call
 * and records nothing, without a log line, because the logging path is the part
 * that failed. (The denial entries never consult that check, so their body still
 * runs; only their reports are lost.)</p>
 *
 * <p>Handler rules the guard cannot enforce:</p>
 * <ul>
 *   <li>{@link Original} stays on the hook's thread. If a body hands it to
 *       another thread and returns while that call is still running, the guard
 *       never calls the mod a second time: it reports the contract break and
 *       returns {@code null}, which a handler of an {@code Operation<Boolean>} or
 *       other primitive-returning operation unboxes into a
 *       {@link NullPointerException} inside the mod's method.</li>
 *   <li>The public {@code wrap} takes the mod's arguments as varargs: pass a
 *       single array-typed or {@code null} argument as {@code (Object) x}, or it
 *       is spread into, or replaced by, an empty argument list.</li>
 *   <li>A body that puts EventTrail work in a try-with-resources around
 *       {@code original.call()} lets Java attach a {@code close()} failure to the
 *       mod's own exception as a suppressed exception. The guard reports that
 *       failure, but cannot detach it from the mod's exception; prefer closing
 *       the EventTrail boundary outside the mod's call.</li>
 * </ul>
 */
public final class HookGuard {
    static final String SKIPPED_MOD_CALL = "hook-body-skipped-mod-call";
    static final String SWALLOWED_MOD_FAILURE = "hook-body-swallowed-mod-failure";
    static final String UNFINISHED_MOD_CALL = "hook-mod-call-unfinished";

    private static final int NOT_CALLED = 0;
    private static final int CALLING = 1;
    private static final int RETURNED = 2;
    private static final int THREW = 3;

    private HookGuard() {
    }

    /** EventTrail's work around the mod's call in a {@code @WrapMethod} or {@code @WrapOperation} handler. */
    @FunctionalInterface
    public interface WrapBody<R> {
        void run(Original<R> original) throws Throwable;
    }

    /** EventTrail's work in an {@code @Inject} handler (observation only). */
    @FunctionalInterface
    public interface InjectBody {
        void run() throws Throwable;
    }

    /** EventTrail's decision in an {@code @Inject} handler that may cancel. */
    @FunctionalInterface
    public interface Decision {
        boolean decide() throws Throwable;
    }

    /** What the guard does on its edges; the public methods use {@link AdapterFailSafe}. */
    interface Policy {
        boolean active(AdapterKey key);

        void report(AdapterKey key, String site, Throwable failure);

        void invalid(AdapterKey key, String site, String reasonCode);
    }

    /**
     * The mod's call, at most once. A second {@link #call()} returns the recorded
     * result, or re-throws the recorded exception, without calling the mod again.
     * Confined to the hook's own thread.
     */
    public static final class Original<R> {
        private final Operation<R> operation;
        private final Object[] args;
        private int state = NOT_CALLED;
        private R result;
        private Throwable failure;
        private int suppressedAtFailure;

        Original(Operation<R> operation, Object[] args) {
            this.operation = operation;
            this.args = args;
        }

        public R call() {
            if (state == RETURNED) return result;
            if (state == THREW) throw sneaky(failure);
            if (state == CALLING) throw new IllegalStateException("The mod's call is already running");
            state = CALLING;
            try {
                R value = operation.call(args);
                result = value;
                state = RETURNED;
                return value;
            } catch (Throwable modFailure) {
                failure = modFailure;
                suppressedAtFailure = suppressedCount(modFailure);
                state = THREW;
                throw sneaky(modFailure);
            }
        }

        /** Whether the mod's call has run (returned or thrown). */
        public boolean called() {
            return state == RETURNED || state == THREW;
        }
    }

    /**
     * Guards a wrap handler. {@code args} are the arguments the handler would
     * have passed to {@code original.call(...)}; the mod's call runs exactly once
     * with them and its result is returned (null for {@code Operation<Void>}).
     */
    public static <R> R wrap(
            AdapterKey key, String site, Operation<R> original, WrapBody<R> body, Object... args) {
        return wrap(null, key, site, original, body, args);
    }

    /** Guards an observing {@code @Inject} handler; nothing escapes. */
    public static void observe(AdapterKey key, String site, InjectBody body) {
        observe(null, key, site, body);
    }

    /**
     * Guards a cancelling {@code @Inject} handler. Returns the body's decision
     * only if the body completed; false if the key is not active or the body
     * failed. The handler applies {@code ci.cancel()} itself, after this returns
     * true, so a cancel is never half-applied.
     */
    public static boolean decide(AdapterKey key, String site, Decision body) {
        return decide(null, key, site, body);
    }

    /**
     * Guards a wrap handler of a denial-only hook. The same as
     * {@link #wrap(AdapterKey, String, Operation, WrapBody, Object...)}, except
     * that the body also runs while {@code key} is OFF or its hook family is
     * stopped (class note). Failures still go to {@link AdapterFailSafe}.
     */
    public static <R> R wrapDenial(
            AdapterKey key, String site, Operation<R> original, WrapBody<R> body, Object... args) {
        return wrap(new DenialPolicy(null), key, site, original, body, args);
    }

    /**
     * Guards an observing {@code @Inject} handler of a denial-only hook; the
     * body also runs while {@code key} is OFF or its hook family is stopped.
     * Nothing escapes.
     */
    public static void observeDenial(AdapterKey key, String site, InjectBody body) {
        observe(new DenialPolicy(null), key, site, body);
    }

    /** {@link #wrapDenial} with the failure edges of {@code edges} (its active check is ignored). */
    static <R> R wrapDenial(
            Policy edges,
            AdapterKey key,
            String site,
            Operation<R> original,
            WrapBody<R> body,
            Object[] args) {
        return wrap(new DenialPolicy(edges), key, site, original, body, args);
    }

    /** {@link #observeDenial} with the failure edges of {@code edges} (its active check is ignored). */
    static void observeDenial(Policy edges, AdapterKey key, String site, InjectBody body) {
        observe(new DenialPolicy(edges), key, site, body);
    }

    static <R> R wrap(
            Policy policy,
            AdapterKey key,
            String site,
            Operation<R> original,
            WrapBody<R> body,
            Object[] args) {
        Object[] callArgs = args == null ? new Object[0] : args;
        if (body == null || !isActive(policy, key)) return original.call(callArgs);

        Original<R> once = new Original<>(original, callArgs);
        Throwable eventTrailFailure = null;
        boolean bodyCompleted = false;
        try {
            body.run(once);
            bodyCompleted = true;
        } catch (Throwable thrown) {
            if (!(once.state == THREW && thrown == once.failure)) eventTrailFailure = thrown;
        }

        if (once.state == RETURNED) {
            if (eventTrailFailure != null) report(policy, key, site, eventTrailFailure);
            return once.result;
        }
        if (once.state == THREW) {
            if (eventTrailFailure != null) {
                report(policy, key, site, eventTrailFailure);
            } else if (bodyCompleted) {
                invalid(policy, key, site, SWALLOWED_MOD_FAILURE);
            }
            reportAttachedFailures(policy, key, site, once);
            throw sneaky(once.failure);
        }
        if (once.state == CALLING) {
            // Only reachable if the body handed the call to another thread; never call twice. A handler of a
            // primitive-returning operation unboxes this null into a NullPointerException (class doc).
            invalid(policy, key, site, UNFINISHED_MOD_CALL);
            return null;
        }
        if (eventTrailFailure != null) {
            report(policy, key, site, eventTrailFailure);
        } else {
            invalid(policy, key, site, SKIPPED_MOD_CALL);
        }
        // Through the once-only wrapper, so a handle the body leaked returns this outcome later instead of
        // calling the mod a second time (review-01 m2). The mod's exception is re-thrown unchanged.
        return once.call();
    }

    static void observe(Policy policy, AdapterKey key, String site, InjectBody body) {
        if (body == null || !isActive(policy, key)) return;
        try {
            body.run();
        } catch (Throwable failure) {
            report(policy, key, site, failure);
        }
    }

    static boolean decide(Policy policy, AdapterKey key, String site, Decision body) {
        if (body == null || !isActive(policy, key)) return false;
        try {
            return body.decide();
        } catch (Throwable failure) {
            report(policy, key, site, failure);
            return false;
        }
    }

    /**
     * Reports every exception attached to the mod's exception as suppressed
     * after the mod threw it. Only EventTrail's body runs between the mod's
     * throw and here, so each one is an EventTrail failure, typically a
     * {@code close()} of a try-with-resources around the mod's call (review-01
     * m3). Java offers no way to detach them from the mod's exception.
     */
    private static void reportAttachedFailures(Policy policy, AdapterKey key, String site, Original<?> once) {
        Throwable[] attached;
        try {
            attached = once.failure.getSuppressed();
        } catch (Throwable unreadable) {
            return;
        }
        if (once.suppressedAtFailure >= attached.length) return;
        abortOpenAction();
        for (int index = once.suppressedAtFailure; index < attached.length; index++) {
            notifyReport(policy, key, site, attached[index]);
        }
    }

    private static int suppressedCount(Throwable failure) {
        try {
            return failure.getSuppressed().length;
        } catch (Throwable unreadable) {
            // Never replace the mod's exception; report nothing attached later.
            return Integer.MAX_VALUE;
        }
    }

    private static boolean isActive(Policy policy, AdapterKey key) {
        try {
            return policy == null ? AdapterFailSafe.active(key) : policy.active(key);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static void report(Policy policy, AdapterKey key, String site, Throwable failure) {
        abortOpenAction();
        notifyReport(policy, key, site, failure);
    }

    private static void notifyReport(Policy policy, AdapterKey key, String site, Throwable failure) {
        try {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (policy == null) {
                AdapterFailSafe.report(key, site, failure);
            } else {
                policy.report(key, site, failure);
            }
        } catch (Throwable ignored) {
            // Reporting must never replace or repeat the mod's call.
        }
    }

    private static void invalid(Policy policy, AdapterKey key, String site, String reasonCode) {
        abortOpenAction();
        try {
            if (policy == null) {
                AdapterFailSafe.invalid(key, site, reasonCode);
            } else {
                policy.invalid(key, site, reasonCode);
            }
        } catch (Throwable ignored) {
            // Never throws.
        }
    }

    /**
     * A skip: discards the calling thread's open exact action, if any, before the
     * skip is reported, so the discard does not depend on reporting working.
     */
    private static void abortOpenAction() {
        try {
            OpenActionAbort.abortOnCurrentThread();
        } catch (Throwable ignored) {
            // Never replace or repeat the mod's call.
        }
    }

    /**
     * The policy of the denial entries: always active (it ignores OFF and the
     * family gate), and its failure edges are {@link AdapterFailSafe}'s, or
     * those of a test policy. No static state, like {@link Original}.
     */
    private static final class DenialPolicy implements Policy {
        private final Policy edges;

        DenialPolicy(Policy edges) {
            this.edges = edges;
        }

        @Override
        public boolean active(AdapterKey key) {
            return true;
        }

        @Override
        public void report(AdapterKey key, String site, Throwable failure) {
            if (edges == null) {
                AdapterFailSafe.report(key, site, failure);
            } else {
                edges.report(key, site, failure);
            }
        }

        @Override
        public void invalid(AdapterKey key, String site, String reasonCode) {
            if (edges == null) {
                AdapterFailSafe.invalid(key, site, reasonCode);
            } else {
                edges.invalid(key, site, reasonCode);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }
}
