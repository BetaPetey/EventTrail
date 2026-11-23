package io.github.betapetey.eventtrail.events.spells;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.entity.spells.firebolt.FireboltProjectile;
import io.redspace.ironsspellbooks.spells.fire.FireboltSpell;
import io.redspace.ironsspellbooks.spells.lightning.ShockwaveSpell;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Optional exact-byte producer. Native invocation members, never entity ownership, confer authority. */
public final class IronsCastAuditBridge {
    public static final int MAX_CASTS = 256, MAX_EFFECTS = 4096, MAX_EFFECTS_PER_PLAYER = 256;
    public static final int MAX_DESCENDANTS_PER_CAST = 1, MAX_PROJECTILE_TICKS = 300;
    public static final long MAX_CAST_NANOS = 60_000_000_000L, MAX_EFFECT_NANOS = 30_000_000_000L;
    public static final long CAST_METADATA_CHARGE = 1024, EFFECT_METADATA_CHARGE = 512;
    public static final long MAX_METADATA_CHARGE = MAX_CASTS * CAST_METADATA_CHARGE + MAX_EFFECTS * EFFECT_METADATA_CHARGE;
    private static final IdentityHashMap<MagicData, Cast> CASTS = new IdentityHashMap<>();
    private static final IdentityHashMap<Entity, Effect> EFFECTS = new IdentityHashMap<>();
    private static final IdentityHashMap<ServerPlayer, Integer> COUNTS = new IdentityHashMap<>();
    private static volatile Thread ownerThread;
    private static final ThreadLocal<Input> INPUT = new ThreadLocal<>();
    private static final ThreadLocal<Attempt> ATTEMPT = new ThreadLocal<>();
    private static final ThreadLocal<Cast> EXECUTION = new ThreadLocal<>();
    private static final ThreadLocal<Cast> CALLBACK_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Cast> CALLBACK = new ThreadLocal<>();
    private static final ThreadLocal<Effect> TICK = new ThreadLocal<>();
    private static final ThreadLocal<Impact> HIT_PERMIT = new ThreadLocal<>();
    private static final ThreadLocal<Impact> IMPACT = new ThreadLocal<>();
    static { IronsSpellAuditRuntime.registerLifecycle(IronsCastAuditBridge::clear,
            IronsCastAuditBridge::logout, IronsCastAuditBridge::tick); }
    private IronsCastAuditBridge() {}

    public static MagicData data(ServerPlayer player) { return onOwnerThread() ? safeGet(() -> MagicData.getPlayerMagicData(player)) : null; }

