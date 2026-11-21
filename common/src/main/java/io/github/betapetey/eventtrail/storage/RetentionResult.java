package io.github.betapetey.eventtrail.storage;

import java.util.Objects;

/** Committed deletion count and the observed stopping condition of one retention pass. */
public record RetentionResult(long deletedCount, Outcome outcome) {
    public RetentionResult {
        if (deletedCount < 0L) throw new IllegalArgumentException("Deleted count must not be negative");
        Objects.requireNonNull(outcome, "outcome");
    }

    public enum Outcome {
        /** A successful short or empty batch exhausted eligible rows at that observation. */
        EXHAUSTED,
        /** The next batch was withheld by the work budget; eligible rows may remain. */
        BUDGET_EXHAUSTED,
        /** Admission for another finite batch was withdrawn; already committed deletions remain counted. */
        YIELDED,
        /** Interruption stopped the pass; eligible rows may remain. */
        INTERRUPTED,
        /** Unavailable storage, deletion, or cleanup failure prevented a successful pass. */
        FAILED
    }
}
