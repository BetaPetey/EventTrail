package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.events.block.BlockEffectPayload;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.CanonicalBlockStateSnapshot;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import io.github.betapetey.eventtrail.events.block.ExplosionPartText;
import io.github.betapetey.eventtrail.events.block.ExplosionTruncationText;
import io.github.betapetey.eventtrail.events.item.ItemAuditPayload;
import io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPhase;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayload;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.EntityEventPayloadCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Production action-family decoder for EventTrail's private schema-v1 payloads.
 *
 * <p>Every actor/cause decision is derived from a typed payload field. Stored
 * indexed-name/UUID columns are intentionally outside this boundary and cannot
 * turn an unavailable payload into guessed human attribution.</p>
 */
public final class ProductionAuditPayloadCodec implements AuditPayloadCodec {
    private static final ProductionAuditPayloadCodec INSTANCE = new ProductionAuditPayloadCodec();

    private ProductionAuditPayloadCodec() {
    }

    public static ProductionAuditPayloadCodec instance() {
        return INSTANCE;
    }

    @Override
    public DecodedProjection decode(
            AuditAction action,
            String technicalSubjectIdentity,
            String encodedPayload) {
        Objects.requireNonNull(action, "action");
        if (technicalSubjectIdentity == null || technicalSubjectIdentity.isBlank()) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        return switch (action.evidenceKind()) {
            case BLOCK -> decodeBlock(action, technicalSubjectIdentity, encodedPayload);
            case ITEM -> decodeItem(action, technicalSubjectIdentity, encodedPayload);
            case ENTITY -> decodeEntity(action, technicalSubjectIdentity, encodedPayload);
            case CONTAINER_ACCESS -> decodeAccess(action, technicalSubjectIdentity, encodedPayload);
            case CONTAINER_TRANSFER -> decodeTransfer(action, technicalSubjectIdentity, encodedPayload);
        };
    }

    private static DecodedProjection decodeBlock(
            AuditAction action,
            String technicalSubject,
            String encoded) {
        BlockEffectPayloadCodec.DecodeResult decoded = BlockEffectPayloadCodec.decode(encoded);
        if (!decoded.successful()) {
            return unavailable(action, blockStatus(decoded.status()));
        }
        BlockEffectPayload payload = decoded.payload();
        String presentation = blockPresentation(
                action,
                payload,
                technicalSubject);
        if (presentation == null) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        // Explosion split (design-02 1.4, Advisor point A-2 and answer 3): the evidence version is the version the
        // payload was encoded with (2 for a tagged row), so a tagged row survives AuditRecord's re-validation.
        return new DecodedProjection(
                new AuditEvidence.Block(BlockEffectPayloadCodec.versionOf(payload), encoded),
                payload.subjectKind() == BlockEffectPayload.SubjectKind.OPERATION
                        ? AuditSubject.Kind.OPERATION
                        : AuditSubject.Kind.BLOCK,
                blockTraits(payload),
                presentation);
    }

    private static DecodedProjection decodeItem(
            AuditAction action,
            String technicalSubject,
            String encoded) {
        ItemAuditPayloadCodec.DecodeResult decoded = ItemAuditPayloadCodec.decode(encoded);
        if (!decoded.successful()) {
            return unavailable(action, itemStatus(decoded.status()));
        }
        ItemAuditPayload payload = decoded.payload();
        ItemAuditPayload.Outcome required = action == AuditAction.ITEM_DROP
                ? ItemAuditPayload.Outcome.DROP
                : ItemAuditPayload.Outcome.PICKUP;
        if (payload.outcome() != required
                || !payload.itemId().toString().equals(technicalSubject)) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        return new DecodedProjection(
                new AuditEvidence.Item(
                        ItemAuditPayloadCodec.CURRENT_VERSION,
                        required == ItemAuditPayload.Outcome.DROP
                                ? AuditEvidence.ItemOutcome.DROP
                                : AuditEvidence.ItemOutcome.PICKUP,
                        encoded),
                AuditSubject.Kind.ITEM,
                playerTraits(payload.actorState() == ItemAuditPayload.ActorState.SYNTHETIC_PLAYER),
                payload.presentationIdentity());
    }

