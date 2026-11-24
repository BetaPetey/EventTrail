package io.github.betapetey.eventtrail.events.spells;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** No optional API linkage. Exact mod producers enrich the existing death path, never write rows. */
public final class IronsSpellAuditRuntime {
    private static final String NAMESPACE = "irons_spellbooks";
    private static final String PACKAGE = "io.redspace.ironsspellbooks.";
    private static final ThreadLocal<DamageFrame> DAMAGE = new ThreadLocal<>();
    private static volatile Lifecycle lifecycle;

    private IronsSpellAuditRuntime() { }

    /** Recognition grants no credit, including for unknown versions or unavailable adapters. */
    public static boolean isIronSource(DamageSource source) {
        if (source == null) return false;
        if (ironType(source.getClass())) return true;
        if (source.typeHolder().unwrapKey().map(k -> NAMESPACE.equals(k.location().getNamespace())).orElse(false)) return true;
        return isIronEntity(source.getDirectEntity()) || isIronEntity(source.getEntity());
    }

    public static boolean isIronEntity(Entity entity) {
        if (entity == null) return false;
        if (ironType(entity.getClass())) return true;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && NAMESPACE.equals(id.getNamespace());
    }

    static boolean ironType(Class<?> type) {
        for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass()) {
            if (type.getName().startsWith(PACKAGE)) return true;
        }
        return false;
    }

    /** Called only around the exact supported spell's native damage instruction. */
    public static DamageBoundary beginDamage(Entity victim, DamageSource source, ServerPlayer actor, boolean projectile) {
        return beginDamage(victim, source, actor, projectile, () -> false);
    }

    public static DamageBoundary beginDamage(Entity victim, DamageSource source, ServerPlayer actor, boolean projectile,
                                             BooleanSupplier currentAuthority) {
        DamageFrame previous = DAMAGE.get();
        DamageFrame frame = new DamageFrame(victim, source, actor, projectile, previous, currentAuthority);
        try {
            frame.direct = source == null ? null : source.getDirectEntity();
            frame.generation = EventTrailManager.getInstance().recordingSnapshot().generation();
            frame.admitted = previous == null && live(actor) && victim != null && source != null
                    && currentAuthority != null && currentAuthority.getAsBoolean()
                    && victim.level() == actor.level() && isIronSource(source)
                    && source.getEntity() == actor
                    && (projectile ? source.getDirectEntity() instanceof Projectile
                    : source.getDirectEntity() == actor);
            if (previous != null) previous.admitted = false;
        } catch (RuntimeException | LinkageError failure) {
            frame.admitted = false;
            AdapterFailSafe.report(AdapterKey.IRONS, "bridge:IronsSpellAuditRuntime.beginDamage", failure);
        }
        // N-1: the boundary is built before DAMAGE is set, so a failure while building it leaves nothing open.
        DamageBoundary boundary = new DamageBoundary(frame);
        DAMAGE.set(frame);
        return boundary;
    }

    /** Empty means unrelated source; a present negative forbids generic ownership fallback. */
    public static Optional<EntityKillAttribution> resolveDamage(LivingEntity victim, DamageSource source) {
        if (!isIronSource(source)) return Optional.empty();
        DamageFrame frame = DAMAGE.get();
        if (frame != null && frame.admitted && frame.victim == victim && frame.source == source
                && live(frame.actor) && victim.level() == frame.actor.level()
                && frame.generation == EventTrailManager.getInstance().recordingSnapshot().generation()
                && frame.currentAuthority.getAsBoolean() && source.getDirectEntity() == frame.direct
                && source.getEntity() == frame.actor
                && (frame.projectile ? source.getDirectEntity() instanceof Projectile : source.getDirectEntity() == frame.actor)) {
            ActorProvenance actor = NeoForgePlayerProvenance.capture(frame.actor,
                    frame.projectile ? ActorProvenance.Causality.IMMEDIATE_SECONDARY : ActorProvenance.Causality.DIRECT,
                    null).orElse(null);
            if (actor != null) return Optional.of(EntityKillAttribution.accountable(
                    frame.projectile ? EntityKillAttribution.ProvenanceKind.PLAYER_PROJECTILE
                            : EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER, actor));
        }
        return Optional.of(EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY));
    }

    // M-2: the spell ledger writes no row (a cast's row is counted at its own capture point), so this gate is the
    // plain readiness check, not a counted skipped capture attempt (CaptureGateSourceScanTest names this exemption).
    // GF-FAKEPLAYER-ID fu2: the listed-player test is the counted isAdmittedPlayer, after the uncounted tests before it.
    public static boolean live(ServerPlayer player) {
        return player != null && player.server != null && player.server.isSameThread()
                && !player.isRemoved() && player.isAlive() && !player.isSpectator()
                && !NeoForgePlayerProvenance.isSynthetic(player)
                && NeoForgePlayerProvenance.isAdmittedPlayer(player)
                && player.serverLevel().getEntity(player.getUUID()) == player
                && EventTrailManager.getInstance().isReady();
    }

    /** No Iron's explosion support is claimed by this tranche. Ordinary explosions stay unchanged. */
    public static boolean denyExplosion(Explosion explosion) {
        return isIronEntity(explosion.getDirectSourceEntity())
                || isIronEntity(explosion.getIndirectSourceEntity()) || ironType(explosion.getClass());
    }

    public static synchronized void registerLifecycle(Runnable clear, Consumer<UUID> logout, Consumer<MinecraftServer> tick) {
        if (lifecycle != null) throw new IllegalStateException("Iron's observer already registered");
        lifecycle = new Lifecycle(clear, logout, tick);
    }
    /**
     * R2-inner lifecycle rule: with IRONS OFF or its family stopped the tick is skipped; clear and logout always run.
     * A failure of any of the three is reported to the fail-safe and never thrown (observation never controls
     * gameplay).
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.run(AdapterKey.IRONS, "lifecycle:IronsSpellAuditRuntime.tick",
                () -> value.tick.accept(server));
    }
    public static void onLogout(UUID playerId) {
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.contain(AdapterKey.IRONS, "lifecycle:IronsSpellAuditRuntime.logout",
                () -> value.logout.accept(playerId));
    }
    public static void clear() {
        DAMAGE.remove();
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.contain(AdapterKey.IRONS, "lifecycle:IronsSpellAuditRuntime.clear", value.clear);
    }

    public static final class DamageBoundary implements AutoCloseable {
        private final DamageFrame frame;
        private boolean closed;
        private DamageBoundary(DamageFrame frame) { this.frame = frame; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            frame.admitted = false;
            if (DAMAGE.get() != frame) { DAMAGE.remove(); return; }
            if (frame.previous == null) DAMAGE.remove(); else DAMAGE.set(frame.previous);
        }
    }
    private static final class DamageFrame {
        private final Entity victim;
        private final DamageSource source;
        private final ServerPlayer actor;
        private final boolean projectile;
        private final DamageFrame previous;
        private final BooleanSupplier currentAuthority;
        private Entity direct;
        private long generation;
        private boolean admitted;
        private DamageFrame(Entity victim, DamageSource source, ServerPlayer actor, boolean projectile, DamageFrame previous,
                            BooleanSupplier currentAuthority) {
            this.victim = victim; this.source = source; this.actor = actor; this.projectile = projectile; this.previous = previous;
            this.currentAuthority = currentAuthority;
        }
    }
    private record Lifecycle(Runnable clear, Consumer<UUID> logout, Consumer<MinecraftServer> tick) { }
}
