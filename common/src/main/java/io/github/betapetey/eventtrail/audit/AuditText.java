package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.util.EventTrailText;

/** Local bounds for human-facing audit-domain text. */
final class AuditText {
    static final int MAX_PRESENTATION_CHARACTERS = 512;

    private AuditText() {
    }

    static String boundedRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return bounded(value);
    }

    static String boundedNullable(String value) {
        return value == null ? null : bounded(value);
    }

    private static String bounded(String value) {
        // Cut only between whole characters (T31-R2): never keep half of a surrogate pair.
        return EventTrailText.boundWithEllipsis(value, MAX_PRESENTATION_CHARACTERS);
    }
}
