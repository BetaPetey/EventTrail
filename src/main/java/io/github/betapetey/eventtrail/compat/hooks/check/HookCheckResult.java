package io.github.betapetey.eventtrail.compat.hooks.check;

import java.util.Objects;

/**
 * The pre-apply check's verdict for one hook class on one target class.
 *
 * <p>Only {@link Outcome#PASSED} lets the hook be handed to Mixin. The wording is "passed the check", never
 * "applied": the check reads the other mod's file before Mixin runs and cannot see what Mixin or another mod's
 * transformer does later (work map R4-wire, zero-site obligation).</p>
 *
 * @param hook   the hook class, internal name
 * @param target the target class, internal name
 * @param reason why the hook did not pass; empty when it passed
 */
public record HookCheckResult(String hook, String target, Outcome outcome, String reason) {
    /** How one hook fared on one target. */
    public enum Outcome {
        /** Everything Mixin needs to apply the hook cleanly is present in the target as read. */
        PASSED,
        /** The target class is not present: the mod (or that class of it) is not installed. */
        TARGET_ABSENT,
        /** The target is present but differs from what the hook needs; the hook is skipped. */
        FAILED,
        /** The hook or a class it needs could not be read or is not understood; the hook is skipped. */
        UNVERIFIABLE
    }

    public HookCheckResult {
        Objects.requireNonNull(outcome, "outcome");
        hook = hook == null ? "?" : hook;
        target = target == null ? "?" : target;
        reason = reason == null ? "" : reason;
    }

    public boolean passed() {
        return outcome == Outcome.PASSED;
    }

    static HookCheckResult pass(String hook, String target) {
        return new HookCheckResult(hook, target, Outcome.PASSED, "");
    }

    static HookCheckResult of(String hook, String target, Outcome outcome, String reason) {
        return new HookCheckResult(hook, target, outcome, reason);
    }

    @Override
    public String toString() {
        String text = hook.replace('/', '.') + " on " + target.replace('/', '.') + ": " + outcome;
        return reason.isEmpty() ? text : text + " (" + reason + ")";
    }
}
