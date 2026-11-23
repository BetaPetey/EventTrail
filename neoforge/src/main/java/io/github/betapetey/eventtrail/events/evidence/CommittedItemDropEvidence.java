package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Immutable world-admission proof for one explicit player discard operation. */
public record CommittedItemDropEvidence(
        long timestamp,
        UUID operationId,
        UUID itemEntityId,
        ActorProvenance actor,
        ResourceLocation dimensionId,
        BlockPos position,
        ItemStack stack) {

    public CommittedItemDropEvidence {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        operationId = Objects.requireNonNull(operationId, "operationId");
        itemEntityId = Objects.requireNonNull(itemEntityId, "itemEntityId");
        actor = Objects.requireNonNull(actor, "actor");
        if (actor.playerId() == null || actor.causality() != ActorProvenance.Causality.DIRECT) {
            throw new IllegalArgumentException("item-drop evidence requires a direct player actor");
        }
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("item-drop evidence requires a non-empty admitted stack");
        }
        stack = stack.copy();
    }

    /** Never exposes the evidence object's retained mutable stack instance. */
    @Override
    public ItemStack stack() {
        return stack.copy();
    }
}
