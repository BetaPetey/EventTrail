package io.github.betapetey.eventtrail.core;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Explosion split (design-02 2.3; Advisor answer 2, the coordinator's counter; condition 4): the one figure that
 * counts every part of an explosion stored in parts that was not stored, each part exactly once under one cause, and
 * the rows the explosion budget left out. Owned by EventTrailRuntimeCoordinator, shown by {@code /eventtrail status}
 * and by the coordinator's stop-time line. A NO_ROOM answer is not a loss and is never counted here.
 *
 * <p>Counting adds to pre-made {@link LongAdder}s only: no allocation, no lock, nothing that can throw. Counts are
 * since this process started; nothing survives a crash or a kill (a known limitation: readers still show those
 * explosions INCOMPLETE).</p>
 */
public final class ExplosionPartLossCounter {
    /** Why a part was not stored. Every part not stored is counted under exactly one cause. */
    public enum Cause {
        /** Still waiting when the server stopped (or when a new run started), or finished while no run was open. */
        STOP("still waiting at server stop"),
        /** The backlog of waiting parts was full when the explosion ended. */
        BACKLOG_CAP("backlog full"),
        /** The explosion waited past the age bound (server ticks) and storage still had no room. */
        AGE("waited too long for writer room"),
        /** Storage refused the part, could not take it, or failed while taking it. */
        REFUSED("refused by storage"),
        /** A later part of an explosion one of whose parts storage refused (never offered). */
        AFTER_REFUSAL("not offered after a refused part"),
        /**
         * Review M-A (1): an explosion to be stored in parts whose parts could not be built (none of its rows was
         * stored). Its parts never existed, so the whole explosion is counted as ONE part with all its listed rows.
         */
        BUILD_FAILED("whole explosion(s) whose parts could not be built");

        private final String text;

        Cause(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private final LongAdder explosions = new LongAdder();
    private final LongAdder[] parts = new LongAdder[Cause.values().length];
    private final LongAdder[] blockRows = new LongAdder[Cause.values().length];
    private final LongAdder budgetRows = new LongAdder();

    public ExplosionPartLossCounter() {
        for (int i = 0; i < parts.length; i++) {
            parts[i] = new LongAdder();
            blockRows[i] = new LongAdder();
        }
    }

    /** One more explosion with at least one part not stored (counted once per explosion by the publisher). */
    public void countExplosion() {
        explosions.increment();
    }

    /** {@code partCount} parts with {@code rows} block rows in all, not stored for {@code cause}. */
    public void countParts(Cause cause, int partCount, long rows) {
        if (cause == null || partCount <= 0) return;
        parts[cause.ordinal()].add(partCount);
        if (rows > 0L) blockRows[cause.ordinal()].add(rows);
    }

    /** Destroyed blocks the explosion budget left out of the listing (they are also in the part rows' N). */
    public void countLeftOutByBudget(long rows) {
        if (rows > 0L) budgetRows.add(rows);
    }

    public Snapshot snapshot() {
        long[] partCounts = new long[parts.length];
        long[] rowCounts = new long[blockRows.length];
        for (int i = 0; i < parts.length; i++) {
            partCounts[i] = parts[i].sum();
            rowCounts[i] = blockRows[i].sum();
        }
        return new Snapshot(explosions.sum(), partCounts, rowCounts, budgetRows.sum());
    }

    /** Totals at one moment; {@link #minus} gives the counts since an earlier snapshot. */
    public record Snapshot(long explosions, long[] partsByCause, long[] blockRowsByCause, long budgetRows) {
        public static final Snapshot ZERO = new Snapshot(
                0L, new long[Cause.values().length], new long[Cause.values().length], 0L);

        public Snapshot {
            partsByCause = Objects.requireNonNull(partsByCause, "partsByCause").clone();
            blockRowsByCause = Objects.requireNonNull(blockRowsByCause, "blockRowsByCause").clone();
            if (partsByCause.length != Cause.values().length || blockRowsByCause.length != Cause.values().length) {
                throw new IllegalArgumentException("One count per cause");
            }
        }

        public long parts() {
            long total = 0L;
            for (long count : partsByCause) total += count;
            return total;
        }

        public long blockRows() {
            long total = 0L;
            for (long count : blockRowsByCause) total += count;
            return total;
        }

        public long parts(Cause cause) {
            return partsByCause[cause.ordinal()];
        }

        public long blockRows(Cause cause) {
            return blockRowsByCause[cause.ordinal()];
        }

        public boolean isEmpty() {
            return explosions == 0L && parts() == 0L && budgetRows == 0L;
        }

        public Snapshot minus(Snapshot earlier) {
            long[] partCounts = new long[partsByCause.length];
            long[] rowCounts = new long[blockRowsByCause.length];
            for (int i = 0; i < partCounts.length; i++) {
                partCounts[i] = partsByCause[i] - earlier.partsByCause[i];
                rowCounts[i] = blockRowsByCause[i] - earlier.blockRowsByCause[i];
            }
            return new Snapshot(explosions - earlier.explosions, partCounts, rowCounts,
                    budgetRows - earlier.budgetRows);
        }

        /** One plain-language sentence for the stop line and the status: "3 part(s) ... (2 still waiting ...)". */
        public String describe() {
            StringBuilder causes = new StringBuilder();
            for (Cause cause : Cause.values()) {
                long count = parts(cause);
                if (count <= 0L) continue;
                if (causes.length() > 0) causes.append(", ");
                causes.append(count).append(' ').append(cause.text());
            }
            StringBuilder text = new StringBuilder()
                    .append(parts()).append(" part(s) with ").append(blockRows())
                    .append(" block row(s) of ").append(explosions).append(" explosion(s) stored in parts were not stored");
            if (causes.length() > 0) text.append(" (").append(causes).append(')');
            text.append("; ").append(budgetRows)
                    .append(" destroyed block(s) were left out of explosions by the explosion size budget");
            return text.toString();
        }
    }
}
