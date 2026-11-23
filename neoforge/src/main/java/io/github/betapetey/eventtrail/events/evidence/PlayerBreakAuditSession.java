package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Operation-scoped state for one {@code ServerPlayerGameMode.destroyBlock}
 * invocation. The enclosing mixin owns the lifetime, so evidence cannot outlive
 * or be confused with a later break at the same coordinates.
 */
public final class PlayerBreakAuditSession {
    private final BlockPos requestedPosition;

    private ActorProvenance actor;
    private ResourceLocation dimensionId;
    private BlockState removalState;
    private BlockAuditPreimage preimage;
    private boolean eventAccepted;
    private boolean removalBoundaryClaimed;
    private boolean removalCommitted;
    private boolean invalid;
    private boolean closed;

    public PlayerBreakAuditSession(BlockPos requestedPosition) {
        this.requestedPosition = Objects.requireNonNull(requestedPosition, "requestedPosition").immutable();
    }

    /** Admits the candidate only after every BreakEvent listener has returned. */
    public boolean acceptBreakEvent(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos position) {
        if (closed || invalid || eventAccepted
                || !requestedPosition.equals(position)
                || actor == null
                || actor.playerId() == null
                || actor.causality() != ActorProvenance.Causality.DIRECT) {
            invalidate();
            return false;
        }

        this.actor = actor;
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.eventAccepted = true;
        return true;
    }

    /**
     * Claims the exact removal boundary and rejects stale/replaced state before
     * metadata is captured. A second boundary in one operation is ambiguous.
     */
    public boolean beginRemovalBoundary(
            ResourceLocation dimensionId,
            BlockPos position,
            UUID playerId,
            BlockState liveState,
            BlockState operationState) {
        if (closed || invalid || !eventAccepted || removalBoundaryClaimed) {
            invalidate();
            return false;
        }
        removalBoundaryClaimed = true;

        if (!this.dimensionId.equals(dimensionId)
                || !requestedPosition.equals(position)
                || !actor.playerId().equals(playerId)
                || liveState == null
                || operationState == null
                || !liveState.equals(operationState)) {
            invalidate();
            return false;
        }

        this.removalState = operationState;
        return true;
    }

    /**
     * Records the platform's authoritative onDestroyedByPlayer result and the state read right after it returned.
     * GF-BLOCKIDENTITY: the block counts as removed only when the Block at that moment differs from the preimage
     * Block; a block that only lost a stage or a property (a crystal cluster one stage lower) is no break, and a
     * block on the Q38 list never is.
     */
    public void recordRemovalResult(BlockAuditPreimage preimage, boolean removed, BlockState postRemovalState) {
        if (closed || invalid || !removalBoundaryClaimed || preimage == null
                || !preimage.dimensionId().equals(dimensionId)
                || !preimage.position().equals(requestedPosition)
                || !preimage.blockState().equals(removalState)) {
            invalidate();
            return;
        }
        if (!removed
                || !BlockIdentity.blockDiffers(preimage.blockState(), postRemovalState)
                || BlockIdentity.excluded(preimage.blockState())) {
            invalidate();
            return;
        }

        this.preimage = preimage;
        this.removalCommitted = true;
    }

    /**
     * Closes this exact operation and returns evidence only when both the
     * authoritative removal result and the final post-operation state agree.
     */
    public Optional<CommittedBlockEvidence> complete(
            ResourceLocation dimensionId,
            BlockPos position,
            BlockState finalState,
            boolean destroyBlockSucceeded) {
        if (closed) return Optional.empty();
        closed = true;

        if (invalid || !eventAccepted || !removalCommitted
                || preimage == null
                || !this.dimensionId.equals(dimensionId)
                || !requestedPosition.equals(position)) {
            clearRetainedEvidence();
            return Optional.empty();
        }

        Optional<CommittedBlockEvidence> result = CommittedBlockEvidence.verify(
                AuditAction.BLOCK_BREAK,
                actor,
                preimage,
                finalState,
                destroyBlockSucceeded,
                CommittedBlockEvidence.ProofKind.PLAYER_REMOVAL_RESULT_AND_POST_STATE);
        clearRetainedEvidence();
        return result;
    }

    public void abort() {
        if (!closed) {
            invalid = true;
            closed = true;
            clearRetainedEvidence();
        }
    }

    private void invalidate() {
        invalid = true;
        clearRetainedEvidence();
    }

    private void clearRetainedEvidence() {
        preimage = null;
        removalState = null;
    }
}