    private static DecodedProjection decodeEntity(
            AuditAction action,
            String technicalSubject,
            String encoded) {
        EntityEventPayloadCodec.AuditProjectionResult decoded =
                EntityEventPayloadCodec.decodeAuditProjection(technicalSubject, encoded);
        if (!decoded.successful()) {
            return unavailable(action, entityStatus(decoded.status()));
        }
        EntityEventPayloadCodec.AuditProjection payload = decoded.projection();
        EntityEventPayloadCodec.RowKind required = action == AuditAction.ENTITY_KILL
                ? EntityEventPayloadCodec.RowKind.KILL
                : EntityEventPayloadCodec.RowKind.DEATH;
        if (payload.rowKind() != required
                || !payload.technicalVictimIdentity().equals(technicalSubject)) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        return new DecodedProjection(
                new AuditEvidence.Entity(
                        EntityEventPayloadCodec.CURRENT_VERSION,
                        required == EntityEventPayloadCodec.RowKind.KILL
                                ? AuditEvidence.EntityOutcome.KILL
                                : AuditEvidence.EntityOutcome.DEATH,
                        encoded),
                AuditSubject.Kind.ENTITY,
                new AttributionTraits(
                        identity(payload.indexedIdentity()),
                        cause(payload.cause()),
                        causality(payload.causality()),
                        payload.causeDetail(),
                        new IndexedPartyBinding(
                                payload.indexedPlayerId(),
                                payload.indexedCanonicalName())),
                payload.subjectPresentationIdentity());
    }

    private static DecodedProjection decodeAccess(
            AuditAction action,
            String technicalSubject,
            String encoded) {
        ContainerAccessPayloadCodec.AuditProjectionResult decoded =
                ContainerAccessPayloadCodec.decodeAuditProjection(encoded);
        if (!decoded.successful()) {
            return unavailable(action, accessStatus(decoded.status()));
        }
        ContainerAccessPayloadCodec.AuditProjection payload = decoded.projection();
        if (!payload.technicalTargetIdentity().equals(technicalSubject)) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        return new DecodedProjection(
                new AuditEvidence.ContainerAccess(
                        ContainerAccessPayloadCodec.CURRENT_VERSION,
                        payload.phase() == ContainerAccessPhase.OPEN
                                ? AuditEvidence.AccessPhase.OPEN
                                : AuditEvidence.AccessPhase.CLOSE,
                        encoded),
                AuditSubject.Kind.CONTAINER,
                playerTraits(payload.syntheticActor()),
                payload.subjectPresentationIdentity(),
                new AuditLocation(
                        ResourceLocation.parse(payload.dimensionId()),
                        new net.minecraft.core.BlockPos(
                                payload.x(), payload.y(), payload.z())),
                payload.phase() == ContainerAccessPhase.OPEN
                        ? OccurrenceConstraint.exact(payload.openedAt())
                        : OccurrenceConstraint.notBefore(payload.openedAt()));
    }

    private static DecodedProjection decodeTransfer(
            AuditAction action,
            String technicalSubject,
            String encoded) {
        ContainerTransferPayloadCodec.DecodeResult decoded =
                ContainerTransferPayloadCodec.decode(encoded);
        if (!decoded.successful()) {
            return unavailable(action, transferStatus(decoded.status()));
        }
        ContainerTransferPayload payload = decoded.payload();
        AuditEvidence.TransferDirection direction = payload.direction() == TransactionDirection.DEPOSIT
                ? AuditEvidence.TransferDirection.DEPOSIT : AuditEvidence.TransferDirection.WITHDRAWAL;
        if (direction != action.transferDirection()) {
            throw new IllegalArgumentException("Container action conflicts with its typed payload direction");
        }
        if (!payload.backingReference().equals(technicalSubject)) {
            return unavailable(action, AuditEvidence.Status.MALFORMED);
        }
        return new DecodedProjection(
                new AuditEvidence.ContainerTransfer(
                        ContainerTransferPayloadCodec.CURRENT_VERSION,
                        payload.direction() == TransactionDirection.DEPOSIT
                                ? AuditEvidence.TransferDirection.DEPOSIT
                                : AuditEvidence.TransferDirection.WITHDRAWAL,
                        payload.itemId(),
                        payload.quantity(),
                        encoded),
                AuditSubject.Kind.CONTAINER,
                playerTraits(payload.actorState()
                        == ContainerTransferPayload.ActorState.SYNTHETIC_PLAYER),
                payload.targetDisplay());
    }

