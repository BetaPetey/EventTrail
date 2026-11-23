package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.ExplosionActorProvenance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;

import java.util.Objects;
import java.util.Optional;

/** NeoForge live-object boundary for exact human explosion attribution. */
public final class NeoForgeExplosionActorProvenance {
    private NeoForgeExplosionActorProvenance() {
    }

    public static Optional<ActorProvenance> resolve(Explosion explosion) {
        Objects.requireNonNull(explosion, "explosion");
        Entity direct = explosion.getDirectSourceEntity();
        LivingEntity indirect = explosion.getIndirectSourceEntity();
        // Exclusion precedes exact-action attribution and environment fallback.
        // A recognized synthetic source must never become a neutral explosion.
        if (excludedPlayerSources(direct, indirect)) return Optional.empty();
        if (io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime.denyExplosion(explosion)) {
            return Optional.of(neutralSource(direct));
        }
        if (io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime.denyExplosion(explosion)) {
            return Optional.of(neutralSource(direct));
        }
        if (direct instanceof Player player) {
            return playerActor(
                    player,
                    ActorProvenance.Causality.DIRECT,
                    "Player explosion");
        }

        if (indirect instanceof Player player
                && exactPlayerAction(direct, player)) {
            String sourceName = direct instanceof PrimedTnt
                    ? "TNT"
                    : direct instanceof Projectile
                    ? "projectile"
                    : "explosion";
            return playerActor(
                    player,
                    ActorProvenance.Causality.IMMEDIATE_SECONDARY,
                    sourceName + " caused by player");
        }

        return Optional.of(neutralSource(direct));
    }

    /**
     * GF-FAKEPLAYER-ID (B51): a direct player source is the acting player, so it must also be the PlayerList's own
     * object for its UUID. The indirect source is a stored owner (a TNT's igniter, a projectile's shooter) that is
     * credited only with packet-scoped proof of its own action, and may have left the list since; it stays type-only.
     */
    static boolean excludedPlayerSources(Entity direct, LivingEntity indirect) {
        return (direct instanceof Player directPlayer
                && !NeoForgePlayerProvenance.isAdmittedPlayer(directPlayer))
                || (indirect instanceof Player indirectPlayer
                && NeoForgePlayerProvenance.isSynthetic(indirectPlayer));
    }

    private static boolean exactPlayerAction(Entity direct, Player player) {
        if (direct instanceof PrimedTnt tnt) {
            return tnt.getType() == EntityType.TNT
                    && NeoForgeTntActionAuditBridge.wasIgnitedByPlayerAction(
                            tnt, player.getUUID());
        }
        if (direct instanceof Projectile projectile) {
            return NeoForgeProjectileActionAuditBridge.wasLaunchedByPlayerAction(
                    projectile, player.getUUID());
        }
        return false;
    }

    private static Optional<ActorProvenance> playerActor(
            Player player,
            ActorProvenance.Causality causality,
            String trackedDetail) {
        return ExplosionActorProvenance.playerIdentity(
                player.getUUID(),
                player.getName().getString(),
                NeoForgePlayerProvenance.isSynthetic(player),
                causality,
                trackedDetail);
    }

    private static ActorProvenance neutralSource(Entity direct) {
        if (direct == null) {
            return ActorProvenance.environment("Environment", "Explosion");
        }
        try {
            String sourceName = direct.getType().getDescription().getString();
            return ActorProvenance.environment(sourceName, "Caused by: " + sourceName);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return ActorProvenance.environment(
                    "Explosion source", "Caused by an unidentified explosion source");
        }
    }
}
