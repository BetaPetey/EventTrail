package io.github.betapetey.eventtrail.audit;

import java.util.Objects;

/** Technical subject identity kept separate from bounded presentation text. */
public record AuditSubject(Kind kind, String technicalIdentity, String presentationIdentity) {
    public AuditSubject {
        kind = Objects.requireNonNull(kind, "kind");
        technicalIdentity = requireText(technicalIdentity, "technicalIdentity");
        presentationIdentity = AuditText.boundedRequired(
                presentationIdentity, "presentationIdentity");
    }

    public static AuditSubject technical(Kind kind, String technicalIdentity) {
        return new AuditSubject(kind, technicalIdentity, technicalIdentity);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Kind {
        BLOCK,
        ITEM,
        ENTITY,
        CONTAINER,
        OPERATION
    }
}
