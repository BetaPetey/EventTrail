package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.util.EventTrailText;

import java.util.Objects;
import java.util.UUID;

/**
 * Queryable row identity and causal classification.
 *
 * <p>The indexed party is deliberately role-tagged because an entity-death row
 * indexes the victim, while a kill row indexes the accountable killer.</p>
 */
public record AuditAttribution(
        IndexedParty indexedParty,
        CauseKind causeKind,
        Causality causality,
        String causeDetail) {

    public static final String ENVIRONMENT_STORAGE_ID = "ENVIRONMENT";

    /**
     * Largest indexed name a new row stores, in UTF-16 units: the player_name column holds 255 characters on
     * every backend (F1). The restore path keeps the record's wider bound, so older stored rows still decode.
     */
    public static final int MAX_INDEXED_NAME_CHARS = 255;

    public AuditAttribution {
        indexedParty = Objects.requireNonNull(indexedParty, "indexedParty");
        causeKind = Objects.requireNonNull(causeKind, "causeKind");
        causality = Objects.requireNonNull(causality, "causality");
        causeDetail = AuditText.boundedNullable(causeDetail);

        if ((causeKind == CauseKind.ENVIRONMENT || causeKind == CauseKind.UNATTRIBUTED)
                != (causality == Causality.UNATTRIBUTED)) {
            throw new IllegalArgumentException(
                    "Environment/unattributed causes require unattributed causality and vice versa");
        }
    }

    public static AuditAttribution actor(
            UUID playerId,
            String canonicalName,
            boolean synthetic,
            Causality causality,
            String detail) {
        if (causality == Causality.UNATTRIBUTED) {
            throw new IllegalArgumentException("A player actor must have direct or immediate causality");
        }
        IdentityKind kind = synthetic ? IdentityKind.SYNTHETIC_PLAYER : IdentityKind.PLAYER;
        CauseKind cause = synthetic ? CauseKind.SYNTHETIC_PLAYER : CauseKind.PLAYER;
        return new AuditAttribution(
                new IndexedParty(IndexedRole.ACTOR, kind, playerId, indexedName(canonicalName)),
                cause,
                causality,
                detail);
    }

    public static AuditAttribution victim(
            UUID playerId,
            String canonicalName,
            boolean synthetic,
            CauseKind causeKind,
            Causality causality,
            String detail) {
        return new AuditAttribution(
                new IndexedParty(
                        IndexedRole.VICTIM,
                        synthetic ? IdentityKind.SYNTHETIC_PLAYER : IdentityKind.PLAYER,
                        playerId,
                        indexedName(canonicalName)),
                causeKind,
                causality,
                detail);
    }

    public static AuditAttribution environment(String name, String detail) {
        return new AuditAttribution(
                new IndexedParty(IndexedRole.ACTOR, IdentityKind.ENVIRONMENT, null, indexedName(name)),
                CauseKind.ENVIRONMENT,
                Causality.UNATTRIBUTED,
                detail);
    }

    /**
     * The name a new row indexes: unchanged when it has at most {@link #MAX_INDEXED_NAME_CHARS} UTF-16 units,
     * otherwise at most 252 units of whole characters followed by "...". Deterministic and idempotent, so
     * capture, encode and decode agree. Every capture-time {@link IndexedParty} construction must call it;
     * the restore path must not, so a stored row decodes with the name it was written with.
     */
    public static String indexedName(String value) {
        return EventTrailText.boundWithEllipsis(value, MAX_INDEXED_NAME_CHARS);
    }

    /**
     * Whether an indexed name matches the full name a payload carries: equal, or equal to that name's cut
     * ({@link #indexedName}). For every name of at most 255 units this is plain equality.
     */
    public static boolean sameIndexedName(String indexed, String payloadName) {
        if (indexed == null) return false;
        return indexed.equals(payloadName) || indexed.equals(indexedName(payloadName));
    }

    public record IndexedParty(
            IndexedRole role,
            IdentityKind kind,
            UUID playerId,
            String canonicalName) {
        public IndexedParty {
            role = Objects.requireNonNull(role, "role");
            kind = Objects.requireNonNull(kind, "kind");
            if (kind == IdentityKind.ENVIRONMENT) {
                if (playerId != null) {
                    throw new IllegalArgumentException("Environment identity cannot carry a player UUID");
                }
            } else {
                playerId = Objects.requireNonNull(playerId, "playerId");
            }
            if (canonicalName == null || canonicalName.isBlank()) {
                throw new IllegalArgumentException("canonicalName must not be blank");
            }
            canonicalName = AuditText.boundedRequired(canonicalName, "canonicalName");
        }

        public String storageId() {
            return playerId == null ? ENVIRONMENT_STORAGE_ID : playerId.toString();
        }
    }

    public enum IndexedRole { ACTOR, VICTIM }

    /**
     * UNCLASSIFIED_PLAYER is restore-only: a historical UUID-bearing row whose
     * private payload cannot prove whether the actor was human or synthetic.
     */
    public enum IdentityKind { PLAYER, SYNTHETIC_PLAYER, UNCLASSIFIED_PLAYER, ENVIRONMENT }

    public enum CauseKind { PLAYER, SYNTHETIC_PLAYER, ENVIRONMENT, UNATTRIBUTED }

    public enum Causality { DIRECT, IMMEDIATE_SECONDARY, UNATTRIBUTED }
}
