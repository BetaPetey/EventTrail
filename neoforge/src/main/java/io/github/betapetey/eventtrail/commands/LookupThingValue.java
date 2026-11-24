package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.storage.ThingFilter;
import io.github.betapetey.eventtrail.storage.ThingFilters;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Parses the value list of one {@code block:}, {@code item:}, {@code entity:} or {@code mod:} lookup filter
 * (Owner 11-22 5:12 AM, items 4-8 and 11-13).
 *
 * <ul>
 *   <li>Members are split on commas outside quotes; an empty member is refused.</li>
 *   <li>An unquoted member is an ID: lowercase, {@code namespace:path} or {@code path}, {@code *} allowed. Any other
 *   unquoted member is refused with a hint to quote it as a name.</li>
 *   <li>A member in "..." or '...' is a name: the whole name, ignoring case ({@link ThingFilter#foldName}).</li>
 *   <li>{@code mod:} members are literal mod IDs only: lowercase, no {@code *}, no quotes.</li>
 * </ul>
 *
 * <p>The members are returned as typed (quotes kept), for the canonical query text.</p>
 */
final class LookupThingValue {
    private LookupThingValue() {
    }

    /** One typed member: its text as typed (trimmed, quotes kept), and its name or ID value. */
    record Member(String typed, boolean quoted, String value) {
    }

    /** The members, or the one-line error. */
    record Result(List<Member> members, String error) {
        boolean successful() {
            return error == null;
        }

        /** The canonical value list: the members as typed, joined with ','. */
        String canonical() {
            List<String> typed = new ArrayList<>(members.size());
            for (Member member : members) typed.add(member.typed());
            return String.join(",", typed);
        }
    }

    /** Parses a block:, item: or entity: value list; every quoted hint is assumed to resolve. */
    static Result parseThings(String key, String value) {
        return parseThings(key, value, name -> true);
    }

    /**
     * Parses a block:, item: or entity: value list. An unquoted value that is not an ID is refused; the refusal
     * suggests the value in quotes only when that form parses back as one name: the quote kind the value does not
     * hold, a value short enough with its quotes, and a folded name {@code nameResolves} accepts.
     */
    static Result parseThings(String key, String value, Predicate<String> nameResolves) {
        Result split = split(key, value);
        if (!split.successful()) return split;
        for (Member member : split.members()) {
            if (!member.quoted() && !ThingFilter.ID_PATTERN.matcher(member.value()).matches()) {
                String hint = quotedHint(key, member.value(), nameResolves);
                return failure(hint == null
                        ? member.value() + " is not an ID, and cannot be used as a name in quotes. Search by its ID."
                        : member.value() + " is not an ID. Try " + hint);
            }
            if (member.quoted() && ThingFilter.foldName(member.value()).isEmpty()) {
                return failure(key + ": has an empty name in quotes.");
            }
        }
        return split;
    }

    /**
     * The value in quotes ({@code key:"value"}, or {@code key:'value'} when the value holds a double quote), or null
     * when no quoted form parses back as this one name: the value holds both kinds of quote, is too long with its
     * quotes, or names more normal names than a lookup takes.
     */
    static String quotedHint(String key, String value, Predicate<String> nameResolves) {
        char quote;
        if (value.indexOf('"') < 0) {
            quote = '"';
        } else if (value.indexOf('\'') < 0) {
            quote = '\'';
        } else {
            return null;
        }
        if (value.length() + 2 > ExtendedLookup.MAX_FILTER_VALUE_LENGTH) return null;
        String folded = ThingFilter.foldName(value);
        if (folded.isEmpty() || !nameResolves.test(folded)) return null;
        return key + ":" + quote + value + quote;
    }

    /** Parses a mod: value list: literal lowercase mod IDs. */
    static Result parseMods(String value) {
        Result split = split("mod", value);
        if (!split.successful()) return split;
        for (Member member : split.members()) {
            if (member.quoted() || !ThingFilters.MOD_ID_PATTERN.matcher(member.value()).matches()) {
                return failure(member.typed() + " is not a mod ID. mod: takes the mod's own lowercase ID, for"
                        + " example mod:minecraft or mod:ae2.");
            }
        }
        return split;
    }

    private static Result split(String key, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return failure(key + ": requires a value.");
        List<Member> members = new ArrayList<>();
        int index = 0;
        while (true) {
            while (index < text.length() && text.charAt(index) == ' ') index++;
            if (index >= text.length() || text.charAt(index) == ',') {
                return failure(key + ": has an empty entry in its comma list.");
            }
            char first = text.charAt(index);
            int end;
            Member member;
            if (first == '"' || first == '\'') {
                int close = text.indexOf(first, index + 1);
                if (close < 0) {
                    return failure(key + ": has an opening quote without its closing quote, for example "
                            + key + ":\"Lucky Pick\".");
                }
                member = new Member(text.substring(index, close + 1), true, text.substring(index + 1, close));
                end = close + 1;
                while (end < text.length() && text.charAt(end) == ' ') end++;
                if (end < text.length() && text.charAt(end) != ',') {
                    return failure(key + ": has text after a closing quote. Separate values with commas.");
                }
            } else {
                end = text.indexOf(',', index);
                if (end < 0) end = text.length();
                String typed = text.substring(index, end).trim();
                member = new Member(typed, false, typed);
            }
            members.add(member);
            if (end >= text.length()) break;
            index = end + 1;
            if (index >= text.length()) return failure(key + ": has an empty entry in its comma list.");
        }
        return new Result(List.copyOf(members), null);
    }

    private static Result failure(String error) {
        return new Result(List.of(), error);
    }
}
