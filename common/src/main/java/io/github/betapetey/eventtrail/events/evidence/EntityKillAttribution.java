package io.github.betapetey.eventtrail.events.evidence;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit player-credit provenance for a committed death. Ownership context
 * by itself is represented by a non-accountable kind and never becomes a row
 * actor.
 *
 * <p>Kill credit A (design-05c 4.1): {@code related} is the killer's recorded owner, shown only inside the killer's
 * label. It exists only on an unaccountable kind other than RETALIATION, so an owner can never become a row actor or a
 * second killer (One Killer). {@code retaliation} is the hit-back enchantment a RETALIATION death matched (signal 1),
 * and exists only on RETALIATION. Neither ever holds the Thorns wearer.</p>
 */
public record EntityKillAttribution(
        ProvenanceKind provenance,
        ActorProvenance accountablePlayer,
        RelatedParty related,
        RetaliationDetail retaliation) {

    public EntityKillAttribution {
        provenance = Objects.requireNonNull(provenance, "provenance");
        if (provenance.accountable() != (accountablePlayer != null)) {
            throw new IllegalArgumentException(
                    "Accountable provenance must contain exactly one player actor");
        }
        if (accountablePlayer != null && accountablePlayer.playerId() == null) {
            throw new IllegalArgumentException("Kill attribution requires a player identity");
        }
        if (related != null
                && (provenance.accountable() || provenance == ProvenanceKind.RETALIATION)) {
            throw new IllegalArgumentException(
                    "A related party is only allowed on an unaccountable, non-retaliation kind");
        }
        if (retaliation != null && provenance != ProvenanceKind.RETALIATION) {
            throw new IllegalArgumentException("Retaliation detail is only allowed on RETALIATION");
        }
    }

    public static EntityKillAttribution accountable(
            ProvenanceKind provenance,
            ActorProvenance actor) {
        return new EntityKillAttribution(provenance, Objects.requireNonNull(actor, "actor"), null, null);
    }

    public static EntityKillAttribution unattributed(ProvenanceKind provenance) {
        return new EntityKillAttribution(provenance, null, null, null);
    }

    /** A death a retaliation signal matched; {@code detail} is null unless the VICTIM frame (signal 1) matched. */
    public static EntityKillAttribution retaliation(RetaliationDetail detail) {
        return new EntityKillAttribution(ProvenanceKind.RETALIATION, null, null, detail);
    }

    /** This attribution with the killer's recorded owner; throws on an accountable or RETALIATION kind. */
    public EntityKillAttribution withRelated(RelatedParty party) {
        return new EntityKillAttribution(
                provenance, accountablePlayer, Objects.requireNonNull(party, "party"), retaliation);
    }

    /**
     * The killing entity of a death (design-05c 4.1, 4.2; lead ruling 2 of fold 01): the causing entity when the direct
     * entity is a projectile, otherwise the direct entity. The one definition for both the owner attach (live entities,
     * {@code direct instanceof Projectile}) and the death-row label (stored identities, whose kind is
     * {@code projectile} exactly when the live direct entity was a Projectile), so the owner shown is always the owner
     * of the entity it is printed next to.
     */
    public static <T> T killingEntity(T direct, T causing, boolean directIsProjectile) {
        return directIsProjectile ? causing : direct;
    }

    public EntityKillAttribution redactExcludedSyntheticPlayer() {
        if (accountablePlayer == null
                || accountablePlayer.actorKind() != ActorProvenance.ActorKind.SYNTHETIC_PLAYER) {
            return this;
        }
        return unattributed(ProvenanceKind.EXCLUDED_SYNTHETIC_PLAYER);
    }

    public enum ProvenanceKind {
        DIRECT_PLAYER(true),
        PLAYER_PROJECTILE(true),
        TNT_IGNITER(true),
        ENGINE_KILL_CREDIT(true),
        EXCLUDED_SYNTHETIC_PLAYER(false),
        PET_OR_MINION(false),
        DEVICE_OR_AUTOMATION(false),
        NON_PLAYER_ENTITY(false),
        ENVIRONMENT(false),
        UNSUPPORTED_SECONDARY(false),
        /** Thorns or other hit-back: the wearer did not act, so nobody is credited (P2-K8). */
        RETALIATION(false);

        private final boolean accountable;

        ProvenanceKind(boolean accountable) {
            this.accountable = accountable;
        }

        public boolean accountable() {
            return accountable;
        }
    }

    /**
     * The killer's recorded owner (4.1): a resolved player, a synthetic player (stored with its own UUID and name: no
     * "Automated player" redaction for owners, design-05c 4.2 and lead ruling 3 of fold 01), a non-player entity (with
     * its type id), or an owner UUID the server cannot name now (UNRESOLVED, no name). It has no role: its one meaning
     * is "the killer's recorded owner".
     */
    public record RelatedParty(Kind kind, UUID uuid, String typeId, String name) {
        public RelatedParty {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(uuid, "uuid");
            if ((kind == Kind.ENTITY) != (typeId != null && !typeId.isBlank())) {
                throw new IllegalArgumentException("Only an ENTITY owner carries a type id, and it must carry one");
            }
            if (kind == Kind.UNRESOLVED && name != null) {
                throw new IllegalArgumentException("An UNRESOLVED owner carries no name");
            }
            if (name != null && name.isBlank()) name = null;
        }

        public enum Kind { PLAYER, SYNTHETIC_PLAYER, ENTITY, UNRESOLVED }
    }

    /** Signal 1's enchantment: its registry id and the description text captured at death time. */
    public record RetaliationDetail(String effectId, String name) {
        public RetaliationDetail {
            if (effectId != null && effectId.isBlank()) effectId = null;
            if (name != null && name.isBlank()) name = null;
            if (effectId == null && name == null) {
                throw new IllegalArgumentException("Retaliation detail needs an enchantment id or name");
            }
        }
    }
}
