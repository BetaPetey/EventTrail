package io.github.betapetey.eventtrail.util;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe time gate for bounded recurring operational diagnostics. */
public final class RateLimitedDiagnosticGate {
    private final long intervalMillis;
    private final AtomicLong nextEmissionMillis = new AtomicLong();
    private final LongAdder suppressed = new LongAdder();

    public RateLimitedDiagnosticGate(long intervalMillis) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.intervalMillis = intervalMillis;
    }

    public Optional<Emission> acquire() {
        return acquire(System.currentTimeMillis());
    }

    /** Visible for deterministic tests; production callers use {@link #acquire()}. */
    public Optional<Emission> acquire(long nowMillis) {
        if (nowMillis < 0L) return Optional.empty();

        while (true) {
            long next = nextEmissionMillis.get();
            if (nowMillis < next || next == Long.MAX_VALUE) {
                suppressed.increment();
                return Optional.empty();
            }

            long following = nowMillis > Long.MAX_VALUE - intervalMillis
                    ? Long.MAX_VALUE
                    : nowMillis + intervalMillis;
            if (nextEmissionMillis.compareAndSet(next, following)) {
                return Optional.of(new Emission(suppressed.sumThenReset()));
            }
        }
    }

    public record Emission(long suppressedSinceLastEmission) {
        public Emission {
            if (suppressedSinceLastEmission < 0L) {
                throw new IllegalArgumentException("suppressed count must not be negative");
            }
        }
    }
}
