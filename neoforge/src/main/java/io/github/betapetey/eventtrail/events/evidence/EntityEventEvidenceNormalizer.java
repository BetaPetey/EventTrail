package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.util.EntityEventPayloadCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maps one committed physical death into its independently controlled audit facts. */
public final class EntityEventEvidenceNormalizer {
    private EntityEventEvidenceNormalizer() {
    }

    public static List<AuditRecord> toAuditRecords(
            CommittedEntityDeathEvidence evidence,
            boolean trackPlayerDeaths,
            boolean trackEntityKills,
            boolean captureFullWeaponMetadata) {
        Objects.requireNonNull(evidence, "evidence");
        List<AuditRecord> rows = new ArrayList<>(2);

        EntityKillAttribution storedAttribution = evidence.attribution();
        // A retained real-victim death must also redact an excluded synthetic
        // device/projectile owner that was never eligible for killer credit.
        EntityDamageEvidence storedDamage = evidence.damage().redactExcludedSyntheticPlayers();
        ActorProvenance killer = storedAttribution.accountablePlayer();
        boolean excludedSyntheticKiller = killer != null
                && killer.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER;
        if (excludedSyntheticKiller) {
            storedAttribution = storedAttribution.redactExcludedSyntheticPlayer();
            killer = null;
        }

        EntityIdentity victim = evidence.victim();
        boolean victimIsPlayer = victim.kind() == EntityIdentity.EntityKind.PLAYER;
        boolean victimSynthetic = victim.kind() == EntityIdentity.EntityKind.SYNTHETIC_PLAYER;
        CommittedEntityDeathEvidence storedEvidence = victimSynthetic
                ? withVictim(evidence, victim.redactSyntheticPlayer())
                : evidence;
        if (trackPlayerDeaths && victimIsPlayer && evidence.victimPlayerTracked()) {
            rows.add(row(
                    storedEvidence,
                    AuditAction.ENTITY_DEATH,
                    victimAttribution(victim, storedAttribution),
                    EntityEventPayloadCodec.RowKind.DEATH,
                    storedDamage,
                    storedAttribution,
                    victimSynthetic,
                    captureFullWeaponMetadata));
        }

        if (trackEntityKills && killer != null
                && !killer.playerId().equals(victim.entityId())) {
            rows.add(row(
                    storedEvidence,
                    AuditAction.ENTITY_KILL,
                    entityActorAttribution(killer),
                    EntityEventPayloadCodec.RowKind.KILL,
                    storedDamage,
                    storedAttribution,
                    killer.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER,
                    captureFullWeaponMetadata));
        }
        return List.copyOf(rows);
    }

    /** A synthetic victim's redacted form; it writes no death row, so it keeps no last-hit note. */
    private static CommittedEntityDeathEvidence withVictim(
            CommittedEntityDeathEvidence evidence,
            EntityIdentity victim) {
        return new CommittedEntityDeathEvidence(
                evidence.timestamp(),
                evidence.operationId(),
                victim,
                evidence.dimensionId(),
                evidence.position(),
                evidence.damage(),
                evidence.attribution(),
                false,
                evidence.proofKind());
    }

    private static AuditRecord row(
            CommittedEntityDeathEvidence evidence,
            AuditAction action,
            AuditAttribution auditAttribution,
            EntityEventPayloadCodec.RowKind rowKind,
            EntityDamageEvidence storedDamage,
            EntityKillAttribution storedAttribution,
            boolean rowActorSynthetic,
            boolean captureFullWeaponMetadata) {
        String presentation = rowName(evidence.victim());
        String encoded = EntityEventPayloadCodec.encode(
                evidence,
                rowKind,
                storedDamage,
                storedAttribution,
                rowActorSynthetic,
                captureFullWeaponMetadata);
        return AuditRecord.captureAt(
                evidence.timestamp(),
                action,
                auditAttribution,
                new AuditLocation(evidence.dimensionId(), evidence.position()),
                new AuditSubject(
                        AuditSubject.Kind.ENTITY,
                        evidence.victim().typeId().toString(),
                        presentation),
                new AuditEvidence.Entity(
                        EntityEventPayloadCodec.CURRENT_VERSION,
                        action == AuditAction.ENTITY_KILL
                                ? AuditEvidence.EntityOutcome.KILL
                                : AuditEvidence.EntityOutcome.DEATH,
                        encoded));
    }

