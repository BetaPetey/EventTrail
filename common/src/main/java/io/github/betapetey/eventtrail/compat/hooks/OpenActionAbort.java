package io.github.betapetey.eventtrail.compat.hooks;

import java.util.Objects;

/**
 * The compat-side point through which a guarded hook skip discards the calling
 * thread's open exact container action (lead decision L15 G1).
 *
 * <p>{@code compat} does not depend on {@code events}, so the container tracker
 * registers its abort here from its own static initialiser. Until it does,
 * nothing is registered and a skip has nothing to discard: an exact action can
 * only be opened by the tracker, so none exists on any thread before the
 * tracker class is initialised.</p>
 *
 * <p>Like {@link HookGuard}, this class runs inside other mods' methods. It has
 * no static initialiser (its one field starts as {@code null}), so loading it
 * cannot fail. The registered abort touches only the calling thread's state,
 * so it is safe to run from any thread; {@link HookGuard} runs it inside a
 * catch-all.</p>
 */
public final class OpenActionAbort {
    private static volatile Runnable registered;

    private OpenActionAbort() {
    }

    /**
     * Registers the abort of the calling thread's open action; called once by the container tracker. Only the
     * tracker's static initialiser calls it, and a test that installs another abort puts the previous one back
     * with {@code restoreForTests}.
     */
    public static void register(Runnable abort) {
        registered = Objects.requireNonNull(abort, "abort");
    }

    /** Discards the calling thread's open exact action, if one is open; otherwise does nothing. */
    static void abortOnCurrentThread() {
        Runnable abort = registered;
        if (abort != null) abort.run();
    }

    static Runnable registeredForTests() {
        return registered;
    }

    static void restoreForTests(Runnable abort) {
        registered = abort;
    }
}
