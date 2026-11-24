package io.github.betapetey.eventtrail.inventory.graves;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The pile rule (graves design r6, 1.6.3): one rule for every partial take of a pile whose units sit in named buckets
 * (owner buckets and the unlabelled bucket) plus {@code unknown}. It never guesses: a named bucket's part is the least
 * that must have come from it, and the rest of the take is the uncertain part.
 *
 * <p>For a pile of {@code N} units in named buckets {@code b_1..b_k} plus {@code unknown}, a take of {@code p} units
 * ({@code 0 <= p <= N}) gives:</p>
 * <ul>
 *   <li>each named bucket's certain part {@code max(0, p - (N - b_j))};</li>
 *   <li>the uncertain part {@code p - sum(certain parts)};</li>
 *   <li>each named bucket keeps {@code max(0, b_j - p)}, and {@code unknown} takes the rest, so the kept counts sum
 *   to {@code N - p}.</li>
 * </ul>
 * A whole take ({@code p == N}) gives each named bucket exactly its count and {@code unknown} as the uncertain part.
 */
public final class PileRule {
    private PileRule() {
    }

    /** One take's result: the parts taken and what stays on the pile, each named bucket in the input's order. */
    public record Split(List<Long> certain, long uncertain, List<Long> keptNamed, long keptUnknown) {
        public Split {
            certain = List.copyOf(certain);
            keptNamed = List.copyOf(keptNamed);
        }

        /** Units taken: the certain parts plus the uncertain part. */
        public long taken() {
            long sum = uncertain;
            for (long part : certain) sum += part;
            return sum;
        }
    }

    /**
     * Splits a take of {@code p} units from a pile with {@code named} bucket counts (each at least 0) and
     * {@code unknown} units. Throws IllegalArgumentException for a negative count or {@code p} outside
     * {@code 0..N}.
     */
    public static Split take(List<Long> named, long unknown, long p) {
        Objects.requireNonNull(named, "named");
        if (unknown < 0L) throw new IllegalArgumentException("unknown must not be negative");
        long total = unknown;
        for (Long count : named) {
            if (count == null || count < 0L) throw new IllegalArgumentException("bucket counts must not be negative");
            total = Math.addExact(total, count);
        }
        if (p < 0L || p > total) throw new IllegalArgumentException("a take must be between 0 and the pile's count");
        List<Long> certain = new ArrayList<>(named.size());
        List<Long> kept = new ArrayList<>(named.size());
        long certainSum = 0L;
        long keptSum = 0L;
        for (long count : named) {
            long part = Math.max(0L, p - (total - count));
            long stays = Math.max(0L, count - p);
            certain.add(part);
            kept.add(stays);
            certainSum += part;
            keptSum += stays;
        }
        return new Split(certain, p - certainSum, kept, (total - p) - keptSum);
    }
}
