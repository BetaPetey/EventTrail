package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.audit.ShownCause;

import java.util.UUID;

/**
 * Whose grave items a row moved, relative to the row's indexed player (graves design r6, 1.2). It is derived at read
 * time from stored facts and never stored as a field.
 *
 * <p>OWN and OTHER need a valid stored owner UUID: a VALIDATED owner whose UUID text is canonical. They are decided by
 * UUID equality only, never by a name. Every other case (no owner, a name without a valid UUID, the mod's own "no owner
 * set", no indexed player) is UNKNOWN.</p>
 */
public enum GraveAttribution {
    /** The stored owner UUID is valid and equals the indexed player's UUID. */
    OWN,
    /** The stored owner UUID is valid and differs from the indexed player's UUID. */
    OTHER,
    /** Every other case. */
    UNKNOWN;

    /** The attribution of a stored cause owner (null for no owner) for the indexed player (null for none). */
    public static GraveAttribution of(ShownCause.Owner owner, UUID indexedPlayer) {
        if (owner == null || owner.state() != ShownCause.OwnerState.VALIDATED) return UNKNOWN;
        return of(canonicalUuid(owner.uuid()), indexedPlayer);
    }

    /** The attribution of a stored owner UUID (null when none was stored) for the indexed player (null for none). */
    public static GraveAttribution of(UUID ownerUuid, UUID indexedPlayer) {
        if (ownerUuid == null || indexedPlayer == null) return UNKNOWN;
        return ownerUuid.equals(indexedPlayer) ? OWN : OTHER;
    }

    /** The UUID a canonical UUID text names, or null for any other text: a stored value is never guessed at. */
    private static UUID canonicalUuid(String text) {
        if (text == null) return null;
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equals(text) ? parsed : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
