package io.github.betapetey.eventtrail.events.spells;

import com.hollingsworth.arsnouveau.api.registry.SpellCasterRegistry;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.api.spell.wrapped_caster.PlayerCaster;
import com.hollingsworth.arsnouveau.common.entity.EntityProjectileSpell;
import com.hollingsworth.arsnouveau.common.items.SpellBook;
import com.hollingsworth.arsnouveau.common.network.AbstractPacket;
import com.hollingsworth.arsnouveau.common.network.PacketCastSpell;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectHarm;
import com.hollingsworth.arsnouveau.common.spell.method.MethodProjectile;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exact native Ars instruction membership. This observer never writes audit rows. */
public final class ArsCastAuditBridge {
    public static final int MAX_ACTIVE_CASTS = 1, MAX_CHILD_DEPTH = 0, MAX_DESCENDANTS_PER_CAST = 1;
    public static final int MAX_EFFECTS = 4096, MAX_EFFECTS_PER_PLAYER = 256, MAX_PROJECTILE_TICKS = 1200;
    public static final long MAX_CAST_NANOS = 1_000_000_000L, MAX_EFFECT_NANOS = 60_000_000_000L;
    public static final long CAST_METADATA_CHARGE = 1024, EFFECT_METADATA_CHARGE = 1024;
    public static final long MAX_METADATA_CHARGE = CAST_METADATA_CHARGE + MAX_EFFECTS * EFFECT_METADATA_CHARGE;
    private static final IdentityHashMap<Entity, Effect> EFFECTS = new IdentityHashMap<>();
    private static final IdentityHashMap<ServerPlayer, Integer> COUNTS = new IdentityHashMap<>();
    private static final IdentityHashMap<SpellContext, Entity> CONTEXT_EFFECTS = new IdentityHashMap<>();
    private static volatile Thread ownerThread;
    private static final ThreadLocal<Network> NETWORK = new ThreadLocal<>();
    private static final ThreadLocal<Cast> INPUT = new ThreadLocal<>();
    private static final ThreadLocal<Cast> METHOD_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Cast> METHOD = new ThreadLocal<>();
    private static final ThreadLocal<Effect> TICK = new ThreadLocal<>();
    private static final ThreadLocal<Effect> TRACE_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Effect> TRACE = new ThreadLocal<>();
    private static final ThreadLocal<Impact> HIT_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Effect> IMPACT = new ThreadLocal<>();
    private static final ThreadLocal<Resolution> RESOLVE_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Resolution> RESOLVE = new ThreadLocal<>();
    private static final ThreadLocal<Resolution> RESUME = new ThreadLocal<>();
    private static final ThreadLocal<Harm> HARM_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Harm> HARM = new ThreadLocal<>();
    private static final ThreadLocal<Harm> DAMAGE_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Harm> DAMAGE = new ThreadLocal<>();
    static { ArsSpellAuditRuntime.registerLifecycle(ArsCastAuditBridge::clear, ArsCastAuditBridge::logout, ArsCastAuditBridge::tick); }
    private ArsCastAuditBridge() {}

    /** Only the native registered server payload dispatcher supplies this permission. */
    public static void network(AbstractPacket packet, ServerPlayer player, Runnable original) {
        Network old = NETWORK.get();
        if (old != null) old.invalid = true;
        if (!safe(() -> packet != null && packet.getClass() == PacketCastSpell.class && ArsSpellAuditRuntime.live(player))
                || !claimOwnerThread()) { original.run(); return; }
        Network network = new Network(packet, player); network.invalid = old != null;
        NETWORK.set(network);
        try { original.run(); } finally { network.invalid = true; restore(NETWORK, old); }
    }

