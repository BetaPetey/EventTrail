package io.github.betapetey.eventtrail.commands.presentation;

/** Concise stable context shown above a structured lookup page. */
public record LookupSummaryView(
        long resultCount,
        Integer radius,
        String timeLabel,
        String worldLabel,
        long queryMillis) {

    public LookupSummaryView {
        if (resultCount < 0L) throw new IllegalArgumentException("resultCount cannot be negative");
        if (radius != null && radius < 1) throw new IllegalArgumentException("radius must be positive");
        if (timeLabel == null || timeLabel.isBlank()) {
            throw new IllegalArgumentException("timeLabel must not be blank");
        }
        if (worldLabel == null || worldLabel.isBlank()) {
            throw new IllegalArgumentException("worldLabel must not be blank");
        }
        if (queryMillis < 0L) throw new IllegalArgumentException("queryMillis cannot be negative");
    }
}
