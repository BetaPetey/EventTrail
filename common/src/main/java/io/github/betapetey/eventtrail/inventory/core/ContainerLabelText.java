package io.github.betapetey.eventtrail.inventory.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one text rule for container labels: open, close, items in and items out
 * rows all carry the same text.
 *
 * <p>{@link #containerLabel(String)} runs where a label enters EventTrail
 * ({@link ContainerTarget}). Its result is:</p>
 * <ul>
 *   <li>clean: line breaks, tabs, vertical tabs and form feeds become spaces, runs
 *       of white space become one space, and the text is trimmed. This is exactly
 *       the cleaning of {@code EventTrailTextBounds.literalDisplayText}, so the
 *       row read-back returns the text unchanged.</li>
 *   <li>free of lone UTF-16 surrogates (each becomes {@code ?}; valid pairs are
 *       kept), so the strict UTF-8 transfer payload round-trips the text.</li>
 *   <li>never blank ({@code Container}, the read-back's own word).</li>
 *   <li>at most {@value #MAX_UTF8_BYTES} UTF-8 bytes, cut at whole code points
 *       with the same algorithm as the access snapshot's {@code boundUtf8}:
 *       unchanged when it fits, else the code points that fit in 253 bytes plus
 *       {@code ...}.</li>
 * </ul>
 *
 * <p>A label that is already clean and within the bound is returned as it is, so
 * the stored text of such labels does not change. Labels with a tab, line break,
 * vertical tab or form feed, a run of spaces, a character up to U+0020 at either
 * end, a lone surrogate or more than 256 UTF-8 bytes are stored cleaned or cut
 * from now on; a label with nothing left after trim is stored as
 * {@code Container}.</p>
 *
 * <p>Stateless and pure: no cache, no static mutable state, safe on any thread.
 * It never throws for a non-null argument, so a label can never make an
 * access-target build fail.</p>
 */
final class ContainerLabelText {
    /** Equal to ContainerAccessTargetSnapshot.MAX_DISPLAY_UTF8_BYTES (pinned by a test). */
    static final int MAX_UTF8_BYTES = 256;

    private static final String BLANK_LABEL = "Container";
    private static final Pattern WHITE_SPACE_RUN = Pattern.compile("\\s+");

    private ContainerLabelText() {}

    static String containerLabel(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (isAlreadyClean(raw)) return raw;
        // Exactly the cleaning of EventTrailTextBounds.literalDisplayText (without its
        // 256-character cut, which would run before the byte cut and could split a pair).
        String clean = WHITE_SPACE_RUN.matcher(
                raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')).replaceAll(" ").trim();
        clean = replaceLoneSurrogates(clean, '?');
        if (clean.isBlank()) return BLANK_LABEL;
        return cutUtf8(clean, MAX_UTF8_BYTES);
    }

    /**
     * The fast path: true only when the slow path would return {@code text} itself.
     * That needs all of: not empty; a first character that is above U+0020 and not
     * white space (so the text is not blank and trim removes nothing at the front);
     * a last character above U+0020 (trim removes nothing at the end); no tab, line
     * feed, vertical tab, form feed or carriage return anywhere; no two spaces in a
     * row; every surrogate part in a valid pair; at most 256 UTF-8 bytes. Characters
     * above U+0020 that are not matched by the regular expression white-space class
     * (for example U+0085 or U+2028 inside the text) are kept by the cleaning, so
     * they do not leave the fast path.
     */
    private static boolean isAlreadyClean(String text) {
        int length = text.length();
        if (length == 0) return false;
        char first = text.charAt(0);
        if (first <= ' ' || Character.isWhitespace(first) || text.charAt(length - 1) <= ' ') {
            return false;
        }
        int bytes = 0;
        boolean previousWasSpace = false;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r') return false;
            if (c == ' ') {
                if (previousWasSpace) return false;
                previousWasSpace = true;
                bytes += 1;
            } else {
                previousWasSpace = false;
                if (c < 0x80) {
                    bytes += 1;
                } else if (c < 0x800) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) return false;
                    i++;
                    bytes += 4;
                } else if (Character.isLowSurrogate(c)) {
                    return false;
                } else {
                    bytes += 3;
                }
            }
            if (bytes > MAX_UTF8_BYTES) return false;
        }
        return true;
    }

    /** Replaces each lone UTF-16 surrogate with {@code replacement}; valid pairs are kept. */
    private static String replaceLoneSurrogates(String text, char replacement) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lone;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    if (out != null) out.append(c).append(text.charAt(i + 1));
                    i++;
                    continue;
                }
                lone = true;
            } else {
                lone = Character.isLowSurrogate(c);
            }
            if (lone) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                    out.append(text, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    /**
     * The algorithm of the access snapshot's {@code boundUtf8}: unchanged when it
     * fits {@code limit} bytes, else whole code points while the kept bytes stay
     * within {@code limit - 3}, then {@code ...}. The text has no lone surrogate here.
     */
    private static String cutUtf8(String value, int limit) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= limit) return value;
        StringBuilder bounded = new StringBuilder(value.length());
        int retainedBytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (retainedBytes + unitBytes > limit - 3) break;
            bounded.append(unit);
            retainedBytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        return bounded.append("...").toString();
    }
}
