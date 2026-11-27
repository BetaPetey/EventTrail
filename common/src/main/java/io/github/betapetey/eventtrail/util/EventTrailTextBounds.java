package io.github.betapetey.eventtrail.util;

/**
 * The text-length bounds and the pure text bounding rules for stored and displayed audit text.
 *
 * <p>These members used to live in {@code EventTrailNbtUtil} and {@code DisplayNameResolver}, which reach
 * Minecraft and NeoForge. They touch no game type, so they sit here with only {@link EventTrailText}.
 */
public final class EventTrailTextBounds {
    public static final String METADATA_UNAVAILABLE_KEY = "eventtrail_metadata_unavailable";
    public static final String METADATA_REASON_KEY = "eventtrail_metadata_reason";
    public static final String METADATA_REASON_CODEC_FAILURE = "codec_failure";
    public static final String METADATA_REASON_TOO_LARGE = "too_large";
    public static final String METADATA_REASON_BUFFER_PRESSURE = "write_buffer_saturated";
    public static final int MAX_ITEM_METADATA_NBT_BYTES = 64 * 1024;
    public static final int MAX_ITEM_METADATA_CHARS = 64 * 1024;
    public static final int MAX_AUDIT_DESCRIPTION_CHARS = 512;
    public static final int MAX_DISPLAY_TEXT_CHARS = 256;
    /** Bound for a custom name or a book title before escaping (item-names design 2.3). */
    static final int MAX_CUSTOM_TEXT_CHARS = 100;

    // ItemDetails (plan 1.3) reason names, one per reason code; stored as codes, these are their names in WARNs.
    public static final String DETAILS_REASON_DECODE_FAILED = "decode_failed";
    public static final String DETAILS_REASON_STORE_DIFFERS = "store_differs";
    public static final String DETAILS_REASON_NBT_BYTES = "nbt_bytes";
    public static final String DETAILS_REASON_TEXT_CHARS = "text_chars";
    public static final String DETAILS_REASON_UTF8_BYTES = "utf8_bytes";
    public static final String DETAILS_REASON_BINARY_BYTES = "binary_bytes";
    public static final String DETAILS_REASON_TOO_DEEP = "too_deep";
    public static final String DETAILS_REASON_CODEC_THREW = "codec_threw";
    public static final String DETAILS_REASON_NON_COMPOUND = "non_compound";
    public static final String DETAILS_REASON_OFF_THREAD = "off_thread";
    public static final String DETAILS_REASON_NO_SERVER = "no_server";

    // ItemDetails wordings W1-W6 (plan section 3), as approved: each is the one place its text lives. Nothing renders
    // them yet (B0); the readers switch in B1-B5.
    /** W1: TOO_LARGE. */
    public static final String DETAILS_WORDING_TOO_LARGE = "(details too large to store)";
    /** W2: BUSY. */
    public static final String DETAILS_WORDING_BUSY = "(details not stored: server was busy)";
    /** W3: NOT_STORED. */
    public static final String DETAILS_WORDING_NOT_STORED = "(details not stored)";
    /** W4: UNREADABLE. */
    public static final String DETAILS_WORDING_UNREADABLE = "(some details unreadable)";
    /**
     * W5: in place of W4 when the item's mod is not loaded. With no stored name, the name shown is the item's ID
     * itself followed by this text, e.g. {@code draconicevolution:draconic_staff (mod not loaded)}.
     */
    public static final String DETAILS_WORDING_MOD_NOT_LOADED = "(mod not loaded)";
    /** W6: the stored record itself is unreadable. */
    public static final String DETAILS_WORDING_RECORD_UNREADABLE = "(stored record unreadable)";

    private EventTrailTextBounds() {}

    public static String boundAuditDescription(String value) {
        // Cut only between whole characters (T31-R2): never keep half of a surrogate pair.
        return EventTrailText.boundWithEllipsis(value, MAX_AUDIT_DESCRIPTION_CHARS);
    }

    /** Sanitizes literal player/mod-provided display text without treating it as a format template. */
    public static String literalDisplayText(String value) {
        if (value == null) return null;
        String clean = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) return null;
        return boundText(clean, MAX_DISPLAY_TEXT_CHARS);
    }

    /** One literal presentation rule for current container-target labels. */
    public static String containerTargetDisplayName(String storedLabel) {
        String stored = literalDisplayText(storedLabel);
        return stored == null ? "Container" : stored;
    }

    static String boundText(String value, int maximum) {
        // Cut only between whole characters (T31-R2); this text is also stored in payloads.
        if (value.length() <= maximum) return value;
        if (maximum <= EventTrailText.ELLIPSIS.length()) return EventTrailText.prefixOfWholeCharacters(value, maximum);
        return EventTrailText.boundWithEllipsis(value, maximum);
    }
}