    public static <T> T input(AbstractPacket packet, AbstractCaster<?> caster, Level level, LivingEntity actor, InteractionHand hand,
                              Spell spell, Supplier<T> original) {
        if (!onOwnerThread()) return original.get();
        Network network = NETWORK.get(); Cast old = INPUT.get();
        if (old != null) old.invalid = true;
        Cast cast = safeGet(() -> old == null && network != null && !network.invalid && !network.used
                && network.packet == packet && network.player == actor && actor.level() == level && hand != null && supported(spell)
                && caster != null && caster.getClass() == SpellCaster.class
                && actor.getItemInHand(hand).getItem().getClass() == SpellBook.class
                && SpellCasterRegistry.from(actor.getItemInHand(hand)) == caster
                ? new Cast(network, network.player, hand, actor.getItemInHand(hand), caster, spell) : null);
        if (network != null) network.used = true;
        INPUT.set(cast);
        boolean normal = false;
        try { T result = original.get(); normal = true; return result; }
        finally {
            if (cast != null) {
                if (!normal) { cast.invalid = true; invalidateEntity(cast.descendant); }
                else if (cast.descendant != null) {
                    Effect effect = safeGet(() -> EFFECTS.get(cast.descendant));
                    if (effect != null) effect.inputComplete = true;
                }
            }
            restore(INPUT, old);
        }
    }

    public static SpellResolver resolver(AbstractCaster<?> caster, SpellContext context, Level level,
                                         LivingEntity actor, InteractionHand hand, Supplier<SpellResolver> original) {
        SpellResolver resolver = original.get();
        if (!onOwnerThread()) return resolver;
        safeRun(() -> {
            Cast cast = INPUT.get();
            if (cast != null && cast.resolver == null && cast.caster == caster && cast.player == actor
                    && cast.level == level && cast.hand == hand && cast.inputCurrent()
                    && resolver != null && resolver.getClass() == SpellResolver.class && resolver.spellContext == context
                    && context.getSpell() == cast.spell && context.getPreviousContext() == null
                    && context.getCaster().getClass() == PlayerCaster.class
                    && ((PlayerCaster) context.getCaster()).player == cast.player
                    && ((PlayerCaster) context.getCaster()).livingEntity == cast.player
                    && context.getUnwrappedCaster() == cast.player && context.level == cast.level
                    && context.castingTile == null && !context.isCanceled() && !context.isDelayed()) {
                cast.context = context; cast.resolver = resolver; cast.wrappedCaster = context.getCaster();
                cast.contextTool = context.getCasterTool();
            }
        });
        return resolver;
    }

    /** The native resolver invokes this only after validation, mana and cancellation checks. */
    public static CastResolveType accepted(SpellResolver resolver, AbstractCastMethod method, SpellContext context,
                                           SpellStats stats, Supplier<CastResolveType> original) {
        if (!onOwnerThread()) return original.get();
        Cast cast = INPUT.get(); Cast old = METHOD_PERMIT.get();
        Cast accepted = safe(() -> old == null && cast != null && !cast.accepted && cast.resolver == resolver
                && cast.context == context && cast.method == method && cast.inputCurrent() && cast.contextCurrent()
                && context.getCurrentIndex() == 0 && plain(stats)) ? cast : null;
        if (accepted != null) accepted.accepted = true;
        METHOD_PERMIT.set(accepted);
        boolean success = false;
        try { CastResolveType result = original.get(); success = result != null && result.wasSuccess; return result; }
        finally {
            if (!success && accepted != null) { accepted.invalid = true; invalidateEntity(accepted.descendant); }
            restore(METHOD_PERMIT, old);
        }
    }

    public static <T> T methodBody(SpellResolver resolver, LivingEntity actor, SpellStats stats,
                                   boolean projectile, Supplier<T> original) {
        if (!onOwnerThread()) return original.get();
        Cast permit = METHOD_PERMIT.get(); METHOD_PERMIT.remove(); Cast old = METHOD.get();
        Cast cast = safe(() -> old == null && permit != null && permit.resolver == resolver && permit.player == actor
                && permit.projectile == projectile && permit.inputCurrent() && permit.contextCurrent() && plain(stats)) ? permit : null;
        METHOD.set(cast);
        try { return original.get(); } finally { restore(METHOD, old); }
    }

