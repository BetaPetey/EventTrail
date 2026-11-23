package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.events.block.BlockEffectPayload;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;

import java.util.Objects;

/** Builds structural block payload facts from platform-captured provenance. */
final class BlockEffectPayloadAdapter {
    private BlockEffectPayloadAdapter() {}

    static BlockEffectPayload from(
            ActorProvenance actor,
            String technicalIdentity,
            String presentationIdentity,
            BlockEntityEvidence blockEntityEvidence,
            BlockEffectTruncation truncation) {
        return from(actor, technicalIdentity, presentationIdentity,
                blockEntityEvidence, truncation,
                BlockEffectPayload.SubjectKind.BLOCK);
    }

    static BlockEffectPayload from(
            ActorProvenance actor,
            String technicalIdentity,
            String presentationIdentity,
            BlockEntityEvidence blockEntityEvidence,
            BlockEffectTruncation truncation,
            BlockEffectPayload.SubjectKind subjectKind) {
        Objects.requireNonNull(actor, "actor");
        return new BlockEffectPayload(
                Objects.requireNonNull(subjectKind, "subjectKind"),
                Objects.requireNonNull(technicalIdentity, "technicalIdentity"),
                Objects.requireNonNull(presentationIdentity, "presentationIdentity"),
                switch (actor.actorKind()) {
                    case PLAYER -> BlockEffectPayload.ActorKind.PLAYER;
                    case SYNTHETIC_PLAYER -> BlockEffectPayload.ActorKind.SYNTHETIC_PLAYER;
                    case ENVIRONMENT -> BlockEffectPayload.ActorKind.ENVIRONMENT;
                },
                switch (actor.causality()) {
                    case DIRECT -> BlockEffectPayload.Causality.DIRECT;
                    case IMMEDIATE_SECONDARY -> BlockEffectPayload.Causality.IMMEDIATE_SECONDARY;
                    case UNATTRIBUTED -> BlockEffectPayload.Causality.UNATTRIBUTED;
                },
                actor.detail(),
                blockEntityEvidence,
                truncation);
    }
}