    private static AuditAttribution victimAttribution(
            EntityIdentity victim,
            EntityKillAttribution cause) {
        AuditAttribution.IdentityKind victimKind = switch (victim.kind()) {
            case PLAYER -> AuditAttribution.IdentityKind.PLAYER;
            case SYNTHETIC_PLAYER -> AuditAttribution.IdentityKind.SYNTHETIC_PLAYER;
            case REDACTED_SYNTHETIC_PLAYER -> AuditAttribution.IdentityKind.UNCLASSIFIED_PLAYER;
            default -> throw new IllegalArgumentException("Entity-death rows require a player victim");
        };
        ActorProvenance accountable = cause.accountablePlayer();
        AuditAttribution.CauseKind causeKind;
        AuditAttribution.Causality causality;
        String detail;
        if (accountable != null) {
            causeKind = accountable.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER
                    ? AuditAttribution.CauseKind.SYNTHETIC_PLAYER
                    : AuditAttribution.CauseKind.PLAYER;
            causality = switch (accountable.causality()) {
                case DIRECT -> AuditAttribution.Causality.DIRECT;
                case IMMEDIATE_SECONDARY -> AuditAttribution.Causality.IMMEDIATE_SECONDARY;
                case UNATTRIBUTED -> throw new IllegalArgumentException(
                        "Accountable killer cannot be unattributed");
            };
            detail = null;
        } else if (cause.provenance() == EntityKillAttribution.ProvenanceKind.EXCLUDED_SYNTHETIC_PLAYER) {
            causeKind = AuditAttribution.CauseKind.SYNTHETIC_PLAYER;
            causality = AuditAttribution.Causality.IMMEDIATE_SECONDARY;
            detail = "Automated source";
        } else if (cause.provenance() == EntityKillAttribution.ProvenanceKind.ENVIRONMENT) {
            causeKind = AuditAttribution.CauseKind.ENVIRONMENT;
            causality = AuditAttribution.Causality.UNATTRIBUTED;
            detail = "Environment";
        } else {
            causeKind = AuditAttribution.CauseKind.UNATTRIBUTED;
            causality = AuditAttribution.Causality.UNATTRIBUTED;
            detail = cause.provenance().name().toLowerCase(java.util.Locale.ROOT);
        }
        return new AuditAttribution(
                new AuditAttribution.IndexedParty(
                        AuditAttribution.IndexedRole.VICTIM,
                        victimKind,
                        victim.entityId(),
                        AuditAttribution.indexedName(rowName(victim))),
                causeKind,
                causality,
                detail);
    }

    private static AuditAttribution entityActorAttribution(ActorProvenance actor) {
        boolean synthetic = actor.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER;
        AuditAttribution.Causality causality = switch (actor.causality()) {
            case DIRECT -> AuditAttribution.Causality.DIRECT;
            case IMMEDIATE_SECONDARY -> AuditAttribution.Causality.IMMEDIATE_SECONDARY;
            case UNATTRIBUTED -> throw new IllegalArgumentException(
                    "An accountable entity killer cannot be unattributed");
        };
        return AuditAttribution.actor(
                actor.playerId(), actor.displayName(), synthetic, causality, null);
    }

    private static String rowName(EntityIdentity identity) {
        if (identity.canonicalName() != null) return identity.canonicalName();
        if (identity.displayName() != null) return identity.displayName();
        if (identity.translatedTypeName() != null) return identity.translatedTypeName();
        return identity.typeId().toString();
    }
}
