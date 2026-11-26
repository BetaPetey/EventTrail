package io.github.betapetey.eventtrail.commands.presentation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Formats audit ages against an injected clock: one unit and no " ago", as the Owner's row example "39s BetaPetey
 * broke Grass Block 14 63 25" shows (39s, 2m, 1h, 3d, 2w). The exact time is in the row's time hover.
 */
public final class RelativeAgeFormatter {
    private static final List<AgeBand> BANDS = List.of(
            new AgeBand(60L, 1L, "s"),
            new AgeBand(60L * 60L, 60L, "m"),
            new AgeBand(24L * 60L * 60L, 60L * 60L, "h"),
            new AgeBand(7L * 24L * 60L * 60L, 24L * 60L * 60L, "d"),
            new AgeBand(Long.MAX_VALUE, 7L * 24L * 60L * 60L, "w"));

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

    private record AgeBand(long upperExclusiveSeconds, long unitSeconds, String suffix) {
        private String format(long ageSeconds) {
            return (ageSeconds / unitSeconds) + suffix;
        }
    }
}
