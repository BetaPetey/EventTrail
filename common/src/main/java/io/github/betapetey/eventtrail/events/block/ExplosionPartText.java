package io.github.betapetey.eventtrail.events.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Subject text of the part row that opens each part of an explosion stored in parts (explosion-split-design-02
 * section 1.3). The text is regenerated from the part-form tag and the part row's reason code alone, so the
 * production codec accepts exactly the text {@link #presentation} produces ({@link #isPresentation}):
 * <ul>
 *   <li>"Explosion part 2 of 5: 1,023 of 4,700 destroyed blocks";</li>
 *   <li>"Explosion part 5 of 5: 610 of 4,777 destroyed blocks (77 not listed)";</li>
 *   <li>"Explosion part 1 of 1: 312 of at least 312 destroyed blocks (list cut short by an error)".</li>
 * </ul>
 * The total is {@code L + N}; "at least" when the count of blocks not listed is not exact. The text never says
 * whether every part was stored: only a reader's verdict (ExplosionCompleteness) can say that.
 */
public final class ExplosionPartText {
    public static final String TECHNICAL_IDENTITY = "eventtrail:explosion_part";

    private ExplosionPartText() {
    }

    /** The text of the part row with part-form {@code tag} and reason {@code reason}. */
    public static String presentation(ExplosionPartTag tag, BlockEffectTruncation reason) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(reason, "reason");
        if (tag.form() != ExplosionPartTag.Form.PART) throw new IllegalArgumentException("A part-form tag is required");
        if (!isPartReason(reason)) throw new IllegalArgumentException("Not an explosion reason: " + reason);
        long total = (long) tag.listedRows() + tag.notListed();
        StringBuilder text = new StringBuilder("Explosion part ")
                .append(tag.index()).append(" of ").append(tag.parts()).append(": ")
                .append(grouped(tag.blockRows())).append(" of ")
                .append(tag.exact() ? "" : "at least ")
                .append(grouped(total))
                .append(total == 1L ? " destroyed block" : " destroyed blocks");
        List<String> notes = new ArrayList<>(2);
        if (tag.notListed() > 0) notes.add(grouped(tag.notListed()) + " not listed");
        if (reason == BlockEffectTruncation.EXPLOSION_PROCESSING_FAILURE) notes.add("list cut short by an error");
        if (!notes.isEmpty()) text.append(" (").append(String.join("; ", notes)).append(')');
        return text.toString();
    }

    /** Whether {@code text} is exactly the text of a part row with {@code tag} and {@code reason}. */
    public static boolean isPresentation(String text, ExplosionPartTag tag, BlockEffectTruncation reason) {
        if (text == null || tag == null || reason == null) return false;
        if (tag.form() != ExplosionPartTag.Form.PART || !isPartReason(reason)) return false;
        return text.equals(presentation(tag, reason));
    }

    /** Reasons a part row may carry: none, or one of the three explosion reasons. */
    public static boolean isPartReason(BlockEffectTruncation reason) {
        return reason == BlockEffectTruncation.NONE
                || reason == BlockEffectTruncation.EXPLOSION_EVIDENCE_LIMIT
                || reason == BlockEffectTruncation.EXPLOSION_STORAGE_PRESSURE
                || reason == BlockEffectTruncation.EXPLOSION_PROCESSING_FAILURE;
    }

    /** Decimal with a comma every three digits, independent of the server's locale ("1,023"). */
    public static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
