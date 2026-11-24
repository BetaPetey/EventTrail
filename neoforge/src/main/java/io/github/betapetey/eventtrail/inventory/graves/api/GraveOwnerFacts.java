package io.github.betapetey.eventtrail.inventory.graves.api;

import io.github.betapetey.eventtrail.audit.ShownCause;

import java.util.Optional;
import java.util.UUID;

/**
 * What a grave's owner read found (graves design r6, 1.2's table). {@code owner} is the value stored in the cause
 * (always built with {@link ShownCause.Owner#capture}), or null when the read found no owner; {@code reason} is the
 * table's counted reason, or null for a valid UUID. The attribution (OWN, OTHER, UNKNOWN) is never stored: it is
 * derived at read time from {@link #validatedUuid()} by UUID equality only.
 *
 * <p>The constructor holds the table's pairs, so "no owner" can never be claimed without a reason, and the record never
 * claims the mod had no owner when EventTrail only failed to read it.</p>
 */
public record GraveOwnerFacts(ShownCause.Owner owner, Reason reason) {

    /** The counted reasons of 1.2's table. */
    public enum Reason {
        GRAVE_OWNER_MISSING("grave-owner-missing"),
        GRAVE_OWNER_EMPTY("grave-owner-empty"),
        GRAVE_OWNER_MALFORMED("grave-owner-malformed"),
        GRAVE_READ_FAILED("grave-read-failed"),
        GRAVE_COLONY_MISSING("grave-colony-missing");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** The counted reason's text, as the design names it. */
        public String code() {
            return code;
        }
    }

    public GraveOwnerFacts {
        if (owner == null) {
            if (reason == null || reason == Reason.GRAVE_OWNER_EMPTY) {
                throw new IllegalArgumentException("No owner needs a missing, malformed, read-failed or colony reason");
            }
        } else {
            switch (owner.state()) {
                case VALIDATED -> {
                    if (reason != null) throw new IllegalArgumentException("A validated owner has no reason");
                    if (!canonicalUuid(owner.uuid())) {
                        throw new IllegalArgumentException("A validated owner needs a canonical UUID text");
                    }
                }
                case NOT_VALIDATED -> {
                    if (reason != Reason.GRAVE_OWNER_MISSING) {
                        throw new IllegalArgumentException("A not-validated owner is counted as grave-owner-missing");
                    }
                }
                case DEFAULT -> {
                    if (reason != Reason.GRAVE_OWNER_EMPTY) {
                        throw new IllegalArgumentException("The mod's own no-owner is counted as grave-owner-empty");
                    }
                }
            }
        }
    }

    /** The owner's UUID only when it is VALIDATED; own and other are decided by this alone. */
    public Optional<UUID> validatedUuid() {
        if (owner == null || owner.state() != ShownCause.OwnerState.VALIDATED) return Optional.empty();
        return Optional.of(UUID.fromString(owner.uuid()));
    }

    private static boolean canonicalUuid(String text) {
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
