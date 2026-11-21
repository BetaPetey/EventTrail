package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;

import java.util.Objects;

/** Maps immutable access evidence into the grouped audit model. */
public final class ContainerAccessNormalizer {
    private ContainerAccessNormalizer() {}

    public static AuditRecord toAuditRecord(ContainerAccessEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        ContainerAccessTargetSnapshot target = evidence.target();
        return AuditRecord.captureAt(
                evidence.timestamp(),
                AuditAction.CONTAINER_ACCESS,
                AuditAttribution.actor(
                        evidence.actor().playerId(),
                        evidence.actor().playerName(),
                        evidence.actor().synthetic(),
                        AuditAttribution.Causality.DIRECT,
                        null),
                new AuditLocation(
                        net.minecraft.resources.ResourceLocation.parse(target.dimensionId()),
                        target.position()),
                new AuditSubject(
                        AuditSubject.Kind.CONTAINER,
                        target.technicalTargetId(),
                        // Exactly the read-back of the stored text: the row check compares the two.
                        EventTrailTextBounds.containerTargetDisplayName(target.displayLabel())),
                new AuditEvidence.ContainerAccess(
                        ContainerAccessPayloadCodec.CURRENT_VERSION,
                        evidence.phase() == ContainerAccessPhase.OPEN
                                ? AuditEvidence.AccessPhase.OPEN
                                : AuditEvidence.AccessPhase.CLOSE,
                        ContainerAccessPayloadCodec.encode(evidence)));
    }
}
