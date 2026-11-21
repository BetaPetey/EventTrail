package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Immutable proof candidate for one exact completed living-entity death. */
public record CommittedEntityDeathEvidence(
        long timestamp,
        UUID operationId,
        EntityIdentity victim,
        ResourceLocation dimensionId,
        BlockPos position,
        EntityDamageEvidence damage,
        EntityKillAttribution attribution,
        boolean victimPlayerTracked,
        ProofKind proofKind) {

    public CommittedEntityDeathEvidence {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        operationId = Objects.requireNonNull(operationId, "operationId");
        victim = Objects.requireNonNull(victim, "victim");
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        damage = Objects.requireNonNull(damage, "damage");
        attribution = Objects.requireNonNull(attribution, "attribution");
        boolean victimIsPlayer = victim.kind() == EntityIdentity.EntityKind.PLAYER
                || victim.kind() == EntityIdentity.EntityKind.SYNTHETIC_PLAYER;
        if (victimPlayerTracked && !victimIsPlayer) {
            throw new IllegalArgumentException(
                    "Only a player victim can carry an accepted player-death policy decision");
        }
        proofKind = Objects.requireNonNull(proofKind, "proofKind");
    }

    public enum ProofKind {
        ACCEPTED_EVENT_AND_COMPLETED_DIE_OPERATION
    }
}