    public static boolean spawn(Level level, Entity entity, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Cast cast = METHOD.get();
        Effect pending = safeGet(() -> cast != null && cast.projectile && cast.descendant == null && cast.inputCurrent()
                && cast.contextCurrent() && entity != null && entity.getClass() == EntityProjectileSpell.class
                && entity.level() == level && level == cast.level && entity.tickCount == 0 && !entity.isAddedToLevel()
                && !entity.isRemoved() && ((EntityProjectileSpell) entity).getOwner() == cast.player
                && ((EntityProjectileSpell) entity).resolver() == cast.resolver && hasEffectCapacity(cast.player)
                ? new Effect(cast, (EntityProjectileSpell) entity) : null);
        if (pending != null) { cast.descendant = entity; EFFECTS.put(entity, pending); CONTEXT_EFFECTS.put(cast.context, entity); COUNTS.merge(cast.player, 1, Integer::sum); }
        METHOD.remove(); boolean added = false;
        try { added = original.getAsBoolean(); return added; }
        finally {
            restore(METHOD, cast); final boolean success = added;
            safeRun(() -> {
                if (pending != null && success && EFFECTS.get(entity) == pending && !pending.invalid
                        && cast.inputCurrent() && cast.contextCurrent() && entity.isAddedToLevel()
                        && !entity.isRemoved() && cast.level.getEntity(entity.getUUID()) == entity) pending.admitted = true;
                else if (pending != null) invalidateEntity(entity);
            });
        }
    }

    public static void projectileTick(Entity entity, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect old = TICK.get(); Effect candidate = safeGet(() -> EFFECTS.get(entity));
        Effect effect = safe(() -> old == null && candidate != null && candidate.current()) ? candidate : null;
        TICK.set(effect);
        boolean normal = false;
        try { original.run(); normal = true; } finally {
            restore(TICK, old); if (!normal) invalidateEntity(entity); else safeRun(() -> prune(entity));
        }
    }

    public static void trace(Entity entity, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect old = TRACE_PERMIT.get(); Effect effect = TICK.get();
        TRACE_PERMIT.set(safe(() -> old == null && effect != null && effect.entity == entity && effect.current()) ? effect : null);
        try { original.run(); } finally { restore(TRACE_PERMIT, old); }
    }
    public static void traceBody(Entity entity, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect permit = TRACE_PERMIT.get(); TRACE_PERMIT.remove(); Effect old = TRACE.get();
        TRACE.set(safe(() -> old == null && permit != null && permit.entity == entity && permit.current()) ? permit : null);
        try { original.run(); } finally { restore(TRACE, old); }
    }
    /** Only the native traceAnyHit -> onHit member can permit resolution. */
    public static void hit(Entity entity, HitResult result, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect effect = TRACE.get(); Impact old = HIT_PERMIT.get();
        Impact allowed = safeGet(() -> old == null && effect != null && effect.entity == entity
                && result instanceof EntityHitResult && effect.current() ? new Impact(effect, result) : null);
        HIT_PERMIT.set(allowed);
        try { original.run(); } finally { restore(HIT_PERMIT, old); }
    }
    public static void hitBody(Entity entity, HitResult result, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Impact permit = HIT_PERMIT.get(); HIT_PERMIT.remove(); Effect old = IMPACT.get();
        IMPACT.set(safe(() -> old == null && permit != null && permit.effect.entity == entity
                && permit.result == result && permit.effect.current()) ? permit.effect : null);
        try { original.run(); } finally { restore(IMPACT, old); }
    }

