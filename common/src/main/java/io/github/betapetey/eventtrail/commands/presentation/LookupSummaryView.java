package io.github.betapetey.eventtrail.commands.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Concise stable context shown above a structured lookup page: "N results · Query: N ms", then the filter line (the
 * search's own tokens, plus " in <world>" for a radius search). {@code baseQuery} is the canonical query text the
 * filter line shows and its click puts back in the chat box; {@code scopeWorld} is the world a radius search covers.
 * {@code radius}, {@code timeLabel} and {@code worldLabel} are kept for existing callers and for summaries built
 * without a base query.
 */
public record LookupSummaryView(
        long resultCount,
        Integer radius,
        String timeLabel,
        String worldLabel,
        long queryMillis,
        String baseQuery,
        String scopeWorld) {

    public static final String ALL_DIMENSIONS = "All dimensions";
    public static final String LOOKUP_COMMAND_PREFIX = "/eventtrail lookup ";

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
        if (baseQuery != null && baseQuery.isBlank()) baseQuery = null;
        if (scopeWorld != null && scopeWorld.isBlank()) scopeWorld = null;
    }

    /** The earlier form with no base query: the filter line is rebuilt from the scope and time, with no click. */
    public LookupSummaryView(long resultCount, Integer radius, String timeLabel, String worldLabel, long queryMillis) {
        this(resultCount, radius, timeLabel, worldLabel, queryMillis, null, radius == null ? null : worldLabel);
    }

    /** "1 result" or "N results". */
    public String resultText() {
        return resultCount + (resultCount == 1L ? " result" : " results");
    }

    /** "Query: N ms". */
    public String queryText() {
        return "Query: " + queryMillis + " ms";
    }

    /** The filter line's tokens, in the search's order; quoted values with spaces stay one token. */
    public List<String> filterTokens() {
        if (baseQuery != null) return splitTokens(baseQuery);
        List<String> tokens = new ArrayList<>();
        if (radius != null) tokens.add("radius:" + radius);
        else if (ALL_DIMENSIONS.equals(worldLabel)) tokens.add("global");
        else tokens.add("world:" + worldLabel);
        tokens.add("time:" + timeLabel);
        return List.copyOf(tokens);
    }

    /** The filter line's click: "/eventtrail lookup <base query>", only when the base query is known. */
    public Optional<String> editCommand() {
        return baseQuery == null ? Optional.empty() : Optional.of(LOOKUP_COMMAND_PREFIX + baseQuery);
    }

    /** Splits on spaces outside double quotes; a backslash-escaped quote does not end a quoted value. */
    public static List<String> splitTokens(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < query.length(); index++) {
            char ch = query.charAt(index);
            if (ch == '\\' && quoted && index + 1 < query.length()) {
                current.append(ch).append(query.charAt(++index));
                continue;
            }
            if (ch == '"') quoted = !quoted;
            if (ch == ' ' && !quoted) {
                if (!current.isEmpty()) tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return List.copyOf(tokens);
    }
}
