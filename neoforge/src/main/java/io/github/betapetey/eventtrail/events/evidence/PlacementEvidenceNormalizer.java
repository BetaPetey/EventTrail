package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayload;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;

import java.util.Objects;

/** Maps committed placement postimages into immutable block audit facts. */
public final class PlacementEvidenceNormalizer {
    private PlacementEvidenceNormalizer() {
    }

    public static AuditRecord toAuditRecord(CommittedPlacementEvidence evidence) {
        return toAuditRecord(evidence, new AuditLocation(evidence.dimensionId(), evidence.position()));
    }

    /** The committed member's native state proof is separate from its physical audit address. */
    public static AuditRecord toAuditRecord(CommittedPlacementEvidence evidence, AuditLocation location) {
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.dimensionId().equals(location.dimensionId())) throw new IllegalArgumentException("Frame dimension differs");
        ActorProvenance actor = evidence.actor();
        String technicalState = evidence.canonicalFinalState().storageText();
        String presentation = DisplayNameResolver.capturedBlockStateDisplayName(
                technicalState, evidence.blockEntityEvidence());
        BlockEffectPayload payload = BlockEffectPayloadAdapter.from(
                actor,
                technicalState,
                presentation,
                evidence.blockEntityEvidence(),
                evidence.truncation());
        String encodedPayload = BlockEffectPayloadCodec.encode(payload);
        return AuditRecord.captureAt(
                evidence.timestamp(),
                AuditAction.BLOCK_PLACE,
                ActorAttributionAdapter.indexedActor(actor),
                location,
                new AuditSubject(
                        AuditSubject.Kind.BLOCK,
                        technicalState,
                        presentation),
                new AuditEvidence.Block(
                        BlockEffectPayloadCodec.CURRENT_VERSION,
                        encodedPayload));
    }
}
