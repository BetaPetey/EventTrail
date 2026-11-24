package io.github.betapetey.eventtrail.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One value of a {@code block:}, {@code item:} or {@code entity:} lookup filter (Owner 11-22 5:12 AM, items 4-6).
 *
 * <ul>
 *   <li>{@link Form#ID}: an unquoted registry ID. With a namespace ({@code minecraft:oak_log}) it matches exactly
 *   that ID; without one ({@code oak_log}) it matches exactly that path in any namespace. So {@code oak_log} never
 *   matches {@code minecraft:stripped_oak_log}.</li>
 *   <li>{@link Form#NAME}: a quoted name. It matches the thing's normal name (the registry IDs whose game name
 *   equals it, resolved when the lookup is parsed, in {@link #normalIds()}) or the name shown in the log, custom
 *   names included: the whole name, ignoring case ({@link #foldName}).</li>
 * </ul>
 *
 * <p>{@code *} is the only wildcard, in both forms; every other character is literal. Immutable and safe on any
 * thread.</p>
 */
public record ThingFilter(Form form, String value, List<String> normalIds) {
    /** How the value was typed. */
    public enum Form { ID, NAME }

    /** An unquoted ID: a lowercase namespace and path, {@code *} allowed in either. */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.*-]+(:[a-z0-9_./*-]+)?");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public ThingFilter {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(value, "value");
        if (form == Form.ID && !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a lowercase ID: " + value);
        }
        if (form == Form.NAME && (value.isEmpty() || !value.equals(foldName(value)))) {
            throw new IllegalArgumentException("A name value must be folded and not blank");
        }
        normalIds = form == Form.ID || normalIds == null ? List.of() : List.copyOf(normalIds);
    }

    public static ThingFilter id(String id) {
        return new ThingFilter(Form.ID, id, List.of());
    }

    public static ThingFilter name(String name, List<String> normalIds) {
        return new ThingFilter(Form.NAME, foldName(name), normalIds);
    }

    /** The name compare form: lower case ({@link Locale#ROOT}), runs of whitespace as one space, trimmed. */
    public static String foldName(String name) {
        if (name == null) return "";
        return WHITESPACE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** True when an ID value names its namespace (the part before ':'). */
    boolean hasNamespace() {
        return form == Form.ID && value.indexOf(':') >= 0;
    }

    /**
     * True when a stored registry ID matches: an ID value by its rule, a name value when the ID is one of its
     * normal-name IDs. A stored ID without a ':' has no namespace and matches no ID value.
     */
    boolean matchesId(String storedId) {
        if (storedId == null) return false;
        if (form == Form.NAME) return normalIds.contains(storedId);
        int colon = storedId.indexOf(':');
        if (colon <= 0) return false;
        return hasNamespace() ? glob(value, storedId) : glob(value, storedId.substring(colon + 1));
    }

    /** True when this name value matches a name shown in the log (whole name, ignoring case). */
    boolean matchesShownName(String shown) {
        return form == Form.NAME && nameMatches(value, shown);
    }

    /** True when a folded name pattern ({@code *} the wildcard) matches the whole of a name, ignoring case. */
    public static boolean nameMatches(String foldedPattern, String name) {
        return foldedPattern != null && name != null && glob(foldedPattern, foldName(name));
    }

    /**
     * SQL LIKE patterns (escape '!') of which a stored text holding this ID value matches at least one: the text is
     * {@code prefix + ID + suffix} for each suffix given, where the prefix is literal text. Without a namespace the
     * ID part is {@code %:path}. A name value has no ID pattern (empty list): only the Java check can decide it.
     */
    List<String> idLikePatterns(String prefix, List<String> likeSuffixes) {
        if (form == Form.NAME) return List.of();
        String id = hasNamespace() ? likeGlob(value) : "%:" + likeGlob(value);
        List<String> patterns = new ArrayList<>(likeSuffixes.size());
        for (String suffix : likeSuffixes) patterns.add(likeLiteral(prefix) + id + suffix);
        return List.copyOf(patterns);
    }

    /** The text with the LIKE escape character '!' before '!', '%' and '_'. */
    static String likeLiteral(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** A {@code *} pattern as a LIKE pattern: literal parts escaped, each {@code *} as '%'. */
    static String likeGlob(String pattern) {
        StringBuilder like = new StringBuilder();
        int start = 0;
        for (int star = pattern.indexOf('*'); star >= 0; star = pattern.indexOf('*', start)) {
            like.append(likeLiteral(pattern.substring(start, star))).append('%');
            start = star + 1;
        }
        return like.append(likeLiteral(pattern.substring(start))).toString();
    }

    /** True when the whole text matches the pattern, where {@code *} matches any run of characters. */
    static boolean glob(String pattern, String text) {
        int p = 0;
        int t = 0;
        int star = -1;
        int resume = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = t;
            } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (star >= 0) {
                p = star + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
