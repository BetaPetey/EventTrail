package io.github.betapetey.eventtrail.events.block;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subject text of the one explosion truncation row (A2-44), the text a lookup shows. It says how many blocks the
 * explosion destroyed that its action does not list, and whether the explosion's list was cut short by an error:
 * <ul>
 *   <li>"Explosion evidence (77 destroyed blocks not listed)": the whole explosion was examined, the count is exact;</li>
 *   <li>"Explosion evidence (at least 77 destroyed blocks not listed)": the examination also stopped early or may have
 *       missed blocks, the count is a lower bound;</li>
 *   <li>"Explosion evidence (list cut short by an error)" and "Explosion evidence (list cut short by an error; at least
 *       77 destroyed blocks not listed)": the explosion's list was cut short by an error (reason
 *       explosion_processing_failure); the count after an error is always a lower bound;</li>
 *   <li>the plain "Explosion evidence": no proven-destroyed block was left out (N = 0) and no error. This includes an
 *       examination stopped early with nothing left out yet (reason explosion_evidence_limit or
 *       explosion_storage_pressure): positions after the stop were never examined, so destroyed blocks may be missing
 *       even though the text carries no count; the row's presence is then the only sign (Advisor M-2).</li>
 * </ul>
 * The payload format is unchanged; the text is the only carrier of the count and the error. The production codec
 * accepts exactly the texts {@link #presentation} produces ({@link #isPresentation}).
 */
public final class ExplosionTruncationText {
    public static final String TECHNICAL_IDENTITY = "eventtrail:explosion_truncation";
    public static final String BASE = "Explosion evidence";

    /**
     * Largest count the text carries: {@link #isPresentation} parses at most nine digits. A real count never comes
     * near it (it is at most the 25,000-callback work limit, BlockAuditLimits.MAX_EXPLOSION_EVIDENCE_WORK).
     */
    public static final int MAX_COUNT = 999_999_999;

    private static final String CUT_SHORT = "list cut short by an error";
    private static final Pattern COUNTED = Pattern.compile(
            "Explosion evidence \\((list cut short by an error; )?(at least )?([1-9][0-9]{0,8})"
                    + " destroyed blocks? not listed\\)");

    private ExplosionTruncationText() {
    }

    /** The text for {@code notListed} destroyed blocks left out, without an error; {@code exact} false means "at least". */
    public static String presentation(int notListed, boolean exact) {
        return presentation(notListed, exact, false);
    }

    /**
     * The text for {@code notListed} destroyed blocks left out; {@code exact} false means "at least";
     * {@code cutShortByError} says the explosion's list was cut short by an error, whose count is never exact.
     */
    public static String presentation(int notListed, boolean exact, boolean cutShortByError) {
        if (notListed < 0 || notListed > MAX_COUNT) {
            throw new IllegalArgumentException("notListed must be 0.." + MAX_COUNT);
        }
        if (cutShortByError && exact && notListed > 0) {
            throw new IllegalArgumentException("A count after an error is never exact");
        }
        // Advisor M-2: N = 0 has no count to qualify, so a stopped (inexact) examination also gets the plain text.
        if (notListed == 0) return cutShortByError ? BASE + " (" + CUT_SHORT + ")" : BASE;
        return BASE + " (" + (cutShortByError ? CUT_SHORT + "; " : "") + (exact ? "" : "at least ")
                + Integer.toString(notListed)
                + (notListed == 1 ? " destroyed block" : " destroyed blocks") + " not listed)";
    }

    /** Whether {@code text} is exactly one text {@link #presentation} can produce. */
    public static boolean isPresentation(String text) {
        if (BASE.equals(text) || presentation(0, false, true).equals(text)) return true;
        if (text == null) return false;
        Matcher counted = COUNTED.matcher(text);
        if (!counted.matches()) return false;
        boolean cutShortByError = counted.group(1) != null;
        boolean exact = counted.group(2) == null;
        if (cutShortByError && exact) return false;
        int notListed = Integer.parseInt(counted.group(3));
        return text.equals(presentation(notListed, exact, cutShortByError));
    }
}
