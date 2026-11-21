package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.util.EventTrailTextBounds;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable actor identity and causality captured before asynchronous audit
 * persistence. No live Minecraft player or entity is retained.
 */
public record ActorProvenance(
        ActorKind actorKind,
        Causality causality,
        UUID playerId,
        String displayName,
        String detail) {

    public static final String ENVIRONMENT_ROW_ID = "ENVIRONMENT";

    public ActorProvenance {
        actorKind = Objects.requireNonNull(actorKind, "actorKind");
        causality = Objects.requireNonNull(causality, "causality");
        displayName = requireBoundedText(displayName, "displayName");
        detail = EventTrailTextBounds.boundAuditDescription(detail);

        if (actorKind == ActorKind.ENVIRONMENT) {
            if (playerId != null) {
                throw new IllegalArgumentException("Environment provenance cannot carry a player UUID");
            }
            if (causality != Causality.UNATTRIBUTED) {
                throw new IllegalArgumentException("Environment provenance must be unattributed");
            }
        } else {
            Objects.requireNonNull(playerId, "playerId");
            if (causality == Causality.UNATTRIBUTED) {
                throw new IllegalArgumentException("Player provenance requires direct or immediate causality");
            }
        }
    }

    /**
     * Admits human actors for new recording from platform-captured identity facts.
     * Loader adapters, not this shared value type, classify synthetic players.
     * Historical synthetic provenance remains representable by this value type.
     */
    public static Optional<ActorProvenance> forPlayerIdentity(
            UUID playerId,
            String playerName,
            boolean synthetic,
            Causality causality,
            String detail) {
        if (synthetic) {
            return Optional.empty();
        }

        return Optional.of(new ActorProvenance(
                ActorKind.PLAYER,
                causality,
                Objects.requireNonNull(playerId, "playerId"),
                playerName,
                detail));
    }

    public static ActorProvenance environment(String displayName, String detail) {
        return new ActorProvenance(
                ActorKind.ENVIRONMENT,
                Causality.UNATTRIBUTED,
                null,
                displayName,
                detail);
    }

    public String rowActorId() {
        return playerId == null ? ENVIRONMENT_ROW_ID : playerId.toString();
    }

    public String rowActorName() {
        return displayName;
    }

    private static String requireBoundedText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return EventTrailTextBounds.boundAuditDescription(value);
    }

    public enum ActorKind {
        PLAYER,
        SYNTHETIC_PLAYER,
        ENVIRONMENT
    }

    public enum Causality {
        DIRECT,
        IMMEDIATE_SECONDARY,
        UNATTRIBUTED
    }
}
