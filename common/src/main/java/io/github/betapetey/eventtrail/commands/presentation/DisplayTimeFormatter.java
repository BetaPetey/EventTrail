package io.github.betapetey.eventtrail.commands.presentation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one form of every time shown to a person (Owner Q241 A, 11-22): "2026-11-19 21:40:07 EST (UTC-05:00)", the
 * zone's name in effect on that date, then the UTC offset in brackets. A zone with no common name shows the offset
 * alone: "2026-11-19 21:40:07 (UTC+05:45)". Stored times are never changed; this formats for display only. Every
 * pattern carries an explicit locale, so the JVM default locale never changes the text.
 */
public final class DisplayTimeFormatter {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ZONE_NAME = DateTimeFormatter.ofPattern("zzz", Locale.US);
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ROOT);
    /** A name that is only an offset ("GMT-05:00", "+05:45", "UTC+3") is not a common name. */
    private static final Pattern OFFSET_LIKE = Pattern.compile("^(GMT|UTC)?[+-]\\d.*");

    private DisplayTimeFormatter() {}

    /** The server's zone: the JVM default, read at each call (no setting exists for it). */
    public static String format(Instant at) {
        return format(at, ZoneId.systemDefault());
    }

    public static String format(long epochMillis) {
        return format(Instant.ofEpochMilli(epochMillis));
    }

    public static String format(long epochMillis, ZoneId zone) {
        return format(Instant.ofEpochMilli(epochMillis), zone);
    }

    public static String format(Instant at, ZoneId zone) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(zone, "zone");
        ZonedDateTime local = at.atZone(zone);
        String offset = "(UTC" + OFFSET.format(local) + ")";
        String name = zoneName(local);
        return DATE_TIME.format(local) + (name == null ? " " : " " + name + " ") + offset;
    }

    /** The zone's common short name in effect at that time, or null when it has none. */
    static String zoneName(ZonedDateTime local) {
        if (local.getZone().equals(ZoneOffset.UTC)) return "UTC";
        String name = ZONE_NAME.format(local);
        // No common name: the formatter fell back to an offset ("GMT-05:00", "+05:45") or to a region ID.
        if (name.isBlank() || name.indexOf('/') >= 0 || OFFSET_LIKE.matcher(name).matches()) {
            return null;
        }
        return name;
    }
}
