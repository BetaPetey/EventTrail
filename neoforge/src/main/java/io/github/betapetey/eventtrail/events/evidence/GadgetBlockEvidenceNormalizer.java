package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.*;
import io.github.betapetey.eventtrail.events.block.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Source-proven gadget commits use the unchanged canonical block payload/action encoding. */
public final class GadgetBlockEvidenceNormalizer {
    private GadgetBlockEvidenceNormalizer() {}

    public static AuditRecord committed(long timestamp, AuditAction action, ActorProvenance actor,
            ResourceLocation dimension, BlockPos position, BlockState state, BlockEntityEvidence metadata) {
        if ((action != AuditAction.BLOCK_BREAK && action != AuditAction.BLOCK_PLACE)
                || actor == null || actor.actorKind() != ActorProvenance.ActorKind.PLAYER
                || actor.causality() != ActorProvenance.Causality.DIRECT || state == null || state.isAir()) {
            throw new IllegalArgumentException("A committed gadget block fact requires a human and non-air state");
        }
        String technical = CanonicalBlockStateSnapshot.capture(state).storageText();
        String display = DisplayNameResolver.capturedBlockStateDisplayName(technical, metadata);
        var payload = BlockEffectPayloadAdapter.from(actor, technical, display, metadata, BlockEffectTruncation.NONE);
        return AuditRecord.captureAt(timestamp, action, ActorAttributionAdapter.indexedActor(actor),
                new AuditLocation(dimension, position), new AuditSubject(AuditSubject.Kind.BLOCK, technical, display),
                new AuditEvidence.Block(BlockEffectPayloadCodec.CURRENT_VERSION, BlockEffectPayloadCodec.encode(payload)));
    }
}
