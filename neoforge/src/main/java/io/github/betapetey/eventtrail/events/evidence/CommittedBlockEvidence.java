package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;

/** A block audit fact admitted only after its source-specific finality proof. */
public record CommittedBlockEvidence(
        AuditAction action,
        ActorProvenance actor,
        BlockAuditPreimage preimage,
        BlockState finalBlockState,
        ProofKind proofKind) {

    public CommittedBlockEvidence {
        action = Objects.requireNonNull(action, "action");
        actor = Objects.requireNonNull(actor, "actor");
        preimage = Objects.requireNonNull(preimage, "preimage");
        finalBlockState = Objects.requireNonNull(finalBlockState, "finalBlockState");
        proofKind = Objects.requireNonNull(proofKind, "proofKind");

        if (!preimage.differsFrom(finalBlockState)) {
            throw new IllegalArgumentException("Committed block evidence requires a changed final state");
        }

        boolean validPair = action == AuditAction.BLOCK_BREAK
                && proofKind == ProofKind.PLAYER_REMOVAL_RESULT_AND_POST_STATE
                || action == AuditAction.BLOCK_BREAK
                && proofKind == ProofKind.MOD_PIECE_REMOVAL_POST_STATE
                || action == AuditAction.EXPLOSION
                && proofKind == ProofKind.EXPLOSION_FINALIZATION_POST_STATE;
        if (!validPair) {
            throw new IllegalArgumentException("Unsupported action/finality proof combination");
        }
    }

    /**
     * Verifies the common committed-state invariant. The caller still owns the
     * exact source-specific boundary represented by {@code proofKind}.
     *
     * <p>GF-BLOCKIDENTITY: a block on the Q38 list is never evidence, and an explosion block counts only when its
     * Block changed ({@link BlockIdentity#blockDiffers}); a property-only change (a door or lever toggled) is not a
     * destroyed block.</p>
     */
    public static Optional<CommittedBlockEvidence> verify(
            AuditAction action,
            ActorProvenance actor,
            BlockAuditPreimage preimage,
            BlockState finalState,
            boolean mutationCommitted,
            ProofKind proofKind) {
        Objects.requireNonNull(preimage, "preimage");
        if (!mutationCommitted || !preimage.differsFrom(finalState)) {
            return Optional.empty();
        }
        if (BlockIdentity.excluded(preimage.blockState())
                || proofKind == ProofKind.EXPLOSION_FINALIZATION_POST_STATE
                && !BlockIdentity.blockDiffers(preimage.blockState(), finalState)) {
            return Optional.empty();
        }
        return Optional.of(new CommittedBlockEvidence(
                action, actor, preimage, finalState, proofKind));
    }

    public enum ProofKind {
        PLAYER_REMOVAL_RESULT_AND_POST_STATE,
        EXPLOSION_FINALIZATION_POST_STATE,
        /**
         * A part-removal action of a claimed multi-part block ended with the block gone (air, or exactly the old
         * state's fluid block) while the existing break path recorded no removal. In memory only: no row field and
         * no database value read this.
         */
        MOD_PIECE_REMOVAL_POST_STATE
    }
}
