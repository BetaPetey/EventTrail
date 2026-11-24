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
import net.minecraft.world.level.Explosion;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** No optional API linkage. Exact mod producers enrich the existing death path, never write rows. */
public final class ArsSpellAuditRuntime {
    private static final String NAMESPACE = "ars_nouveau";
    private static final String PACKAGE = "com.hollingsworth.arsnouveau.";
    private static final ThreadLocal<DamageFrame> DAMAGE = new ThreadLocal<>();
    private static volatile Lifecycle lifecycle;

    private ArsSpellAuditRuntime() { }

    /** Recognition grants no credit, including for unknown versions or unavailable adapters. */
    public static boolean isArsSource(DamageSource source) {
        if (source == null) return false;
        if (arsType(source.getClass())) return true;
        if (source.typeHolder().unwrapKey().map(k -> NAMESPACE.equals(k.location().getNamespace())).orElse(false)) return true;
        return isArsEntity(source.getDirectEntity()) || isArsEntity(source.getEntity());
    }

    public static boolean isArsEntity(Entity entity) {
        if (entity == null) return false;
        if (arsType(entity.getClass())) return true;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && NAMESPACE.equals(id.getNamespace());
    }

    static boolean arsType(Class<?> type) {
        for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass()) {
            if (type.getName().startsWith(PACKAGE)) return true;
        }
        return false;
    }

    /** Recognition of one exact native damage call also covers event-replaced vanilla sources. */
    public static boolean isArsDamage(LivingEntity victim, DamageSource source) {
        DamageFrame frame = DAMAGE.get();
        return frame != null && frame.victim == victim && frame.source == source || isArsSource(source);
    }

    /** Exact qualified producers call this at the native hurt instruction, after mutable events. */
    public static DamageBoundary beginDamage(Entity victim, DamageSource source, ServerPlayer actor,
                                             BooleanSupplier currentAuthority) {
        return beginDamage(victim, source, actor, currentAuthority, currentAuthority);
    }

    /**
     * Admission must be current at the exact native hurt instruction. Completion
     * runs inside that same hurt call, after cancellable death listeners, where
     * an input-start deadline alone must not erase an already admitted action.
     */
    public static DamageBoundary beginDamage(Entity victim, DamageSource source, ServerPlayer actor,
                                             BooleanSupplier admissionAuthority, BooleanSupplier completionAuthority) {
        DamageFrame previous = DAMAGE.get();
        // Nested damage revokes its outer attribution before any fallible source
        // inspection, including a modded DamageSource getter.
        if (previous != null) previous.admitted = false;
        DamageFrame frame = new DamageFrame(victim, source, actor, previous, completionAuthority);
        try {
            frame.direct = source == null ? null : source.getDirectEntity();
            frame.generation = EventTrailManager.getInstance().recordingSnapshot().generation();
            frame.admitted = previous == null && live(actor) && victim != null && source != null
                    && admissionAuthority != null && completionAuthority != null && admissionAuthority.getAsBoolean()
                    && victim.level() == actor.level() && isArsSource(source)
                    && source.getEntity() == actor && source.getDirectEntity() == actor;
        } catch (RuntimeException | LinkageError failure) {
            frame.admitted = false;
            AdapterFailSafe.report(AdapterKey.ARS_CAST, "bridge:ArsSpellAuditRuntime.beginDamage", failure);
        }
        // N-1: the boundary is built before DAMAGE is set, so a failure while building it leaves nothing open.
        DamageBoundary boundary = new DamageBoundary(frame);
        DAMAGE.set(frame);
        return boundary;
    }

    /** Empty means unrelated; a present negative forbids ownership and historical-credit fallback. */
    public static Optional<EntityKillAttribution> resolveDamage(LivingEntity victim, DamageSource source) {
        if (!isArsDamage(victim, source)) return Optional.empty();
        DamageFrame frame = DAMAGE.get();
        if (frame != null && frame.admitted && frame.victim == victim && frame.source == source
                && live(frame.actor) && victim.level() == frame.actor.level()
                && frame.generation == EventTrailManager.getInstance().recordingSnapshot().generation()
                && frame.completionAuthority.getAsBoolean() && source.getDirectEntity() == frame.direct
                && source.getEntity() == frame.actor && source.getDirectEntity() == frame.actor) {
            // Ars Harm deliberately collapses even projectile delivery to direct=causing player.
            // Preserve actual source/payload meaning; exact projectile causality stays internal.
            ActorProvenance actor = NeoForgePlayerProvenance.capture(frame.actor,
                    ActorProvenance.Causality.DIRECT, null).orElse(null);
            if (actor != null) return Optional.of(EntityKillAttribution.accountable(
                    EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER, actor));
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

    /** No Ars explosion support is claimed by this tranche. Ordinary explosions stay unchanged. */
    public static boolean denyExplosion(Explosion explosion) {
        return ArsNegativeExplosionScope.denies(explosion) || isArsEntity(explosion.getDirectSourceEntity())
                || isArsEntity(explosion.getIndirectSourceEntity()) || arsType(explosion.getClass());
    }

    public static synchronized void registerLifecycle(Runnable clear, Consumer<UUID> logout, Consumer<MinecraftServer> tick) {
        if (lifecycle != null) throw new IllegalStateException("Ars observer already registered");
        lifecycle = new Lifecycle(clear, logout, tick);
    }
    /**
     * R2-inner lifecycle rule: with ARS_CAST OFF or its family stopped the tick is skipped; clear and logout always
     * run, because clear also resets the damage frame and the negative explosion scope and skipping it would leak
     * per-player spell state past logout and server stop. A failure of any of the three is reported to the fail-safe
     * and never thrown (observation never controls gameplay).
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.run(AdapterKey.ARS_CAST, "lifecycle:ArsSpellAuditRuntime.tick",
                () -> value.tick.accept(server));
    }
    public static void onLogout(UUID playerId) {
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.contain(AdapterKey.ARS_CAST, "lifecycle:ArsSpellAuditRuntime.logout",
                () -> value.logout.accept(playerId));
    }
    public static void clear() {
        DAMAGE.remove();
        ArsNegativeExplosionScope.clear();
        Lifecycle value = lifecycle;
        if (value != null) AdapterFailSafe.contain(AdapterKey.ARS_CAST, "lifecycle:ArsSpellAuditRuntime.clear", value.clear);
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
        private final DamageFrame previous;
        private final BooleanSupplier completionAuthority;
        private Entity direct;
        private long generation;
        private boolean admitted;
        private DamageFrame(Entity victim, DamageSource source, ServerPlayer actor, DamageFrame previous,
                            BooleanSupplier completionAuthority) {
            this.victim = victim; this.source = source; this.actor = actor; this.previous = previous;
            this.completionAuthority = completionAuthority;
        }
    }
    private record Lifecycle(Runnable clear, Consumer<UUID> logout, Consumer<MinecraftServer> tick) { }
}
