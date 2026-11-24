package io.github.betapetey.eventtrail.events.evidence;

import java.util.Objects;

/**
 * Loader-neutral causality policy for one immutable damage-source snapshot.
 * Platform adapters identify concrete entity kinds and may supply only the
 * engine credit they have already proven to be the exact last attacking
 * player; this class owns the portable decision about whether that evidence
 * can become a player-side kill fact.
 */
public final class EntityKillAttributionClassifier {
    private EntityKillAttributionClassifier() {
    }

    public static EntityKillAttribution classify(SourceFacts facts) {
        Objects.requireNonNull(facts, "facts");

        if (facts.directKind() == SourceKind.PET_OR_MINION
                || facts.causingKind() == SourceKind.PET_OR_MINION) {
            return EntityKillAttribution.unattributed(
                    EntityKillAttribution.ProvenanceKind.PET_OR_MINION);
        }

        if (facts.directKind() == SourceKind.UNAVAILABLE
                || facts.causingKind() == SourceKind.UNAVAILABLE) {
            return EntityKillAttribution.unattributed(
                    EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY);
        }

        ActorProvenance causingPlayer = facts.causingPlayer();
        if (causingPlayer != null) {
            if (facts.sameDirectAndCausingEntity()
                    && facts.directKind() == SourceKind.PLAYER
                    && facts.causingKind() == SourceKind.PLAYER) {
                return EntityKillAttribution.accountable(
                        EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER,
                        causingPlayer);
            }
            if (facts.directKind() == SourceKind.PROJECTILE) {
                return EntityKillAttribution.accountable(
                        EntityKillAttribution.ProvenanceKind.PLAYER_PROJECTILE,
                        causingPlayer);
            }
            if (facts.directKind() == SourceKind.PRIMED_TNT) {
                return EntityKillAttribution.accountable(
                        EntityKillAttribution.ProvenanceKind.TNT_IGNITER,
                        causingPlayer);
            }
            return EntityKillAttribution.unattributed(
                    facts.directKind() == SourceKind.NONE
                            ? EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY
                            : EntityKillAttribution.ProvenanceKind.DEVICE_OR_AUTOMATION);
        }

        // Engine kill credit is useful only when the current damage source is
        // genuinely entityless. If a device, projectile, pet, or other entity
        // delivered the final damage, a prior player hit must not be promoted
        // into ownership of that later physical action.
        // Unreachable since final-blow-only (Owner 11-23 11:56 AM): the NeoForge bridge supplies no engine credit.
        if (facts.exactEngineKillCredit() != null
                && facts.directKind() == SourceKind.NONE
                && facts.causingKind() == SourceKind.NONE) {
            return EntityKillAttribution.accountable(
                    EntityKillAttribution.ProvenanceKind.ENGINE_KILL_CREDIT,
                    facts.exactEngineKillCredit());
        }

        if (facts.directKind() != SourceKind.NONE || facts.causingKind() != SourceKind.NONE) {
            return EntityKillAttribution.unattributed(
                    EntityKillAttribution.ProvenanceKind.NON_PLAYER_ENTITY);
        }
        return EntityKillAttribution.unattributed(
                EntityKillAttribution.ProvenanceKind.ENVIRONMENT);
    }

    public enum SourceKind {
        NONE,
        UNAVAILABLE,
        PLAYER,
        PROJECTILE,
        PRIMED_TNT,
        PET_OR_MINION,
        OTHER_ENTITY
    }

    public record SourceFacts(
            SourceKind directKind,
            SourceKind causingKind,
            boolean sameDirectAndCausingEntity,
            ActorProvenance causingPlayer,
            ActorProvenance exactEngineKillCredit) {

        public SourceFacts {
            directKind = Objects.requireNonNull(directKind, "directKind");
            causingKind = Objects.requireNonNull(causingKind, "causingKind");
            if (sameDirectAndCausingEntity
                    && (directKind != causingKind
                    || directKind == SourceKind.NONE
                    || directKind == SourceKind.UNAVAILABLE)) {
                throw new IllegalArgumentException(
                        "The same direct/causing entity requires one available matching source kind");
            }
            if ((causingPlayer != null) != (causingKind == SourceKind.PLAYER)) {
                throw new IllegalArgumentException(
                        "A causing-player identity must exactly match a PLAYER source kind");
            }
            if (causingPlayer != null) {
                ActorProvenance.Causality expected = sameDirectAndCausingEntity
                        ? ActorProvenance.Causality.DIRECT
                        : ActorProvenance.Causality.IMMEDIATE_SECONDARY;
                if (causingPlayer.causality() != expected) {
                    throw new IllegalArgumentException(
                            "Causing-player causality does not match the direct/secondary source facts");
                }
            }
            if (exactEngineKillCredit != null
                    && exactEngineKillCredit.causality()
                    != ActorProvenance.Causality.IMMEDIATE_SECONDARY) {
                throw new IllegalArgumentException(
                        "Engine kill credit must remain explicitly secondary attribution");
            }
        }
    }
}
