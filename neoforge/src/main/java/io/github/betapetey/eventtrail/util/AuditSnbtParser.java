package io.github.betapetey.eventtrail.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

/** Bounded parser for untrusted private audit SNBT. */
public final class AuditSnbtParser {
    public static final int MAX_PAYLOAD_CHARS = 70_000;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_COMPONENT_JSON_CHARS = 65_536;
    public static final int MAX_COMPONENT_JSON_DEPTH = 16;
    public static final int MAX_COMPONENT_JSON_TOKENS = 4_096;

    private AuditSnbtParser() {}

    /** Parses a bounded SNBT payload embedded in any historical EventTrail field. */
    public static ParseResult parsePayload(String storedValue, int payloadOffset) {
        if (storedValue == null || payloadOffset < 0 || payloadOffset > storedValue.length()) {
            return ParseResult.failure(Status.MALFORMED);
        }

        int length = storedValue.length() - payloadOffset;
        if (length > MAX_PAYLOAD_CHARS) {
            return ParseResult.failure(Status.TOO_LARGE);
        }

        Status structure = validateStructure(storedValue, payloadOffset);
        if (structure != Status.OK) {
            return ParseResult.failure(structure);
        }

        try {
            return new ParseResult(
                    Status.OK,
                    TagParser.parseTag(storedValue.substring(payloadOffset)));
        } catch (Exception | StackOverflowError ignored) {
            return ParseResult.failure(Status.MALFORMED);
        }
    }

    static Status validateStructure(String value, int offset) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;

        for (int i = offset; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }

            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '{' || current == '[') {
                if (++depth > MAX_NESTING_DEPTH) return Status.TOO_COMPLEX;
            } else if (current == '}' || current == ']') {
                if (--depth < 0) return Status.MALFORMED;
            }
        }
        return quote == 0 && depth == 0 ? Status.OK : Status.MALFORMED;
    }

    /**
     * Performs a cheap, quote-aware structural check before an embedded text
     * component is handed to either Minecraft's component parser or Gson.
     * JSON stored inside an SNBT string is opaque to {@link #validateStructure},
     * so it needs its own independent depth and breadth limits.
     */
    public static Status validateComponentJson(String value) {
        if (value == null || value.isBlank()) return Status.MALFORMED;
        if (value.length() > MAX_COMPONENT_JSON_CHARS) return Status.TOO_LARGE;

        char[] expectedClosings = new char[MAX_COMPONENT_JSON_DEPTH];
        int depth = 0;
        int tokens = 0;
        boolean quoted = false;
        boolean escaped = false;
        boolean primitive = false;

        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }

            if (primitive) {
                if (Character.isWhitespace(current)
                        || current == ',' || current == ']' || current == '}') {
                    primitive = false;
                } else {
                    continue;
                }
            }

            if (Character.isWhitespace(current)) {
                continue;
            }
            switch (current) {
                case '"' -> {
                    quoted = true;
                    if (++tokens > MAX_COMPONENT_JSON_TOKENS) return Status.TOO_COMPLEX;
                }
                case '{', '[' -> {
                    if (depth >= MAX_COMPONENT_JSON_DEPTH) return Status.TOO_COMPLEX;
                    expectedClosings[depth++] = current == '{' ? '}' : ']';
                    if (++tokens > MAX_COMPONENT_JSON_TOKENS) return Status.TOO_COMPLEX;
                }
                case '}', ']' -> {
                    if (depth == 0 || expectedClosings[depth - 1] != current) {
                        return Status.MALFORMED;
                    }
                    depth--;
                    if (++tokens > MAX_COMPONENT_JSON_TOKENS) return Status.TOO_COMPLEX;
                }
                case ',', ':' -> {
                    if (++tokens > MAX_COMPONENT_JSON_TOKENS) return Status.TOO_COMPLEX;
                }
                default -> {
                    primitive = true;
                    if (++tokens > MAX_COMPONENT_JSON_TOKENS) return Status.TOO_COMPLEX;
                }
            }
        }
        return !quoted && !escaped && depth == 0 ? Status.OK : Status.MALFORMED;
    }

    public enum Status { OK, TOO_LARGE, TOO_COMPLEX, MALFORMED }

    public record ParseResult(Status status, CompoundTag tag) {
        private static ParseResult failure(Status status) {
            return new ParseResult(status, null);
        }

        public boolean successful() {
            return status == Status.OK && tag != null;
        }
    }
}
