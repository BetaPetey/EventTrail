package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Immutable proof that one exact stack entered a player's accountable inventory. */
public record CommittedItemPickupEvidence(
        long timestamp,
        UUID itemEntityId,
        ActorProvenance actor,
        ResourceLocation dimensionId,
        BlockPos position,
        ItemStack stack,
        ProofKind proofKind) {

    public CommittedItemPickupEvidence {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        itemEntityId = Objects.requireNonNull(itemEntityId, "itemEntityId");
        actor = Objects.requireNonNull(actor, "actor");
        if (actor.playerId() == null || actor.causality() != ActorProvenance.Causality.DIRECT) {
            throw new IllegalArgumentException("item-pickup evidence requires a direct player actor");
        }
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("item-pickup evidence requires a non-empty accepted stack");
        }
        stack = stack.copy();
        proofKind = Objects.requireNonNull(proofKind, "proofKind");
    }

    /** Never exposes the evidence object's retained mutable stack instance. */
    @Override
    public ItemStack stack() {
        return stack.copy();
    }

    public enum ProofKind {
        PLAYER_INVENTORY_NET_GAIN
    }
}