    /** Invoked at exact Touch/Projectile resolver calls after native impact cancellation. */
    public static void resolve(SpellResolver resolver, Level level, HitResult result, Entity projectile, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Cast cast = METHOD.get(); Effect effect = IMPACT.get(); Resolution old = RESOLVE_PERMIT.get();
        Resolution permit = safeGet(() -> {
            if (old != null || !(result instanceof EntityHitResult hit)) return null;
            if (projectile == null && cast != null && !cast.projectile && cast.resolver == resolver
                    && cast.level == level && !cast.resolved && cast.inputCurrent() && cast.contextCurrent()) {
                cast.resolved = true; return new Resolution(cast, null, hit);
            }
            if (projectile != null && effect != null && effect.entity == projectile && effect.cast.resolver == resolver
                    && effect.cast.level == level && !effect.resolved && effect.current()) {
                effect.resolved = true; return new Resolution(effect.cast, effect, hit);
            }
            return null;
        });
        RESOLVE_PERMIT.set(permit);
        try { original.run(); } finally {
            restore(RESOLVE_PERMIT, old);
            if (permit != null && permit.effect != null) invalidateEntity(permit.effect.entity);
        }
    }

    public static void resolveBody(SpellResolver resolver, Level level, HitResult result, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Resolution permit = RESOLVE_PERMIT.get(); RESOLVE_PERMIT.remove(); Resolution old = RESOLVE.get();
        Resolution resolution = safe(() -> old == null && permit != null && permit.cast.resolver == resolver
                && permit.cast.level == level && permit.hit == result && permit.current()) ? permit : null;
        if (resolution == null && resolver != null) safeRun(() -> invalidateContext(resolver.spellContext));
        RESOLVE.set(resolution);
        try { original.run(); } finally { restore(RESOLVE, old); }
    }

    public static void resumeBody(SpellResolver resolver, Level level, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Resolution resolution = RESOLVE.get(); Resolution old = RESUME.get();
        Resolution allowed = safe(() -> old == null && resolution != null && !resolution.resumed
                && resolution.cast.resolver == resolver && resolution.cast.level == level && resolution.current()) ? resolution : null;
        if (allowed != null) allowed.resumed = true;
        else if (resolver != null) safeRun(() -> invalidateContext(resolver.spellContext));
        RESUME.set(allowed);
        try { original.run(); } finally { restore(RESUME, old); }
    }

    public static void effect(AbstractEffect effect, HitResult hit, Level level, LivingEntity actor, SpellStats stats,
                               SpellContext context, SpellResolver resolver, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Resolution resolution = RESUME.get(); Harm old = HARM_PERMIT.get();
        Harm harm = safeGet(() -> old == null && resolution != null && !resolution.effectUsed && effect == EffectHarm.INSTANCE
                && resolution.hit == hit && resolution.cast.resolver == resolver && resolution.cast.context == context
                && resolution.cast.player == actor && resolution.cast.level == level && context.getCurrentIndex() == 2
                && resolution.current() && plain(stats) ? new Harm(resolution, stats) : null);
        if (harm != null) resolution.effectUsed = true;
        HARM_PERMIT.set(harm);
        try { original.run(); } finally { restore(HARM_PERMIT, old); }
    }

    public static void harmBody(EntityHitResult hit, Level level, LivingEntity actor, SpellStats stats,
                                SpellContext context, SpellResolver resolver, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Harm permit = HARM_PERMIT.get(); HARM_PERMIT.remove(); Harm old = HARM.get();
        Harm harm = safe(() -> old == null && permit != null && permit.matches(hit.getEntity(), level, actor, stats, context, resolver)
                && permit.resolution.hit == hit && permit.current()) ? permit : null;
        HARM.set(harm);
        try { original.run(); } finally { restore(HARM, old); }
    }

    public static boolean attemptDamage(Level level, LivingEntity actor, SpellStats stats, SpellContext context,
                                         SpellResolver resolver, Entity target, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Harm harm = HARM.get(); Harm old = DAMAGE_PERMIT.get();
        Harm allowed = safe(() -> old == null && harm != null && !harm.attempted
                && harm.matches(target, level, actor, stats, context, resolver) && harm.current()) ? harm : null;
        if (allowed != null) allowed.attempted = true;
        DAMAGE_PERMIT.set(allowed);
        try { return original.getAsBoolean(); } finally { restore(DAMAGE_PERMIT, old); }
    }