    private static AttributionTraits blockTraits(BlockEffectPayload payload) {
        AuditAttribution.IdentityKind identity = switch (payload.actorKind()) {
            case PLAYER -> AuditAttribution.IdentityKind.PLAYER;
            case SYNTHETIC_PLAYER -> AuditAttribution.IdentityKind.SYNTHETIC_PLAYER;
            case ENVIRONMENT -> AuditAttribution.IdentityKind.ENVIRONMENT;
        };
        AuditAttribution.CauseKind cause = switch (payload.actorKind()) {
            case PLAYER -> AuditAttribution.CauseKind.PLAYER;
            case SYNTHETIC_PLAYER -> AuditAttribution.CauseKind.SYNTHETIC_PLAYER;
            case ENVIRONMENT -> AuditAttribution.CauseKind.ENVIRONMENT;
        };
        AuditAttribution.Causality causality = switch (payload.causality()) {
            case DIRECT -> AuditAttribution.Causality.DIRECT;
            case IMMEDIATE_SECONDARY -> AuditAttribution.Causality.IMMEDIATE_SECONDARY;
            case UNATTRIBUTED -> AuditAttribution.Causality.UNATTRIBUTED;
        };
        return new AttributionTraits(identity, cause, causality, payload.causeDetail());
    }

    private static AttributionTraits playerTraits(boolean synthetic) {
        return new AttributionTraits(
                synthetic
                        ? AuditAttribution.IdentityKind.SYNTHETIC_PLAYER
                        : AuditAttribution.IdentityKind.PLAYER,
                synthetic
                        ? AuditAttribution.CauseKind.SYNTHETIC_PLAYER
                        : AuditAttribution.CauseKind.PLAYER,
                AuditAttribution.Causality.DIRECT,
                null);
    }

    private static DecodedProjection unavailable(
            AuditAction action,
            AuditEvidence.Status status) {
        return new DecodedProjection(
                new AuditEvidence.Unavailable(action.evidenceKind(), status),
                unavailableSubjectKind(action),
                new AttributionTraits(
                        AuditAttribution.IdentityKind.UNCLASSIFIED_PLAYER,
                        AuditAttribution.CauseKind.UNATTRIBUTED,
                        AuditAttribution.Causality.UNATTRIBUTED,
                        null),
                "Stored " + action.commandToken() + " evidence unavailable");
    }

    private static AuditSubject.Kind unavailableSubjectKind(AuditAction action) {
        return switch (action.evidenceKind()) {
            case BLOCK -> AuditSubject.Kind.BLOCK;
            case ITEM -> AuditSubject.Kind.ITEM;
            case ENTITY -> AuditSubject.Kind.ENTITY;
            case CONTAINER_ACCESS, CONTAINER_TRANSFER -> AuditSubject.Kind.CONTAINER;
        };
    }

    private static String blockPresentation(
            AuditAction action,
            BlockEffectPayload payload,
            String technicalSubject) {
        BlockEffectPayload.SubjectKind subjectKind = payload.subjectKind();
        String presentationIdentity = payload.presentationIdentity();
        if (!technicalSubject.equals(payload.technicalIdentity())) return null;
        ExplosionPartTag tag = null;
        if (payload.explosionTag() != null) {
            // Only an explosion's rows carry a tag; the payload record already checked its form against the kind.
            if (action != AuditAction.EXPLOSION) return null;
            tag = payload.explosionPartTag().orElse(null);
            if (tag == null) return null;
        }
        if (subjectKind == BlockEffectPayload.SubjectKind.OPERATION) {
            if (action != AuditAction.EXPLOSION) return null;
            if (tag != null) {
                // A part row: exactly the text regenerated from its tag and reason code.
                return tag.form() == ExplosionPartTag.Form.PART
                        && ExplosionPartText.TECHNICAL_IDENTITY.equals(technicalSubject)
                        && ExplosionPartText.isPresentation(presentationIdentity, tag, payload.truncation())
                        ? presentationIdentity
                        : null;
            }
            // The n6-3 truncation row (no longer written by this build), still read from existing data.
            return ExplosionTruncationText.TECHNICAL_IDENTITY.equals(technicalSubject)
                    && ExplosionTruncationText.isPresentation(presentationIdentity)
                    ? presentationIdentity
                    : null;
        }
        if (tag != null && (tag.form() != ExplosionPartTag.Form.BLOCK
                || payload.truncation() != BlockEffectTruncation.NONE)) {
            return null;
        }
        if (!CanonicalBlockStateSnapshot.isCanonicalStorageText(technicalSubject)) return null;
        return presentationIdentity;
    }

