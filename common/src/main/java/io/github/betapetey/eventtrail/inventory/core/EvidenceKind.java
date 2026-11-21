package io.github.betapetey.eventtrail.inventory.core;

/** Provenance of inventory transaction evidence. */
public enum EvidenceKind {
    ACTION_SCOPED_PHYSICAL(true),
    AUTHORITATIVE_MOD_OPERATION(true);

    private final boolean authoritativeActionBoundary;

    EvidenceKind(boolean authoritativeActionBoundary) {
        this.authoritativeActionBoundary = authoritativeActionBoundary;
    }

    public boolean isAuthoritativeActionBoundary() {
        return authoritativeActionBoundary;
    }
}
