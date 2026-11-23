package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.*;
import io.github.betapetey.eventtrail.events.block.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GF-SCOPE: a block change committed inside a real player's action scope uses the unchanged canonical block
 * payload and action encoding, with the player as an immediate-secondary actor (the scope is the proof).
 */
public final class ScopeBlockEvidenceNormalizer {
    private ScopeBlockEvidenceNormalizer() {}

    public static AuditRecord committed(long timestamp, AuditAction action, ActorProvenance actor,
            AuditLocation location, BlockState state, BlockEntityEvidence metadata) {
        if ((action != AuditAction.BLOCK_BREAK && action != AuditAction.BLOCK_PLACE)
                || actor == null || actor.actorKind() != ActorProvenance.ActorKind.PLAYER
                || actor.causality() != ActorProvenance.Causality.IMMEDIATE_SECONDARY
                || location == null || state == null || state.isAir() || metadata == null) {
            throw new IllegalArgumentException("A scope block fact requires a human, a location and a non-air state");
        }
        String technical = CanonicalBlockStateSnapshot.capture(state).storageText();
        String display = DisplayNameResolver.capturedBlockStateDisplayName(technical, metadata);
        var payload = BlockEffectPayloadAdapter.from(actor, technical, display, metadata, BlockEffectTruncation.NONE);
        return AuditRecord.captureAt(timestamp, action, ActorAttributionAdapter.indexedActor(actor),
                location, new AuditSubject(AuditSubject.Kind.BLOCK, technical, display),
                new AuditEvidence.Block(BlockEffectPayloadCodec.CURRENT_VERSION, BlockEffectPayloadCodec.encode(payload)));
    }
}
