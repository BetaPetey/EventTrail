package io.github.betapetey.eventtrail.events.block;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explosion split (Owner decision B, explosion-split-design-02 section 1.2): the identity every row of an explosion
 * stored in parts carries in its block payload (payload version 2, {@link BlockEffectPayloadCodec}).
 *
 * <p>Two forms, both strictly formatted and parsed (no sign, no leading zero, no space, lowercase hex):</p>
 * <ul>
 *   <li>block form {@code b;<eid>;<t0>;<i>;<k>} on every block row of part {@code i};</li>
 *   <li>part form {@code p;<eid>;<t0>;<i>;<k>;<r>;<L>;<N>;<exact 0|1>} on the one part row that opens part {@code i}.</li>
 * </ul>
 * {@code eid} is 64 random bits (16 lowercase hex digits), {@code t0} the part rows' timestamp (Advisor M-7: 1 ms
 * before every row of the explosion), {@code k} the number of parts, {@code r} the block rows in part {@code i},
 * {@code L} the block rows listed in all parts, {@code N} the proven-destroyed blocks not listed and {@code exact}
 * whether {@code N} is exact. A block-form tag keeps {@code r}, {@code L}, {@code N} at 0 and {@code exact} false.
 */
public record ExplosionPartTag(
        Form form,
        long explosionId,
        long partTimestamp,
        int index,
        int parts,
        int blockRows,
        int listedRows,
        int notListed,
        boolean exact) {

    /**
     * Largest number of parts one explosion may have (Advisor M-6 b: the bound the parser enforces and the verdict
     * query's limit stays above). Production can never come near it: a part holds 1,023 block rows or is closed by its
     * 8 MiB budget with more than 7.75 MiB in it (a row is at most 256 KiB), so 25,000 rows (the work limit) within the
     * 48 MiB explosion budget make at most 24 + 6 + 1 = 31 parts.
     */
    public static final int MAX_PARTS = 64;

    /** Most block rows one part carries: one action's row limit less its part row. */
    public static final int MAX_BLOCK_ROWS_PER_PART = 1023;

    /** Largest count of destroyed blocks not listed that a tag carries (nine digits, as ExplosionTruncationText). */
    public static final int MAX_NOT_LISTED = 999_999_999;

    /**
     * Advisor M-1: the UTF-8 length of the longest block-form tag, counted against the payload limits when a row is
     * listed, so adding the real tag at finish can never cross a size limit.
     */
    public static final int MAX_BLOCK_TAG_UTF8_BYTES = 2 + 16 + 1 + Long.toString(Long.MAX_VALUE).length()
            + 1 + Integer.toString(MAX_PARTS).length() + 1 + Integer.toString(MAX_PARTS).length();

    private static final String NUMBER = "(0|[1-9][0-9]{0,18})";
    private static final Pattern BLOCK = Pattern.compile(
            "b;([0-9a-f]{16});" + NUMBER + ";" + NUMBER + ";" + NUMBER);
    private static final Pattern PART = Pattern.compile(
            "p;([0-9a-f]{16});" + NUMBER + ";" + NUMBER + ";" + NUMBER + ";" + NUMBER + ";" + NUMBER + ";" + NUMBER
                    + ";([01])");

    public enum Form { BLOCK, PART }

    public ExplosionPartTag {
        form = Objects.requireNonNull(form, "form");
        if (partTimestamp < 0L) throw new IllegalArgumentException("partTimestamp must not be negative");
        if (parts < 1 || parts > MAX_PARTS) throw new IllegalArgumentException("parts must be 1.." + MAX_PARTS);
        if (index < 1 || index > parts) throw new IllegalArgumentException("index must be 1..parts");
        if (form == Form.BLOCK) {
            if (blockRows != 0 || listedRows != 0 || notListed != 0 || exact) {
                throw new IllegalArgumentException("A block-form tag carries no counts");
            }
        } else {
            if (blockRows < 0 || blockRows > MAX_BLOCK_ROWS_PER_PART) {
                throw new IllegalArgumentException("blockRows must be 0.." + MAX_BLOCK_ROWS_PER_PART);
            }
            if (listedRows < blockRows || listedRows > MAX_BLOCK_ROWS_PER_PART * parts) {
                throw new IllegalArgumentException("listedRows must be blockRows.." + MAX_BLOCK_ROWS_PER_PART + " * parts");
            }
            if (notListed < 0 || notListed > MAX_NOT_LISTED) {
                throw new IllegalArgumentException("notListed must be 0.." + MAX_NOT_LISTED);
            }
        }
    }

    public static ExplosionPartTag block(long explosionId, long partTimestamp, int index, int parts) {
        return new ExplosionPartTag(Form.BLOCK, explosionId, partTimestamp, index, parts, 0, 0, 0, false);
    }

    public static ExplosionPartTag part(
            long explosionId, long partTimestamp, int index, int parts,
            int blockRows, int listedRows, int notListed, boolean exact) {
        return new ExplosionPartTag(Form.PART, explosionId, partTimestamp, index, parts,
                blockRows, listedRows, notListed, exact);
    }

    /** The stored text of this tag. */
    public String format() {
        String head = (form == Form.BLOCK ? "b;" : "p;") + hex(explosionId) + ";" + partTimestamp + ";" + index
                + ";" + parts;
        if (form == Form.BLOCK) return head;
        return head + ";" + blockRows + ";" + listedRows + ";" + notListed + ";" + (exact ? "1" : "0");
    }

    /** The tag {@code text} stores, only when {@code text} is exactly a text {@link #format} produces. */
    public static Optional<ExplosionPartTag> parse(String text) {
        if (text == null || text.length() > 128) return Optional.empty();
        try {
            Matcher block = BLOCK.matcher(text);
            if (block.matches()) {
                ExplosionPartTag tag = block(
                        Long.parseUnsignedLong(block.group(1), 16),
                        Long.parseLong(block.group(2)),
                        Integer.parseInt(block.group(3)),
                        Integer.parseInt(block.group(4)));
                return tag.format().equals(text) ? Optional.of(tag) : Optional.empty();
            }
            Matcher part = PART.matcher(text);
            if (part.matches()) {
                ExplosionPartTag tag = part(
                        Long.parseUnsignedLong(part.group(1), 16),
                        Long.parseLong(part.group(2)),
                        Integer.parseInt(part.group(3)),
                        Integer.parseInt(part.group(4)),
                        Integer.parseInt(part.group(5)),
                        Integer.parseInt(part.group(6)),
                        Integer.parseInt(part.group(7)),
                        "1".equals(part.group(8)));
                return tag.format().equals(text) ? Optional.of(tag) : Optional.empty();
            }
            return Optional.empty();
        } catch (IllegalArgumentException outOfRange) {
            // NumberFormatException (too many digits for an int or a long) is an IllegalArgumentException too.
            return Optional.empty();
        }
    }

    /** Whether {@code other} belongs to the same explosion (same eid and part timestamp). */
    public boolean sameExplosion(ExplosionPartTag other) {
        return other != null && other.explosionId == explosionId && other.partTimestamp == partTimestamp;
    }

    /** The short display form of the explosion id: its first six hex digits. */
    public String displayId() {
        return hex(explosionId).substring(0, 6);
    }

    public static String hex(long explosionId) {
        String digits = Long.toHexString(explosionId).toLowerCase(Locale.ROOT);
        return "0".repeat(16 - digits.length()) + digits;
    }
}
