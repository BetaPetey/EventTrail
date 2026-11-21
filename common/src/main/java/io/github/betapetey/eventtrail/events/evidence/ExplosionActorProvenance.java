package io.github.betapetey.eventtrail.events.evidence;

import java.util.Optional;
import java.util.UUID;

/** Shared immutable actor factory for platform-resolved explosion evidence. */
public final class ExplosionActorProvenance {
    private ExplosionActorProvenance() {}

    public static Optional<ActorProvenance> playerIdentity(
            UUID playerId,
            String playerName,
            boolean synthetic,
            ActorProvenance.Causality causality,
            String trackedDetail) {
        return ActorProvenance.forPlayerIdentity(
                playerId,
                playerName,
                synthetic,
                causality,
                trackedDetail);
    }
}
