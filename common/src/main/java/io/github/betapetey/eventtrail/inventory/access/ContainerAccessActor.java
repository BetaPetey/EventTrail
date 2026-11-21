package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.util.EventTrailTextBounds;

import java.util.Objects;
import java.util.UUID;

/** Immutable actor identity captured when an authoritative menu session opens. */
public record ContainerAccessActor(UUID playerId, String playerName, boolean synthetic) {
    public ContainerAccessActor {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        playerName = EventTrailTextBounds.boundAuditDescription(playerName);
    }
}
