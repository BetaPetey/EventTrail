package io.github.betapetey.eventtrail.commands.presentation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Formats audit ages against an injected clock with a data-driven unit catalog. */
public final class RelativeAgeFormatter {
    private static final List<AgeBand> BANDS = List.of(
            new AgeBand(60L, 1L, "s", 0L, ""),
            new AgeBand(60L * 60L, 60L, "m", 0L, ""),
            new AgeBand(24L * 60L * 60L, 60L * 60L, "h", 60L, "m"),
            new AgeBand(7L * 24L * 60L * 60L, 24L * 60L * 60L, "d", 60L * 60L, "h"),
            new AgeBand(Long.MAX_VALUE, 7L * 24L * 60L * 60L, "w", 24L * 60L * 60L, "d"));

    private final Clock clock;

    public RelativeAgeFormatter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String format(long occurredAtMillis) {
        return format(Instant.ofEpochMilli(occurredAtMillis));
    }

    public String format(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        long elapsedSeconds = Duration.between(occurredAt, clock.instant()).getSeconds();
        long age = Math.max(0L, elapsedSeconds);
        AgeBand band = BANDS.stream()
                .filter(candidate -> age < candidate.upperExclusiveSeconds())
                .findFirst()
                .orElseThrow();
        return band.format(age);
    }

    private record AgeBand(
            long upperExclusiveSeconds,
            long primarySeconds,
            String primarySuffix,
            long secondarySeconds,
            String secondarySuffix) {

        private String format(long ageSeconds) {
            long primary = ageSeconds / primarySeconds;
            StringBuilder result = new StringBuilder()
                    .append(primary)
                    .append(primarySuffix);
            if (secondarySeconds > 0L) {
                long secondary = (ageSeconds % primarySeconds) / secondarySeconds;
                if (secondary > 0L) {
                    result.append(' ').append(secondary).append(secondarySuffix);
                }
            }
            return result.append(" ago").toString();
        }
    }
}
