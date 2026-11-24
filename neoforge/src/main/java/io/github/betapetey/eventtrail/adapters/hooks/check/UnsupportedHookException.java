package io.github.betapetey.eventtrail.adapters.hooks.check;

/**
 * A hook class uses a Mixin or MixinExtras feature that {@link HookReader} does not model, or is not a well-formed
 * hook. The pre-apply check never guesses: such a hook cannot be verified and is skipped. For EventTrail's own shipped
 * hooks, the coverage test turns this into a build failure.
 */
public final class UnsupportedHookException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnsupportedHookException(String message) {
        super(message);
    }
}