    private static AuditAttribution.IdentityKind identity(
            EntityEventPayloadCodec.ProjectionIdentity value) {
        return switch (value) {
            case PLAYER -> AuditAttribution.IdentityKind.PLAYER;
            case SYNTHETIC_PLAYER -> AuditAttribution.IdentityKind.SYNTHETIC_PLAYER;
        };
    }

    private static AuditAttribution.CauseKind cause(
            EntityEventPayloadCodec.ProjectionCause value) {
        return switch (value) {
            case PLAYER -> AuditAttribution.CauseKind.PLAYER;
            case SYNTHETIC_PLAYER -> AuditAttribution.CauseKind.SYNTHETIC_PLAYER;
            case ENVIRONMENT -> AuditAttribution.CauseKind.ENVIRONMENT;
            case UNATTRIBUTED -> AuditAttribution.CauseKind.UNATTRIBUTED;
        };
    }

    private static AuditAttribution.Causality causality(
            EntityEventPayloadCodec.ProjectionCausality value) {
        return switch (value) {
            case DIRECT -> AuditAttribution.Causality.DIRECT;
            case IMMEDIATE_SECONDARY -> AuditAttribution.Causality.IMMEDIATE_SECONDARY;
            case UNATTRIBUTED -> AuditAttribution.Causality.UNATTRIBUTED;
        };
    }

    private static AuditEvidence.Status blockStatus(BlockEffectPayloadCodec.DecodeStatus status) {
        return switch (status) {
            case TOO_LARGE -> AuditEvidence.Status.TOO_LARGE;
            case MALFORMED -> AuditEvidence.Status.MALFORMED;
            case UNSUPPORTED, OK -> AuditEvidence.Status.UNSUPPORTED;
        };
    }

    private static AuditEvidence.Status itemStatus(ItemAuditPayloadCodec.Status status) {
        return switch (status) {
            case TOO_LARGE -> AuditEvidence.Status.TOO_LARGE;
            case MALFORMED -> AuditEvidence.Status.MALFORMED;
            case UNSUPPORTED, OK -> AuditEvidence.Status.UNSUPPORTED;
        };
    }

    private static AuditEvidence.Status entityStatus(EntityEventPayloadCodec.DecodeStatus status) {
        return switch (status) {
            case TOO_LARGE -> AuditEvidence.Status.TOO_LARGE;
            case MALFORMED -> AuditEvidence.Status.MALFORMED;
            case UNSUPPORTED, OK -> AuditEvidence.Status.UNSUPPORTED;
        };
    }

    private static AuditEvidence.Status accessStatus(
            ContainerAccessPayloadCodec.DecodeStatus status) {
        return switch (status) {
            case TOO_LARGE -> AuditEvidence.Status.TOO_LARGE;
            case MALFORMED -> AuditEvidence.Status.MALFORMED;
            case UNSUPPORTED, OK -> AuditEvidence.Status.UNSUPPORTED;
        };
    }

    private static AuditEvidence.Status transferStatus(
            ContainerTransferPayloadCodec.Status status) {
        return switch (status) {
            case TOO_LARGE -> AuditEvidence.Status.TOO_LARGE;
            case MALFORMED -> AuditEvidence.Status.MALFORMED;
            case UNSUPPORTED, OK -> AuditEvidence.Status.UNSUPPORTED;
        };
    }
}
