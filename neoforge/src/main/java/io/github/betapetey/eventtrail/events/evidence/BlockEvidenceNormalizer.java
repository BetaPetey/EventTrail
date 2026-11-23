package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayload;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import io.github.betapetey.eventtrail.events.block.ExplosionPartText;
import io.github.betapetey.eventtrail.events.block.ExplosionTruncationText;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Maps immutable committed block evidence into the grouped audit model. */
public final class BlockEvidenceNormalizer {
    private BlockEvidenceNormalizer() {}

    public static AuditRecord toAuditRecord(CommittedBlockEvidence evidence) {
        return toAuditRecord(evidence, new AuditLocation(evidence.preimage().dimensionId(), evidence.preimage().position()));
    }

    /** Native preimage stays unchanged; the producer supplies its frozen commit location. */
    public static AuditRecord toAuditRecord(CommittedBlockEvidence evidence, AuditLocation location) {
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.preimage().dimensionId().equals(location.dimensionId())) throw new IllegalArgumentException("Frame dimension differs");
        ActorProvenance actor = evidence.actor();
        BlockAuditPreimage preimage = evidence.preimage();
        String technicalState = preimage.canonicalBlockState().storageText();
        String presentation = DisplayNameResolver.capturedBlockStateDisplayName(
                technicalState, preimage.blockEntityEvidence());
        BlockEffectPayload payload = BlockEffectPayloadAdapter.from(
                actor,
                technicalState,
                presentation,
                preimage.blockEntityEvidence(),
                BlockEffectTruncation.NONE);
        String encodedPayload = BlockEffectPayloadCodec.encode(payload);
        return AuditRecord.captureAt(
                preimage.timestamp(),
                evidence.action(),
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

    /**
     * Explosion split (design-02 1.3): {@code untagged}, a block row built by {@link #toAuditRecord}, re-built with
     * the block-form {@code tag} in its payload (payload version 2). Time, actor, location and subject are unchanged.
     * The payload is decoded and re-encoded (the session keeps rows, not evidence; Advisor m-5). Advisor M-1: the
     * session counted the largest tag against every size limit when it listed the row, so this cannot fail for size.
     *
     * @throws IllegalArgumentException when {@code untagged} is not an untagged explosion block row
     */
    public static AuditRecord withExplosionTag(AuditRecord untagged, ExplosionPartTag tag) {
        Objects.requireNonNull(untagged, "untagged");
        Objects.requireNonNull(tag, "tag");
        if (tag.form() != ExplosionPartTag.Form.BLOCK) throw new IllegalArgumentException("A block-form tag is required");
        if (untagged.action() != AuditAction.EXPLOSION
                || !(untagged.evidence() instanceof AuditEvidence.Block block)
                || block.version() != BlockEffectPayloadCodec.CURRENT_VERSION) {
            throw new IllegalArgumentException("Only an untagged explosion block row can be tagged");
        }
        BlockEffectPayloadCodec.DecodeResult decoded = BlockEffectPayloadCodec.decode(block.encodedPayload());
        if (!decoded.successful() || decoded.payload().explosionTag() != null) {
            throw new IllegalArgumentException("Explosion block row payload is not decodable");
        }
        BlockEffectPayload tagged = decoded.payload().withExplosionTag(tag);
        return AuditRecord.captureAt(
                untagged.occurredAtEpochMillis(),
                untagged.action(),
                untagged.attribution(),
                untagged.location(),
                untagged.subject(),
                new AuditEvidence.Block(
                        BlockEffectPayloadCodec.TAGGED_VERSION,
                        BlockEffectPayloadCodec.encode(tagged)));
    }

    /**
     * Explosion split (design-02 1.3): the part row that opens part {@code tag.index()} of an explosion stored in
     * parts: action EXPLOSION, the explosion's actor, at the explosion centre, stamped {@code tag.partTimestamp()}
     * (Advisor M-7: 1 ms before every row of the explosion), subject OPERATION {@code eventtrail:explosion_part}, the
     * text regenerated from the tag (ExplosionPartText), reason code {@code reason}, and the part-form tag (payload
     * version 2).
     */
    public static AuditRecord toExplosionPartRecord(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center,
            ExplosionPartTag tag,
            BlockEffectTruncation reason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(tag, "tag");
        String presentation = ExplosionPartText.presentation(tag, reason);
        BlockEffectPayload payload = BlockEffectPayloadAdapter.from(
                actor,
                ExplosionPartText.TECHNICAL_IDENTITY,
                presentation,
                BlockEntityEvidence.absent(),
                reason,
                BlockEffectPayload.SubjectKind.OPERATION).withExplosionTag(tag);
        return AuditRecord.captureAt(
                tag.partTimestamp(),
                AuditAction.EXPLOSION,
                ActorAttributionAdapter.indexedActor(actor),
                new AuditLocation(dimensionId, center),
                new AuditSubject(
                        AuditSubject.Kind.OPERATION,
                        ExplosionPartText.TECHNICAL_IDENTITY,
                        presentation),
                new AuditEvidence.Block(
                        BlockEffectPayloadCodec.TAGGED_VERSION,
                        BlockEffectPayloadCodec.encode(payload)));
    }

    /**
     * One structural operation row explaining why an explosion's action is incomplete: its reason code says why, and
     * its text says how many destroyed blocks the action does not list and whether the explosion's list was cut short
     * by an error (ExplosionTruncationText). The n6-3 form: this build stores a truncated explosion in parts instead
     * ("part 1 of 1", design-02 1.1) and no longer writes this row; it is kept so tests can build existing data.
     */
    public static AuditRecord toExplosionTruncationRecord(
            ExplosionAuditSession.Summary summary, long occurredAtEpochMillis) {
        Objects.requireNonNull(summary, "summary");
        if (!summary.truncated()) {
            throw new IllegalArgumentException("Only truncated explosion summaries are persisted");
        }
        String presentation = ExplosionTruncationText.presentation(
                summary.notListed(),
                summary.notListedExact(),
                summary.truncation() == BlockEffectTruncation.EXPLOSION_PROCESSING_FAILURE);
        BlockEffectPayload payload = BlockEffectPayloadAdapter.from(
                summary.actor(),
                ExplosionTruncationText.TECHNICAL_IDENTITY,
                presentation,
                BlockEntityEvidence.absent(),
                summary.truncation(),
                BlockEffectPayload.SubjectKind.OPERATION);
        return AuditRecord.captureAt(
                occurredAtEpochMillis,
                io.github.betapetey.eventtrail.audit.AuditAction.EXPLOSION,
                ActorAttributionAdapter.indexedActor(summary.actor()),
                new AuditLocation(summary.dimensionId(), summary.center()),
                new AuditSubject(
                        AuditSubject.Kind.OPERATION,
                        ExplosionTruncationText.TECHNICAL_IDENTITY,
                        presentation),
                new AuditEvidence.Block(
                        BlockEffectPayloadCodec.CURRENT_VERSION,
                        BlockEffectPayloadCodec.encode(payload)));
    }
}