    public static boolean input(ServerPlayer player, BooleanSupplier original) {
        Input prior = INPUT.get();
        if (prior != null) prior.invalid = true;
        if (!safe(() -> live(player)) || !claimOwnerThread()) return original.getAsBoolean();
        Input input = new Input(player);
        input.invalid = prior != null || !safe(() -> live(player));
        INPUT.set(input);
        boolean returnedNormally = false;
        try { boolean result = original.getAsBoolean(); returnedNormally = true; input.successful = result; return result; }
        finally {
            if (!returnedNormally || !input.successful) {
                input.invalid = true;
                safeRun(() -> CASTS.values().removeIf(c -> c.input == input));
            }
            restore(INPUT, prior);
        }
    }
    public static boolean attempt(AbstractSpell spell, ServerPlayer player, MagicData data,
            int spellLevel, CastSource source, String slot, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Attempt prior = ATTEMPT.get();
        if (prior != null) prior.invalid = true;
        Attempt attempt = new Attempt();
        ATTEMPT.set(attempt);
        boolean accepted = false;
        try { accepted = original.getAsBoolean(); return accepted; }
        finally {
            final boolean success = accepted;
            safeRun(() -> {
                Input input = INPUT.get();
                Cast cast = attempt.cast;
                if (success && prior == null && !attempt.invalid && cast != null && input != null
                        && !input.invalid && input.player == player && cast.spell == spell && cast.data == data
                        && cast.spellLevel == spellLevel && cast.source == source && cast.slot.equals(slot)
                        && cast.pendingCurrent() && hasCastCapacity()) {
                    CASTS.values().removeIf(old -> old.player == player);
                    CASTS.put(data, cast);
                }
            });
            restore(ATTEMPT, prior);
        }
    }
    /** Called only at the successful native initiateCast instruction, after cancellation/resource checks. */
    public static void accepted(MagicData data, AbstractSpell spell, int level, int duration,
            CastSource source, String slot) {
        if (!onOwnerThread()) return;
        safeRun(() -> {
            Input input = INPUT.get(); Attempt attempt = ATTEMPT.get();
            if (input == null || input.invalid || attempt == null || attempt.invalid || !supported(spell)
                    || !live(input.player) || MagicData.getPlayerMagicData(input.player) != data
                    || duration < 0 || duration > 1200 || source != CastSource.SPELLBOOK && source != CastSource.SWORD) return;
            ItemStack stack = equipment(input.player, slot);
            if (stack == null || !ISpellContainer.isSpellContainer(stack)) return;
            ISpellContainer container = ISpellContainer.get(stack);
            if (!container.isSpellWheel() || container.getIndexForSpell(spell) < 0) return;
            attempt.cast = new Cast(input, data, spell, level, source, slot, stack, container);
        });
    }
    public static void invalidateData(Object data) {
        if (!onOwnerThread()) return;
        safeRun(() -> {
            Cast old = CASTS.remove(data); if (old != null) old.invalid = true;
            Attempt attempt = ATTEMPT.get();
            if (attempt != null && attempt.cast != null && attempt.cast.data == data) {
                attempt.cast.invalid = true; attempt.cast = null; attempt.invalid = true;
            }
            Cast running = EXECUTION.get(); if (running != null && running.data == data) running.invalid = true;
        });
    }
    public static void execute(AbstractSpell spell, Level level, int spellLevel, ServerPlayer player,
            CastSource source, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Cast previous = EXECUTION.get();
        Cast candidate = safeGet(() -> CASTS.remove(MagicData.getPlayerMagicData(player)));
        if (previous != null) previous.invalid = true;
        Cast cast = safe(() -> previous == null && candidate != null && candidate.input.successful
                && candidate.matches(spell, level, spellLevel, player, source) && candidate.pendingCurrent()) ? candidate : null;
        EXECUTION.set(cast);
        boolean returnedNormally = false;
        try { original.run(); returnedNormally = true; }
        finally {
            if (!returnedNormally && cast != null) {
                cast.invalid = true;
                invalidateEntity(cast.descendant);
            }
            restore(EXECUTION, previous);
        }
    }
    public static void callback(AbstractSpell spell, Level level, int spellLevel, Entity player,
            CastSource source, MagicData data, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Cast candidate = EXECUTION.get();
        Cast old = CALLBACK_PERMIT.get();
        Cast cast = safe(() -> candidate != null && !candidate.callbackUsed && candidate.data == data
                && candidate.matches(spell, level, spellLevel, player, source) && candidate.pendingCurrent()) ? candidate : null;
        if (cast != null) cast.callbackUsed = true;
        CALLBACK_PERMIT.set(cast);
        try { original.run(); } finally { restore(CALLBACK_PERMIT, old); }
    }
    public static void castBody(AbstractSpell spell, ServerPlayer player, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Cast old = EXECUTION.get();
        boolean allowed = old != null && !old.executionEntered && old.spell == spell && old.player == player;
        if (allowed) old.executionEntered = true;
        EXECUTION.set(allowed ? old : null);
        try { original.run(); } finally { restore(EXECUTION, old); }
    }
    public static void spellBody(AbstractSpell spell, Entity entity, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Cast permit = CALLBACK_PERMIT.get(); CALLBACK_PERMIT.remove();
        Cast old = CALLBACK.get();
        CALLBACK.set(permit != null && permit.spell == spell && permit.player == entity ? permit : null);
        try { original.run(); } finally { restore(CALLBACK, old); }
    }
    public static boolean spawn(Level level, Entity entity, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Cast cast = CALLBACK.get();
        boolean eligible = safe(() -> cast != null && cast.spell.getClass() == FireboltSpell.class
                && cast.descendants == 0 && entity.getClass() == FireboltProjectile.class
                && entity.level() == level && level == cast.level && entity.tickCount == 0
                && ((FireboltProjectile) entity).getOwner() == cast.player && !entity.isAddedToLevel()
                && cast.active());
        Effect pending = eligible ? safeGet(() -> {
            if (!hasEffectCapacity(cast.player)) return null;
            cast.descendants++;
            cast.descendant = entity;
            Effect effect = new Effect(cast.player, cast.level, (FireboltProjectile) entity, cast);
            EFFECTS.put(entity, effect); COUNTS.merge(cast.player, 1, Integer::sum); return effect;
        }) : null;
        CALLBACK.remove();
        boolean added = false;
        try { added = original.getAsBoolean(); return added; }
        finally {
            restore(CALLBACK, cast);
            final boolean accepted = added;
            safeRun(() -> {
                if (pending != null && accepted && !pending.invalid && EFFECTS.get(entity) == pending
                        && cast.active() && !entity.isRemoved() && cast.level.getEntity(entity.getUUID()) == entity
                        && ((FireboltProjectile) entity).getOwner() == cast.player) pending.admitted = true;
                else if (pending != null) invalidateEntity(entity);
            });
        }
    }
    public static void projectileTick(Entity entity, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect old = TICK.get();
        Effect candidate = safeGet(() -> EFFECTS.get(entity));
        Effect effect = safe(() -> old == null && candidate != null && candidate.current()) ? candidate : null;
        TICK.set(effect);
        try { original.run(); } finally { restore(TICK, old); safeRun(() -> pruneEntity(entity)); }
    }
    public static void hit(Entity entity, HitResult hit, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Effect effect = TICK.get(); Impact previous = HIT_PERMIT.get();
        Impact permit = safeGet(() -> previous == null && effect != null && effect.entity == entity
                && effect.current() && hit instanceof EntityHitResult e ? new Impact(effect, e) : null);
        HIT_PERMIT.set(permit);
        try { original.run(); } finally { restore(HIT_PERMIT, previous); }
    }
    public static void impact(Entity entity, EntityHitResult hit, Runnable original) {
        if (!onOwnerThread()) { original.run(); return; }
        Impact permit = HIT_PERMIT.get(); HIT_PERMIT.remove();
        Impact old = IMPACT.get();
        IMPACT.set(permit != null && permit.effect.entity == entity && permit.hit == hit ? permit : null);
        try { original.run(); } finally { restore(IMPACT, old); }
    }
    public static boolean directDamage(AbstractSpell spell, Entity target, float amount,
            DamageSource source, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Cast cast = CALLBACK.get();
        ServerPlayer actor = safeGet(() -> cast != null && cast.spell == spell && spell.getClass() == ShockwaveSpell.class
                && cast.active() && source instanceof SpellDamageSource s && s.spell() == spell
                && source.getDirectEntity() == cast.player && source.getEntity() == cast.player ? cast.player : null);
        CALLBACK.remove();
        try (var boundary = IronsSpellAuditRuntime.beginDamage(target, source, actor, false,
                () -> cast != null && cast.active())) {
            return original.getAsBoolean();
        } finally { restore(CALLBACK, cast); }
    }
    public static boolean projectileDamage(Entity entity, Entity target, DamageSource source, BooleanSupplier original) {
        if (!onOwnerThread()) return original.getAsBoolean();
        Impact impact = IMPACT.get();
        ServerPlayer actor = safeGet(() -> impact != null && !impact.used && impact.effect.entity == entity
                && impact.hit.getEntity() == target && impact.effect.current()
                && source instanceof SpellDamageSource s && s.spell() == impact.effect.spell
                && source.getDirectEntity() == entity && source.getEntity() == impact.effect.player ? impact.effect.player : null);
        if (impact != null) impact.used = true;
        IMPACT.remove();
        try (var boundary = IronsSpellAuditRuntime.beginDamage(target, source, actor, true,
                () -> impact != null && impact.effect.current())) {
            return original.getAsBoolean();
        } finally { restore(IMPACT, impact); }
    }
    public static void invalidateEntity(Object entity) {
        if (!onOwnerThread()) return;
        safeRun(() -> { Effect removed = EFFECTS.remove(entity); if (removed != null) {
            removed.invalid = true;
            COUNTS.computeIfPresent(removed.player, (p, n) -> n <= 1 ? null : n - 1);
        }});
    }
    private static void pruneEntity(Entity entity) { Effect e = EFFECTS.get(entity); if (e != null && !safe(e::current)) invalidateEntity(entity); }
    private static void tick(MinecraftServer server) {
        if (server == null || !server.isSameThread() || !claimOwnerThread()) return;
        safeRun(() -> {
            CASTS.values().removeIf(c -> !safe(c::pendingCurrent));
            if (server.getTickCount() % 20 == 0) for (Entity e : EFFECTS.keySet().toArray(Entity[]::new)) pruneEntity(e);
        });
    }
    private static void logout(UUID player) {
        if (!onOwnerThread()) return;
        safeRun(() -> {
            CASTS.values().removeIf(c -> c.player.getUUID().equals(player));
            for (Effect e : EFFECTS.values().toArray(Effect[]::new)) if (e.player.getUUID().equals(player)) invalidateEntity(e.entity);
        });
    }
    public static void clear() {
        if (!onOwnerThread()) return;
        CASTS.values().forEach(c -> c.invalid = true); EFFECTS.values().forEach(e -> e.invalid = true);
        CASTS.clear(); EFFECTS.clear(); COUNTS.clear();
        INPUT.remove(); ATTEMPT.remove(); EXECUTION.remove(); CALLBACK_PERMIT.remove(); CALLBACK.remove();
        TICK.remove(); HIT_PERMIT.remove(); IMPACT.remove();
        ownerThread = null;
    }
    public static Snapshot snapshot() {
        if (ownerThread == null) return new Snapshot(0, 0, 0, 0);
        if (!onOwnerThread()) return new Snapshot(-1, -1, -1, -1);
        return new Snapshot(CASTS.size(), EFFECTS.size(), COUNTS.size(),
                CASTS.size() * CAST_METADATA_CHARGE + EFFECTS.size() * EFFECT_METADATA_CHARGE);
    }
    public record Snapshot(int casts, int effects, int playersWithEffects, long metadataCharge) {}
    private static boolean supported(AbstractSpell spell) { return spell != null && (spell.getClass() == FireboltSpell.class || spell.getClass() == ShockwaveSpell.class); }
    static boolean hasCastCapacity() { return onOwnerThread() && CASTS.size() < MAX_CASTS; }
    static boolean hasEffectCapacity(ServerPlayer player) {
        return onOwnerThread() && EFFECTS.size() < MAX_EFFECTS && COUNTS.getOrDefault(player, 0) < MAX_EFFECTS_PER_PLAYER;
    }
    private static boolean onOwnerThread() { return ownerThread == Thread.currentThread(); }
    private static synchronized boolean claimOwnerThread() {
        if (ownerThread == null) ownerThread = Thread.currentThread();
        return onOwnerThread();
    }
    // M-2: the spell ledger writes no row (a cast's row is counted at its own capture point), so this gate is the
    // plain readiness check, not a counted skipped capture attempt (CaptureGateSourceScanTest names this exemption).
    // GF-FAKEPLAYER-ID fu2: the listed-player test is the counted isAdmittedPlayer, after the uncounted tests before it.
    private static boolean live(ServerPlayer p) {
        return p != null && p.server != null && p.server.isSameThread() && p.isAlive() && !p.isRemoved()
                && !p.isSpectator() && !NeoForgePlayerProvenance.isSynthetic(p)
                && NeoForgePlayerProvenance.isAdmittedPlayer(p) && p.serverLevel().getEntity(p.getUUID()) == p
                && EventTrailManager.getInstance().isReady();
    }
    private static ItemStack equipment(ServerPlayer player, String slot) {
        if ("spellbook".equals(slot)) return Utils.getPlayerSpellbookStack(player);
        for (EquipmentSlot value : EquipmentSlot.values()) if (value.getName().equals(slot)) return player.getItemBySlot(value);
        return null;
    }
    // R2-inner: the three swallowing helpers fail conservatively on ANY Throwable. A failed check grants no permit
    // (false / null) and the failure is reported to IRONS; a throwable other than RuntimeException or LinkageError
    // (an Error) also invalidates the frames of this thread, every pending cast and every pending effect, because a
    // helper that failed half way through an invalidation or a binding step may have left a permit behind.
    private static boolean safe(BooleanSupplier call) {
        try { return call.getAsBoolean(); } catch (Throwable failure) { failed("bridge:IronsCastAuditBridge.safe", failure); return false; }
    }
    private static <T> T safeGet(Supplier<T> call) {
        try { return call.get(); } catch (Throwable failure) { failed("bridge:IronsCastAuditBridge.safeGet", failure); return null; }
    }
    private static void safeRun(Runnable call) {
        try { call.run(); } catch (Throwable failure) { failed("bridge:IronsCastAuditBridge.safeRun", failure); /* Observer fails closed. */ }
    }
    private static void failed(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.IRONS, site, failure);
        if (!(failure instanceof RuntimeException || failure instanceof LinkageError)) taint();
    }
    private static void taint() {
        Input input = INPUT.get(); if (input != null) input.invalid = true;
        Attempt attempt = ATTEMPT.get(); if (attempt != null) attempt.invalid = true;
        Cast execution = EXECUTION.get(); if (execution != null) execution.invalid = true;
        Cast permit = CALLBACK_PERMIT.get(); if (permit != null) permit.invalid = true;
        Cast callback = CALLBACK.get(); if (callback != null) callback.invalid = true;
        if (onOwnerThread()) {
            for (Cast cast : CASTS.values()) cast.invalid = true;
            for (Effect effect : EFFECTS.values()) effect.invalid = true;
        }
    }
    private static <T> void restore(ThreadLocal<T> local, T value) { if (value == null) local.remove(); else local.set(value); }
    private static final class Input { final ServerPlayer player; boolean invalid, successful; Input(ServerPlayer player) { this.player = player; } }
    private static final class Attempt { Cast cast; boolean invalid; }
    private static final class Cast {
        final Input input; final ServerPlayer player; final ServerLevel level; final MagicData data; final AbstractSpell spell;
        final int spellLevel; final CastSource source; final String slot; final ItemStack stack; final ISpellContainer container;
        final long started = System.nanoTime(), generation; boolean invalid, callbackUsed, executionEntered; int descendants; Entity descendant;
        Cast(Input input, MagicData data, AbstractSpell spell, int spellLevel, CastSource source,
                String slot, ItemStack stack, ISpellContainer container) {
            this.input = input; this.player = input.player; this.level = player.serverLevel(); this.data = data; this.spell = spell;
            this.spellLevel = spellLevel; this.source = source; this.slot = slot; this.stack = stack; this.container = container;
            this.generation = EventTrailManager.getInstance().recordingSnapshot().generation();
        }
        boolean active() { return !invalid && !input.invalid && live(player) && player.serverLevel() == level
                && EventTrailManager.getInstance().recordingSnapshot().generation() == generation
                && withinLifetime(started, System.nanoTime(), MAX_CAST_NANOS); }
        boolean pendingCurrent() { return active() && MagicData.getPlayerMagicData(player) == data && data.isCasting()
                && data.getCastingSpell().getSpell() == spell && data.getCastingSpellLevel() == spellLevel
                && data.getCastSource() == source && slot.equals(data.getCastingEquipmentSlot())
                && equipment(player, slot) == stack && ISpellContainer.get(stack) == container; }
        boolean matches(AbstractSpell spell, Level level, int spellLevel, Entity player, CastSource source) {
            return this.spell == spell && this.level == level && this.spellLevel == spellLevel && this.player == player && this.source == source;
        }
    }
    private static final class Effect {
        final ServerPlayer player; final ServerLevel level; final FireboltProjectile entity; final AbstractSpell spell;
        final Object castIdentity; final long generation; final long started = System.nanoTime(); boolean invalid, admitted;
        Effect(ServerPlayer player, ServerLevel level, FireboltProjectile entity, Cast cast) {
            this.player = player; this.level = level; this.entity = entity; this.spell = cast.spell;
            this.castIdentity = cast.input; this.generation = cast.generation;
        }
        boolean current() { return admitted && !invalid && live(player) && player.serverLevel() == level && entity.level() == level
                && entity.getOwner() == player && !entity.isRemoved() && entity.tickCount <= MAX_PROJECTILE_TICKS
                && EventTrailManager.getInstance().recordingSnapshot().generation() == generation
                && level.getEntity(entity.getUUID()) == entity && withinLifetime(started, System.nanoTime(), MAX_EFFECT_NANOS); }
    }
    private static final class Impact { final Effect effect; final EntityHitResult hit; boolean used;
        Impact(Effect effect, EntityHitResult hit) { this.effect = effect; this.hit = hit; } }
    static boolean withinLifetime(long start, long now, long maximum) { long elapsed = now - start; return elapsed >= 0 && elapsed < maximum; }
}
