package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.storage.TableShrink;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The confirm steps of {@code /eventtrail purge}: one pending step per command source, valid for 60 seconds and
 * for the maintenance lifecycle it was offered in. Taking a step removes it; a wrong, late or stale step starts
 * nothing. Bounded: one entry per source, expired entries are dropped on every call.
 */
final class PurgeConfirmations {
    static final long CONFIRM_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);

    enum Step {
        /** {@code /eventtrail purge confirm} starts the purge. */
        PURGE,
        /** {@code /eventtrail purge shrink confirm}: SQLite starts; MySQL/PostgreSQL ask about the free space. */
        SHRINK,
        /** {@code /eventtrail purge shrink confirm-space}: MySQL/PostgreSQL start after the admin's space confirm. */
        SHRINK_SPACE
    }

    record Pending(Step step, int days, TableShrink.Plan plan, long generation, long expiresAtNanos) {}

    private final Map<String, Pending> pending = new HashMap<>();
    private final LongSupplier clock;

    PurgeConfirmations(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Replaces any earlier step of this source. */
    synchronized void offer(String source, Step step, int days, TableShrink.Plan plan, long generation) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(step, "step");
        if ((step == Step.PURGE) != (plan == null)) throw new IllegalArgumentException("A shrink step needs its plan");
        long now = clock.getAsLong();
        sweep(now);
        pending.put(source, new Pending(step, days, plan, generation, now + CONFIRM_WINDOW_NANOS));
    }

    /**
     * The pending step of this source when it is {@code step}, in time and of {@code generation}; otherwise null.
     * Either way the source has no pending step afterwards.
     */
    synchronized Pending take(String source, Step step, long generation) {
        long now = clock.getAsLong();
        sweep(now);
        Pending found = pending.remove(source);
        if (found == null || found.step() != step || found.generation() != generation || generation < 0L) return null;
        return found;
    }

    /** What a shrink confirm leads to. */
    enum Next { START, ASK_SPACE, NOTHING }

    record Decision(Next next, TableShrink.Plan plan) {}

    /**
     * {@code /eventtrail purge shrink confirm}: SQLite starts; MySQL and PostgreSQL get the space step
     * and start only after {@link #confirmShrinkSpace}. Without a valid pending measure, nothing starts.
     */
    synchronized Decision confirmShrink(String source, long generation) {
        Pending step = take(source, Step.SHRINK, generation);
        if (step == null) return new Decision(Next.NOTHING, null);
        if (step.plan().needsAdminSpaceConfirm()) {
            offer(source, Step.SHRINK_SPACE, 0, step.plan(), generation);
            return new Decision(Next.ASK_SPACE, step.plan());
        }
        return step.plan().refused() ? new Decision(Next.NOTHING, null) : new Decision(Next.START, step.plan());
    }

    /** {@code /eventtrail purge shrink confirm-space}: starts only a MySQL/PostgreSQL shrink whose space step is pending. */
    synchronized Decision confirmShrinkSpace(String source, long generation) {
        Pending step = take(source, Step.SHRINK_SPACE, generation);
        return step == null ? new Decision(Next.NOTHING, null) : new Decision(Next.START, step.plan());
    }

    synchronized void clear() {
        pending.clear();
    }

    synchronized int size() {
        return pending.size();
    }

    private void sweep(long now) {
        pending.values().removeIf(entry -> now - entry.expiresAtNanos() >= 0L);
    }
}
