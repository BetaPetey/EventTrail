package io.github.betapetey.eventtrail.util;

/** Length bounds for stored human-facing text that never split a character (T31-R2). */
public final class EventTrailText {
    public static final String ELLIPSIS = "...";

    private EventTrailText() {
    }

    /**
     * Returns {@code value} unchanged when it has at most {@code maxChars} UTF-16 units; otherwise its longest
     * prefix of whole code points with at most {@code maxChars} units. A surrogate pair is never split, so
     * the result is valid UTF-16 whenever the input is, and every UTF-8 backend stores it unchanged.
     */
    public static String prefixOfWholeCharacters(String value, int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must not be negative");
        if (value == null || value.length() <= maxChars) return value;
        int end = maxChars;
        // A high surrogate as the last kept unit is half of a pair whose low half would be cut off, or it is
        // already unpaired; either way it is dropped, so the cut falls between whole characters.
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    /**
     * Returns {@code value} unchanged when it has at most {@code maxChars} UTF-16 units; otherwise its longest
     * prefix of whole code points that fits in {@code maxChars - 3} units, followed by "...". The result never
     * exceeds {@code maxChars} units, so bounding an already bounded value returns it unchanged.
     */
    public static String boundWithEllipsis(String value, int maxChars) {
        if (maxChars <= ELLIPSIS.length()) {
            throw new IllegalArgumentException("maxChars must be greater than the ellipsis length");
        }
        if (value == null || value.length() <= maxChars) return value;
        return prefixOfWholeCharacters(value, maxChars - ELLIPSIS.length()) + ELLIPSIS;
    }
}