    public static boolean damageBody(IDamageEffect effect, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Entity target, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Harm permit = DAMAGE_PERMIT.get(); DAMAGE_PERMIT.remove(); Harm old = DAMAGE.get();
        Harm harm = safe(() -> old == null && effect == EffectHarm.INSTANCE && permit != null
                && permit.matches(target, level, actor, stats, context, resolver) && permit.current()) ? permit : null;
        DAMAGE.set(harm);
        try { return original.getAsBoolean(); } finally { restore(DAMAGE, old); }
    }

    /** Exact final entity.hurt after SpellDamageEvent.Pre; replacements cannot borrow another target's frame. */
    public static boolean damage(Entity target, DamageSource source, BooleanSupplier original) {
        Harm harm = onOwnerThread() ? DAMAGE.get() : null;
        ServerPlayer actor = safeGet(() -> harm != null && !harm.damaged && harm.resolution.hit.getEntity() == target
                && harm.current() && source.getEntity() == harm.resolution.cast.player
                && source.getDirectEntity() == harm.resolution.cast.player ? harm.resolution.cast.player : null);
        if (harm != null) harm.damaged = true;
        DAMAGE.remove();
        try (var boundary = ArsSpellAuditRuntime.beginDamage(target, source, actor,
                () -> harm != null && harm.current(), () -> harm != null && harm.completionCurrent())) {
            boolean accepted = original.getAsBoolean();
            if (accepted && actor == null && target instanceof LivingEntity living) ArsEngineCreditGuard.excludeHistoricalCredit(living);
            return accepted;
        } finally { restore(DAMAGE, harm); }
    }

