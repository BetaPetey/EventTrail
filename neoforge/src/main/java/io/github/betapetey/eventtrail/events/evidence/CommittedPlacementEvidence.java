package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.CanonicalBlockStateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.UUID;

/** Immutable final-world proof for one member of a placement operation. */
public record CommittedPlacementEvidence(
        long timestamp,
        UUID operationId,
        int memberIndex,
        int retainedMemberCount,
        boolean firstSnapshot,
        ActorProvenance actor,
        PlacementItemProvenance itemProvenance,
        ResourceLocation dimensionId,
        BlockPos position,
        BlockState preUseState,
        BlockState eventState,
        BlockState finalState,
        CanonicalBlockStateSnapshot canonicalFinalState,
        BlockEntityEvidence blockEntityEvidence,
        BlockEffectTruncation truncation) {

    public CommittedPlacementEvidence {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        operationId = Objects.requireNonNull(operationId, "operationId");
        if (memberIndex < 0 || retainedMemberCount <= memberIndex) {
            throw new IllegalArgumentException("member index must belong to the retained operation");
        }
        actor = Objects.requireNonNull(actor, "actor");
        if (actor.playerId() == null || actor.causality() != ActorProvenance.Causality.DIRECT) {
            throw new IllegalArgumentException("placement evidence requires a direct player actor");
        }
        itemProvenance = Objects.requireNonNull(itemProvenance, "itemProvenance");
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        preUseState = Objects.requireNonNull(preUseState, "preUseState");
        eventState = Objects.requireNonNull(eventState, "eventState");
        finalState = Objects.requireNonNull(finalState, "finalState");
        if (finalState.isAir() || finalState.equals(preUseState)) {
            throw new IllegalArgumentException("placement evidence requires a changed non-air final state");
        }
        canonicalFinalState = Objects.requireNonNull(canonicalFinalState, "canonicalFinalState");
        blockEntityEvidence = Objects.requireNonNull(blockEntityEvidence, "blockEntityEvidence");
        truncation = Objects.requireNonNull(truncation, "truncation");
        if (truncation != BlockEffectTruncation.NONE
                && truncation != BlockEffectTruncation.PLACEMENT_MEMBER_LIMIT) {
            throw new IllegalArgumentException("placement evidence has an invalid truncation status");
        }
    }

    public boolean transformedAfterEvent() {
        return !eventState.equals(finalState);
    }
}
