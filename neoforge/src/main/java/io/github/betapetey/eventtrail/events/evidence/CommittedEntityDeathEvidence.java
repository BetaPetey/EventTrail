package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable proof candidate for one exact completed living-entity death.
 *
 * <p>{@code lastHitBy} is the Owner's Q265 (a) note (11-23 1:28 PM): on a player's death with no player credit, the
 * other player whose hit was the victim's last, inside the game's own 100-tick window. It is a plain note on the
 * victim's death row only: never a kill, never an accountable player, never indexed. Null for every other death.</p>
 */
public record CommittedEntityDeathEvidence(
        long timestamp,
        UUID operationId,
        EntityIdentity victim,
        ResourceLocation dimensionId,
        BlockPos position,
        EntityDamageEvidence damage,
        EntityKillAttribution attribution,
        boolean victimPlayerTracked,
        ProofKind proofKind,
        LastHit lastHitBy) {

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
        if (lastHitBy != null) {
            if (!victimIsPlayer) {
                throw new IllegalArgumentException("Only a player's death can carry a last-hit note");
            }
            if (attribution.provenance() != EntityKillAttribution.ProvenanceKind.ENVIRONMENT
                    || attribution.accountablePlayer() != null) {
                throw new IllegalArgumentException("A last-hit note sits only on a death with no player credit");
            }
            if (lastHitBy.playerId().equals(victim.entityId())) {
                throw new IllegalArgumentException("A player's own hit is never their last-hit note");
            }
        }
    }

    /** The shape before the last-hit note: no note. */
    public CommittedEntityDeathEvidence(
            long timestamp,
            UUID operationId,
            EntityIdentity victim,
            ResourceLocation dimensionId,
            BlockPos position,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean victimPlayerTracked,
            ProofKind proofKind) {
        this(timestamp, operationId, victim, dimensionId, position, damage, attribution, victimPlayerTracked,
                proofKind, null);
    }

    /** The same evidence with {@code note} as its last-hit note. */
    public CommittedEntityDeathEvidence withLastHitBy(LastHit note) {
        return new CommittedEntityDeathEvidence(timestamp, operationId, victim, dimensionId, position, damage,
                attribution, victimPlayerTracked, proofKind, note);
    }

    public enum ProofKind {
        ACCEPTED_EVENT_AND_COMPLETED_DIE_OPERATION
    }

    /** The last hitter a "last hit by" note names: their UUID and their name as captured at the death. */
    public record LastHit(UUID playerId, String playerName) {
        public LastHit {
            playerId = Objects.requireNonNull(playerId, "playerId");
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("playerName must not be blank");
            }
            playerName = EventTrailTextBounds.boundAuditDescription(playerName);
        }
    }
}