    public static void invalidateEntity(Object entity) {
        if (!onOwnerThread()) return;
        safeRun(() -> { Effect effect = EFFECTS.remove(entity); if (effect != null) {
            effect.invalid = true; CONTEXT_EFFECTS.remove(effect.cast.context); COUNTS.computeIfPresent(effect.cast.player, (p, n) -> n <= 1 ? null : n - 1);
        }});
    }
    public static void invalidateContext(Object context) {
        if (!onOwnerThread()) return;
        safeRun(() -> {
            Entity entity = CONTEXT_EFFECTS.get(context); if (entity != null) invalidateEntity(entity);
            Cast cast = INPUT.get(); if (cast != null && cast.context == context) cast.invalid = true;
        });
    }
    /** The sole supported reset is the exact native onResolveEffect -> resolveAllEffects reset. */
    public static void resetContext(Object context) {
        if (!onOwnerThread()) return;
        Resolution resolution = RESOLVE.get();
        if (!safe(() -> resolution != null && !resolution.resumed && resolution.cast.context == context && resolution.current())) invalidateContext(context);
    }
    private static void prune(Entity entity) { Effect effect = EFFECTS.get(entity); if (effect != null && !safe(effect::current)) invalidateEntity(entity); }
    private static void tick(MinecraftServer server) {
        if (server == null || !server.isSameThread() || !claimOwnerThread() || server.getTickCount() % 20 != 0) return;
        safeRun(() -> { for (Entity entity : EFFECTS.keySet().toArray(Entity[]::new)) prune(entity); });
    }
    private static void logout(UUID id) {
        if (!onOwnerThread()) return;
        safeRun(() -> { for (Effect effect : EFFECTS.values().toArray(Effect[]::new)) if (effect.cast.player.getUUID().equals(id)) invalidateEntity(effect.entity); });
    }
    public static void clear() {
        if (!onOwnerThread()) return;
        EFFECTS.values().forEach(e -> e.invalid = true); EFFECTS.clear(); COUNTS.clear(); CONTEXT_EFFECTS.clear();
        Network network = NETWORK.get(); if (network != null) network.invalid = true;
        Cast cast = INPUT.get(); if (cast != null) cast.invalid = true;
        NETWORK.remove(); INPUT.remove(); METHOD_PERMIT.remove(); METHOD.remove(); TICK.remove(); TRACE_PERMIT.remove(); TRACE.remove(); HIT_PERMIT.remove(); IMPACT.remove();
        RESOLVE_PERMIT.remove(); RESOLVE.remove(); RESUME.remove(); HARM_PERMIT.remove(); HARM.remove(); DAMAGE_PERMIT.remove(); DAMAGE.remove();
        ownerThread = null;
    }
    public record Snapshot(int casts, int effects, int playersWithEffects, long metadataCharge) {}
    public static Snapshot snapshot() {
        if (ownerThread == null) return new Snapshot(0, 0, 0, 0);
        if (!onOwnerThread()) return new Snapshot(-1, -1, -1, -1);
        int casts = INPUT.get() == null ? 0 : 1;
        return new Snapshot(casts, EFFECTS.size(), COUNTS.size(), casts * CAST_METADATA_CHARGE + EFFECTS.size() * EFFECT_METADATA_CHARGE);
    }
    static boolean hasEffectCapacity(ServerPlayer player) { return onOwnerThread() && EFFECTS.size() < MAX_EFFECTS && COUNTS.getOrDefault(player, 0) < MAX_EFFECTS_PER_PLAYER; }
    static boolean withinLifetime(long start, long now, long maximum) { long elapsed = now - start; return elapsed >= 0 && elapsed < maximum; }
    static boolean supported(Spell spell) { return spell != null && spell.getClass() == Spell.class && spell.size() == 2
            && (spell.get(0) == MethodTouch.INSTANCE || spell.get(0) == MethodProjectile.INSTANCE) && spell.get(1) == EffectHarm.INSTANCE; }
    private static boolean plain(SpellStats stats) { return stats != null && stats.getAugments().isEmpty(); }
    private static boolean onOwnerThread() { return ownerThread == Thread.currentThread(); }
    private static synchronized boolean claimOwnerThread() { if (ownerThread == null) ownerThread = Thread.currentThread(); return onOwnerThread(); }
    // R2-inner: the three swallowing helpers fail conservatively on ANY Throwable. A failed check grants no permit
    // (false / null) and the failure is reported to ARS_CAST; a throwable other than RuntimeException or LinkageError
    // (an Error) also invalidates the frames of this thread and every pending effect, because a helper that failed
    // half way through an invalidation or a binding step may have left a permit behind.
    private static boolean safe(BooleanSupplier call) {
        try { return call.getAsBoolean(); } catch (Throwable failure) { failed("bridge:ArsCastAuditBridge.safe", failure); return false; }
    }
    private static <T> T safeGet(Supplier<T> call) {
        try { return call.get(); } catch (Throwable failure) { failed("bridge:ArsCastAuditBridge.safeGet", failure); return null; }
    }
    private static void safeRun(Runnable call) {
        try { call.run(); } catch (Throwable failure) { failed("bridge:ArsCastAuditBridge.safeRun", failure); /* Observation only. */ }
    }
    private static void failed(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.ARS_CAST, site, failure);
        if (!(failure instanceof RuntimeException || failure instanceof LinkageError)) taint();
    }
    private static void taint() {
        Network network = NETWORK.get(); if (network != null) network.invalid = true;
        Cast input = INPUT.get(); if (input != null) input.invalid = true;
        Cast method = METHOD.get(); if (method != null) method.invalid = true;
        if (onOwnerThread()) for (Effect effect : EFFECTS.values()) effect.invalid = true;
    }
    private static <T> void restore(ThreadLocal<T> local, T value) { if (value == null) local.remove(); else local.set(value); }
    private static final class Network { final AbstractPacket packet; final ServerPlayer player; boolean invalid, used;
        Network(AbstractPacket packet, ServerPlayer player) { this.packet = packet; this.player = player; } }
    private static final class Cast {
        final Network network;
        final ServerPlayer player; final ServerLevel level; final InteractionHand hand; final ItemStack stack;
        final AbstractCaster<?> caster; final Spell spell; final AbstractCastMethod method; final boolean projectile;
        final long started = System.nanoTime(), generation; SpellResolver resolver; SpellContext context; Object wrappedCaster; ItemStack contextTool;
        boolean invalid, accepted, resolved; Entity descendant;
        Cast(Network network, ServerPlayer player, InteractionHand hand, ItemStack stack, AbstractCaster<?> caster, Spell spell) {
            this.network = network; this.player = player; this.level = player.serverLevel(); this.hand = hand; this.stack = stack; this.caster = caster;
            this.spell = spell; this.method = spell.getCastMethod(); this.projectile = method == MethodProjectile.INSTANCE;
            this.generation = EventTrailManager.getInstance().recordingSnapshot().generation();
        }
        boolean baseCurrent() { return !invalid && ArsSpellAuditRuntime.live(player) && player.serverLevel() == level
                && EventTrailManager.getInstance().recordingSnapshot().generation() == generation; }
        boolean inputCurrent() { return inputStructuralCurrent() && withinLifetime(started, System.nanoTime(), MAX_CAST_NANOS); }
        boolean inputStructuralCurrent() { return baseCurrent() && INPUT.get() == this && NETWORK.get() == network && !network.invalid
                && player.getItemInHand(hand) == stack && SpellCasterRegistry.from(stack) == caster; }
        boolean contextCurrent() { return resolver != null && resolver.getClass() == SpellResolver.class && resolver.spellContext == context
                && resolver.spell == spell && resolver.castType == method && resolver.previousResolver == null
                && context.getSpell() == spell && supported(spell) && context.level == level && context.castingTile == null
                && context.getPreviousContext() == null && context.getCaster() == wrappedCaster && wrappedCaster instanceof PlayerCaster p
                && p.getClass() == PlayerCaster.class && p.player == player && p.livingEntity == player
                && context.getUnwrappedCaster() == player && context.getCasterTool() == contextTool
                && !context.isCanceled() && !context.isDelayed(); }
    }
    private static final class Effect {
        final Cast cast; final EntityProjectileSpell entity; final long started = System.nanoTime();
        boolean admitted, inputComplete, invalid, resolved;
        Effect(Cast cast, EntityProjectileSpell entity) { this.cast = cast; this.entity = entity; }
        boolean current() { return admitted && inputComplete && !invalid && cast.baseCurrent() && cast.contextCurrent()
                && entity.getClass() == EntityProjectileSpell.class && entity.level() == cast.level && !entity.isRemoved()
                && entity.isAddedToLevel() && entity.getOwner() == cast.player && entity.resolver() == cast.resolver
                && entity.tickCount <= MAX_PROJECTILE_TICKS && entity.age <= MAX_PROJECTILE_TICKS
                && cast.level.getEntity(entity.getUUID()) == entity && withinLifetime(started, System.nanoTime(), MAX_EFFECT_NANOS); }
    }
    private static final class Resolution {
        final Cast cast; final Effect effect; final EntityHitResult hit; boolean resumed, effectUsed;
        Resolution(Cast cast, Effect effect, EntityHitResult hit) { this.cast = cast; this.effect = effect; this.hit = hit; }
        boolean current() { return hit.getEntity().level() == cast.level && cast.contextCurrent()
                && (effect == null ? cast.inputCurrent() : effect.current()); }
        boolean completionCurrent() { return hit.getEntity().level() == cast.level && cast.contextCurrent()
                && (effect == null ? cast.inputStructuralCurrent() : effect.current()); }
    }
    private record Impact(Effect effect, HitResult result) {}
    private static final class Harm {
        final Resolution resolution; final SpellStats stats; boolean attempted, damaged;
        Harm(Resolution resolution, SpellStats stats) { this.resolution = resolution; this.stats = stats; }
        boolean current() { return resolution.current() && resolution.cast.context.getCurrentIndex() == 2 && plain(stats); }
        boolean completionCurrent() { return attempted && damaged && resolution.completionCurrent()
                && resolution.cast.context.getCurrentIndex() == 2 && plain(stats); }
        boolean matches(Entity target, Level level, LivingEntity actor, SpellStats stats, SpellContext context, SpellResolver resolver) {
            Cast cast = resolution.cast; return target == resolution.hit.getEntity() && cast.level == level && cast.player == actor
                    && this.stats == stats && cast.context == context && cast.resolver == resolver;
        }
    }
}
