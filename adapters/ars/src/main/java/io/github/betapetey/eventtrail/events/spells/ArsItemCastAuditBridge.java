package io.github.betapetey.eventtrail.events.spells;

import com.hollingsworth.arsnouveau.api.item.inv.ExtractedStack;
import com.hollingsworth.arsnouveau.api.item.inv.InventoryManager;
import com.hollingsworth.arsnouveau.api.item.ISpellModifierItem;
import com.hollingsworth.arsnouveau.api.registry.SpellCasterRegistry;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.api.spell.wrapped_caster.PlayerCaster;
import com.hollingsworth.arsnouveau.common.items.SpellBook;
import com.hollingsworth.arsnouveau.common.network.AbstractPacket;
import com.hollingsworth.arsnouveau.common.network.PacketCastSpell;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectPickup;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectToss;
import com.hollingsworth.arsnouveau.common.spell.method.MethodSelf;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.platform.ArsItemEvidenceBridge;
import io.github.betapetey.eventtrail.events.platform.ArsHandlerDepositEvidenceBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Separate synchronous item-family authority. Never grants Harm or projectile ownership. */
public final class ArsItemCastAuditBridge {
    public static final int MAX_CASTS = 1, MAX_PICKUP_TARGETS = 1, MAX_TOSS_RESULTS = 1;
    private static final ThreadLocal<Network> NETWORK = new ThreadLocal<>();
    private static final ThreadLocal<Cast> INPUT = new ThreadLocal<>();
    private static final ThreadLocal<Cast> METHOD = new ThreadLocal<>();
    private static final ThreadLocal<Cast> BODY = new ThreadLocal<>();
    private static final ThreadLocal<Cast> RESOLVE = new ThreadLocal<>();
    private static final ThreadLocal<Cast> RESUME = new ThreadLocal<>();
    private static final ThreadLocal<Frame> EFFECT = new ThreadLocal<>();
    private static final ThreadLocal<Frame> EFFECT_BODY = new ThreadLocal<>();
    private static final ThreadLocal<Frame> HANDLER = new ThreadLocal<>();
    private static final ThreadLocal<Frame> RETURN_CALL = new ThreadLocal<>();
    private static final ThreadLocal<Frame> RETURN_BODY = new ThreadLocal<>();
    static { ArsItemAuditRuntime.register(ArsItemCastAuditBridge::clear, ArsItemCastAuditBridge::logout); }
    private ArsItemCastAuditBridge() { }

    public static void network(AbstractPacket packet, ServerPlayer player, Runnable original) {
        Network old = NETWORK.get(); if (old != null) old.invalid = true;
        Network next = safely(() -> old == null && packet != null && packet.getClass() == PacketCastSpell.class
                && ArsSpellAuditRuntime.live(player) ? new Network(packet, player) : null);
        NETWORK.set(next);
        try { original.run(); } finally { if (next != null) next.invalid = true; restore(NETWORK, old); }
    }

    public static <T> T input(AbstractPacket packet, AbstractCaster<?> caster, Level level, LivingEntity actor,
                              InteractionHand hand, Spell spell, Supplier<T> original) {
        Network network = NETWORK.get(); Cast old = INPUT.get(); if (old != null) old.invalid = true;
        Cast cast = safely(() -> old == null && network != null && !network.invalid && !network.used
                && network.packet == packet && network.player == actor && actor.level() == level && hand != null
                && supported(spell) && caster != null && caster.getClass() == SpellCaster.class
                && actor.getItemInHand(hand).getItem().getClass() == SpellBook.class
                && SpellCasterRegistry.from(actor.getItemInHand(hand)) == caster
                ? new Cast(network, caster, hand, spell) : null);
        if (network != null) network.used = true;
        INPUT.set(cast); boolean normal = false;
        try { T result = original.get(); normal = true; return result; }
        finally {
            boolean completed = normal;
            if (cast != null) completeInput(completed, cast.methodSucceeded, cast::current, cast::finish);
            restore(INPUT, old);
        }
    }

    public static SpellResolver resolver(AbstractCaster<?> caster, SpellContext context, Level level,
            LivingEntity actor, InteractionHand hand, Supplier<SpellResolver> original) {
        SpellResolver result = original.get();
        observe(() -> {
            Cast cast = INPUT.get();
            if (cast != null && cast.resolver == null && cast.caster == caster && cast.player == actor
                    && cast.level == level && cast.hand == hand && cast.inputCurrent()
                    && result != null && result.getClass() == SpellResolver.class && result.spellContext == context
                    && context.getSpell() == cast.spell && context.getPreviousContext() == null
                    && context.getCaster().getClass() == PlayerCaster.class
                    && ((PlayerCaster) context.getCaster()).player == cast.player
                    && ((PlayerCaster) context.getCaster()).livingEntity == cast.player
                    && context.getUnwrappedCaster() == cast.player && context.level == level
                    && context.castingTile == null && !context.isCanceled() && !context.isDelayed()) {
                cast.context = context; cast.resolver = result; cast.wrapped = context.getCaster(); cast.tool = context.getCasterTool();
            }
        });
        return result;
    }

    /** Call site is after native validation, mana and SpellCastEvent cancellation. */
    public static CastResolveType accepted(SpellResolver resolver, AbstractCastMethod method, SpellContext context,
            SpellStats stats, Supplier<CastResolveType> original) {
        return accepted(resolver, method, context, stats, null, original);
    }

    public static CastResolveType acceptedBlock(SpellResolver resolver, AbstractCastMethod method, SpellContext context,
            SpellStats stats, UseOnContext use, Supplier<CastResolveType> original) {
        return accepted(resolver, method, context, stats, use, original);
    }

    private static CastResolveType accepted(SpellResolver resolver, AbstractCastMethod method, SpellContext context,
            SpellStats stats, UseOnContext use, Supplier<CastResolveType> original) {
        Cast old = METHOD.get(); Cast cast = INPUT.get();
        Cast allowed = safely(() -> old == null && cast != null && !cast.accepted && method == cast.methodMember
                && (method == MethodSelf.INSTANCE || method == MethodTouch.INSTANCE && use != null
                && use.getPlayer() == cast.player && use.getLevel() == cast.level && use.getHand() == cast.hand
                && resolver.hitResult instanceof BlockHitResult hit && exactTouchHit(use, hit))
                && cast.resolver == resolver && cast.context == context && context.getCurrentIndex() == 0
                && cast.current() && plain(stats) ? cast : null);
        if (old != null) old.invalid = true;
        if (allowed != null) { allowed.accepted = true; allowed.touchUse = use; }
        METHOD.set(allowed);
        try { CastResolveType result = original.get(); if (allowed != null) allowed.methodSucceeded = result == CastResolveType.SUCCESS; return result; }
        finally { restore(METHOD, old); }
    }

    public static CastResolveType selfBody(SpellResolver resolver, LivingEntity actor, SpellStats stats,
            SpellContext context, Supplier<CastResolveType> original) {
        Cast permit = METHOD.get(); METHOD.remove(); Cast old = BODY.get();
        Cast allowed = safely(() -> old == null && permit != null && permit.current() && permit.resolver == resolver
                && permit.methodMember == MethodSelf.INSTANCE
                && permit.player == actor && permit.context == context && plain(stats) ? permit : null);
        if (old != null) old.invalid = true;
        BODY.set(allowed);
        try { return original.get(); } finally { restore(BODY, old); }
    }

    public static CastResolveType touchBody(SpellResolver resolver, UseOnContext use, SpellStats stats,
            SpellContext context, Supplier<CastResolveType> original) {
        Cast permit = METHOD.get(); METHOD.remove(); Cast old = BODY.get();
        Cast allowed = safely(() -> old == null && permit != null && permit.current() && permit.resolver == resolver
                && permit.methodMember == MethodTouch.INSTANCE && permit.touchUse == use
                && permit.player == use.getPlayer() && permit.level == use.getLevel() && permit.hand == use.getHand()
                && permit.context == context && plain(stats) ? permit : null);
        if (old != null) old.invalid = true;
        BODY.set(allowed);
        try { return original.get(); } finally { restore(BODY, old); }
    }

    static boolean exactTouchHit(UseOnContext use, BlockHitResult hit) {
        return use != null && hit != null && hit.getClass() == BlockHitResult.class && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(use.getClickedPos()) && hit.getDirection() == use.getClickedFace()
                && hit.getLocation().equals(use.getClickLocation());
    }

    /** Only the selected native method member call can authorize its one exact hit. */
    public static void resolve(SpellResolver resolver, Level level, HitResult hit, Runnable original) {
        Cast old = RESOLVE.get(), cast = BODY.get();
        Cast allowed = safely(() -> old == null && cast != null && !cast.resolved && cast.current()
                && cast.resolver == resolver && cast.level == level
                && (cast.methodMember == MethodSelf.INSTANCE && hit instanceof EntityHitResult entity && entity.getEntity() == cast.player
                || cast.methodMember == MethodTouch.INSTANCE && hit instanceof BlockHitResult block && exactTouchHit(cast.touchUse, block))
                ? cast : null);
        if (allowed != null) { allowed.resolved = true; allowed.hit = hit; }
        RESOLVE.set(allowed);
        try { original.run(); } finally { restore(RESOLVE, old); }
    }

    public static void resume(SpellResolver resolver, Level level, Runnable original) {
        Cast old = RESUME.get(), cast = RESOLVE.get();
        Cast allowed = safely(() -> old == null && cast != null && !cast.resumed && cast.current()
                && cast.resolver == resolver && cast.level == level && resolver.hitResult == cast.hit ? cast : null);
        if (allowed != null) allowed.resumed = true;
        RESUME.set(allowed);
        try { original.run(); } finally { restore(RESUME, old); }
    }

    /** Exact selected effect invocation, after native effect cancellation and resolve listeners. */
    public static void effect(AbstractEffect effect, HitResult hit, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Runnable original) {
        Frame old = EFFECT.get(); Cast cast = RESUME.get();
        Frame frame = safely(() -> old == null && cast != null && !cast.effectUsed && cast.current()
                && effect == cast.effectMember && cast.hit == hit && cast.player == actor && cast.level == level
                && cast.context == context && cast.resolver == resolver && context.getCurrentIndex() == 2 && plain(stats)
                ? new Frame(cast, effect, stats) : null);
        if (old != null) old.cast.invalid = true;
        if (frame != null) { cast.effectUsed = true; cast.frame = frame; }
        EFFECT.set(frame); boolean normal = false;
        try { original.run(); normal = true; }
        finally { if (frame != null && !normal) frame.cast.invalid = true; restore(EFFECT, old); }
    }

    public static void itemBody(AbstractEffect effect, HitResult hit, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Runnable original) {
        Frame frame = EFFECT.get(), old = EFFECT_BODY.get();
        Frame allowed = safely(() -> old == null && frame != null && !frame.entered && frame.cast.current()
                && frame.effect == effect && frame.cast.hit == hit && frame.cast.level == level && frame.cast.player == actor
                && frame.stats == stats && frame.cast.context == context && frame.cast.resolver == resolver && plain(stats) ? frame : null);
        if (old != null) old.cast.invalid = true;
        if (allowed != null) allowed.entered = true;
        EFFECT_BODY.set(allowed);
        try { original.run(); } finally { restore(EFFECT_BODY, old); }
    }

    /** Observe the already-native query result; do not perform an EventTrail world scan. */
    public static <T extends Entity> List<T> pickupTargets(Class<T> type, Supplier<List<T>> original) {
        List<T> result = original.get();
        observe(() -> {
            Frame frame = EFFECT.get();
            if (frame != null && frame.effect == EffectPickup.INSTANCE && type == ItemEntity.class) {
                if (frame.queried || result == null || result.size() != MAX_PICKUP_TARGETS) frame.cast.invalid = true;
                else { frame.queried = true; frame.item = (ItemEntity) result.getFirst(); }
            }
        });
        return result;
    }

    public static ItemStack pickupInsert(InventoryManager manager, ItemStack source, Supplier<ItemStack> original) {
        Frame frame = EFFECT.get();
        if (frame != null && frame.pickup != null) frame.cast.invalid = true;
        ArsItemEvidenceBridge.PickupBoundary boundary = safely(() -> frame != null && frame.current()
                && frame.effect == EffectPickup.INSTANCE && frame.queried && frame.item != null && frame.pickup == null
                && source == frame.item.getItem() && mainInventory(manager, frame.cast.player)
                ? ArsItemEvidenceBridge.beginPickup(frame.cast.player, frame.item, source, frame.cast::current) : null);
        if (frame != null && boundary != null) frame.pickup = boundary;
        ItemStack remainder = original.get();
        observe(() -> ArsItemEvidenceBridge.pickupInserted(boundary, remainder));
        return remainder;
    }

    public static void pickupUpdated(ItemEntity item, Runnable original) {
        original.run();
        observe(() -> { Frame frame = EFFECT.get(); if (frame != null && frame.current() && item == frame.item)
            ArsItemEvidenceBridge.pickupSourceUpdated(frame.pickup); });
    }

    public static ExtractedStack tossExtract(InventoryManager manager, Supplier<ExtractedStack> original) {
        Frame frame = EFFECT.get();
        if (frame != null && frame.extractionSeen) frame.cast.invalid = true;
        ArsItemEvidenceBridge.TossBoundary boundary = safely(() -> frame != null && frame.current()
                && frame.cast.methodMember == MethodSelf.INSTANCE && frame.effect == EffectToss.INSTANCE
                && !frame.extractionSeen && mainInventory(manager, frame.cast.player)
                ? ArsItemEvidenceBridge.beginToss(frame.cast.player, frame.cast::current) : null);
        ArsHandlerDepositEvidenceBridge.Boundary deposit = safely(() -> frame != null && frame.current()
                && frame.cast.methodMember == MethodTouch.INSTANCE && frame.effect == EffectToss.INSTANCE && frame.lookupSeen
                && !frame.extractionSeen && mainInventory(manager, frame.cast.player)
                ? ArsHandlerDepositEvidenceBridge.begin(frame.cast.player, frame.cast.level, (BlockHitResult) frame.cast.hit,
                        null, frame.handler, frame.cast::current) : null);
        if (frame != null && boundary != null) frame.toss = boundary;
        if (frame != null && deposit != null) frame.deposit = deposit;
        if (frame != null) frame.extractionSeen = true;
        ExtractedStack result = original.get();
        observe(() -> {
            if (frame != null && (boundary != null || deposit != null) && result != null && result.getClass() == ExtractedStack.class
                    && result.getHandler() == manager.getInventory().getFirst().getHandler()
                    && result.getSlot() >= 0 && result.getSlot() < 36) {
                frame.extracted = result; ArsItemEvidenceBridge.extracted(boundary, result.stack);
                ArsHandlerDepositEvidenceBridge.extracted(deposit, result.stack);
            }
        });
        return result;
    }

    public static boolean tossSpawn(Level level, Entity entity, BooleanSupplier original) {
        Frame frame = EFFECT.get();
        ArsItemEvidenceBridge.TossBoundary boundary = safely(() -> frame != null && frame.current()
                && frame.cast.methodMember == MethodSelf.INSTANCE && frame.effect == EffectToss.INSTANCE
                && frame.extracted != null && level == frame.cast.level
                && entity instanceof ItemEntity item && item.getClass() == ItemEntity.class
                && ItemStack.matches(item.getItem(), frame.extracted.stack) ? frame.toss : null);
        if (entity instanceof ItemEntity item) observe(() -> ArsItemEvidenceBridge.admitting(boundary, item));
        boolean result = original.getAsBoolean();
        if (entity instanceof ItemEntity item) observe(() -> ArsItemEvidenceBridge.admitted(boundary, item, result));
        return result;
    }

    /** Observes the exact capability call Ars already made, including its null side context. */
    public static <T, C> T handlerLookup(Level level, BlockCapability<T, C> capability, BlockPos position,
            C context, Supplier<T> original) {
        T result = original.get();
        observe(() -> {
            Frame frame = EFFECT.get();
            if (frame == null || !frame.current() || frame.cast.methodMember != MethodTouch.INSTANCE
                    || frame.effect != EffectToss.INSTANCE) return;
            if (frame.lookupSeen || capability != (Object) Capabilities.ItemHandler.BLOCK || context != null
                    || level != frame.cast.level || !(frame.cast.hit instanceof BlockHitResult hit)
                    || !hit.getBlockPos().equals(position) || result != null && !(result instanceof IItemHandler)) {
                frame.cast.invalid = true;
                return;
            }
            frame.lookupSeen = true;
            frame.handler = (IItemHandler) result;
        });
        return result;
    }

    /** Exact native consumer member, binding the captured target and extracted object. */
    public static void handlerBody(IItemHandler handler, Level level, BlockPos remainderPosition,
            ExtractedStack extracted, Runnable original) {
        Frame frame = EFFECT.get(), old = HANDLER.get();
        Frame allowed = safely(() -> old == null && frame != null && frame.current()
                && frame.cast.methodMember == MethodTouch.INSTANCE && !frame.handlerUsed && frame.lookupSeen
                && handler != null && handler == frame.handler && extracted != null && extracted == frame.extracted
                && level == frame.cast.level && remainderPosition.equals(remainderPosition(frame)) ? frame : null);
        if (old != null) old.cast.invalid = true;
        if (allowed != null) allowed.handlerUsed = true;
        HANDLER.set(allowed);
        try { original.run(); } finally { restore(HANDLER, old); }
    }

    public static ItemStack handlerInsert(IItemHandler handler, ItemStack input, boolean simulate,
            Supplier<ItemStack> original) {
        Frame frame = HANDLER.get();
        Frame allowed = safely(() -> frame != null && frame.current() && !frame.insertSeen && !simulate
                && handler == frame.handler && frame.extracted.stack == input ? frame : null);
        if (frame != null && allowed == null) frame.cast.invalid = true;
        if (allowed != null) {
            allowed.insertSeen = true;
            observe(() -> ArsHandlerDepositEvidenceBridge.inserting(allowed.deposit, handler, input));
        }
        ItemStack remainder = original.get();
        observe(() -> {
            if (allowed != null) {
                allowed.insertedRemainder = remainder;
                ArsHandlerDepositEvidenceBridge.inserted(allowed.deposit, handler, remainder);
            }
        });
        return remainder;
    }

    /** Call-site token is consumed by the exact ExtractedStack native method body. */
    public static void handlerReturn(ExtractedStack extracted, Level level, BlockPos position, Runnable original) {
        Frame frame = HANDLER.get(), old = RETURN_CALL.get();
        Frame allowed = safely(() -> old == null && frame != null && frame.current() && !frame.returnUsed
                && frame.insertSeen && frame.insertedRemainder != null && extracted == frame.extracted
                && extracted.stack == frame.insertedRemainder && level == frame.cast.level
                && position.equals(remainderPosition(frame)) ? frame : null);
        if (old != null) old.cast.invalid = true;
        if (allowed != null) allowed.returnUsed = true;
        RETURN_CALL.set(allowed);
        try { original.run(); } finally { restore(RETURN_CALL, old); }
    }

    public static void returnBody(ExtractedStack extracted, Level level, BlockPos position, Runnable original) {
        Frame permit = RETURN_CALL.get(); RETURN_CALL.remove(); Frame old = RETURN_BODY.get();
        Frame allowed = safely(() -> old == null && permit != null && permit.current() && !permit.returnEntered
                && extracted == permit.extracted && level == permit.cast.level && position.equals(remainderPosition(permit))
                && extracted.stack == permit.insertedRemainder ? permit : null);
        if (old != null) old.cast.invalid = true;
        if (allowed != null) {
            allowed.returnEntered = true;
            if (extracted.stack.isEmpty()) allowed.finalRemainder = ItemStack.EMPTY;
            observe(() -> ArsHandlerDepositEvidenceBridge.returning(allowed.deposit, extracted.stack));
        }
        RETURN_BODY.set(allowed); boolean normal = false;
        try { original.run(); normal = true; }
        finally {
            if (allowed != null) {
                if (!normal || allowed.finalRemainder == null) allowed.cast.invalid = true;
                else observe(() -> ArsHandlerDepositEvidenceBridge.returned(allowed.deposit, allowed.finalRemainder));
            }
            restore(RETURN_BODY, old);
        }
    }

    /** The native field remains stale after a successful return, so copy the actual result here. */
    public static ItemStack returnedStack(ExtractedStack extracted, Supplier<ItemStack> original) {
        Frame frame = RETURN_BODY.get();
        Frame allowed = safely(() -> frame != null && frame.current() && !frame.returnStackSeen
                && frame.finalRemainder == null && frame.extracted == extracted ? frame : null);
        if (frame != null && allowed == null) frame.cast.invalid = true;
        if (allowed != null) allowed.returnStackSeen = true;
        ItemStack result = original.get();
        observe(() -> { if (allowed != null) allowed.finalRemainder = result.copy(); });
        return result;
    }

    public static boolean remainderSpawn(Level level, Entity entity, BooleanSupplier original) {
        Frame frame = RETURN_BODY.get();
        Frame allowed = safely(() -> frame != null && frame.current() && frame.returnStackSeen
                && frame.finalRemainder != null && !frame.finalRemainder.isEmpty() && level == frame.cast.level
                && entity instanceof ItemEntity item && item.getClass() == ItemEntity.class
                && item.blockPosition().equals(remainderPosition(frame))
                && ItemStack.matches(item.getItem(), frame.finalRemainder) ? frame : null);
        if (frame != null && allowed == null) frame.cast.invalid = true;
        if (allowed != null) observe(() -> ArsHandlerDepositEvidenceBridge.admitting(allowed.deposit, (ItemEntity) entity));
        boolean result = original.getAsBoolean();
        if (allowed != null) observe(() -> ArsHandlerDepositEvidenceBridge.admitted(allowed.deposit, (ItemEntity) entity, result));
        return result;
    }

    private static BlockPos remainderPosition(Frame frame) {
        BlockHitResult hit = (BlockHitResult) frame.cast.hit;
        return hit.getBlockPos().relative(hit.getDirection());
    }

    static boolean supported(Spell spell) {
        return spell != null && spell.getClass() == Spell.class && spell.size() == 2
                && (spell.get(0) == MethodSelf.INSTANCE && (spell.get(1) == EffectPickup.INSTANCE || spell.get(1) == EffectToss.INSTANCE)
                || spell.get(0) == MethodTouch.INSTANCE && spell.get(1) == EffectToss.INSTANCE);
    }
    static boolean recipeCurrent(Spell spell, AbstractCastMethod method, AbstractEffect admittedMember) {
        return supported(spell) && spell.get(0) == method && spell.get(1) == admittedMember;
    }
    static boolean plain(SpellStats stats) {
        return stats != null && stats.getAugments().isEmpty() && stats.getModifierItems().size() <= 128
                && stats.getModifierItems().stream().noneMatch(stack -> stack == null || !stack.isEmpty() && stack.getItem() instanceof ISpellModifierItem)
                && stats.getAoeMultiplier() == 0 && stats.getAmpMultiplier() == 0 && !stats.isRandomized()
                && !stats.isSensitive() && stats.getAccMultiplier() == 0 && stats.getDamageModifier() == 0
                && stats.getDurationMultiplier() == 0;
    }
    private static boolean mainInventory(InventoryManager manager, ServerPlayer player) {
        return manager != null && manager.getClass() == InventoryManager.class && manager.getInventory().size() == 1
                && manager.getInventory().getFirst().getHandler().getClass() == PlayerMainInvWrapper.class
                && ((PlayerMainInvWrapper) manager.getInventory().getFirst().getHandler()).getInventoryPlayer() == player.getInventory()
                && manager.getInventory().getFirst().getHandler().getSlots() == 36;
    }
    public static void clear() {
        Cast cast = INPUT.get(); if (cast != null) { cast.invalid = true; observe(() -> cast.finish(false)); }
        Network network = NETWORK.get(); if (network != null) network.invalid = true;
        NETWORK.remove(); INPUT.remove(); METHOD.remove(); BODY.remove(); RESOLVE.remove(); RESUME.remove(); EFFECT.remove(); EFFECT_BODY.remove();
        HANDLER.remove(); RETURN_CALL.remove(); RETURN_BODY.remove();
    }
    private static void logout(UUID id) { Cast cast = INPUT.get(); if (cast != null && cast.player.getUUID().equals(id)) cast.invalid = true; }
    public static int retainedAuthority() { return NETWORK.get() == null && INPUT.get() == null && METHOD.get() == null && BODY.get() == null
            && RESOLVE.get() == null && RESUME.get() == null && EFFECT.get() == null && EFFECT_BODY.get() == null
            && HANDLER.get() == null && RETURN_CALL.get() == null && RETURN_BODY.get() == null ? 0 : 1; }
    /** A failed final provider/currentness lookup must still consume the pending boundary. */
    static void completeInput(boolean normal, boolean methodSucceeded, BooleanSupplier current, Consumer<Boolean> finish) {
        boolean valid = Boolean.TRUE.equals(safely(() -> normal && methodSucceeded && current.getAsBoolean()));
        observe(() -> finish.accept(valid));
    }
    // R2-inner: both swallowing helpers fail conservatively on ANY Throwable and report to ARS_ITEM. A failed lookup
    // grants no permit (null); a failed observation invalidates the current cast; a throwable other than
    // RuntimeException or LinkageError (an Error) also invalidates the packet, so nothing later binds to this cast.
    private static <T> T safely(Supplier<T> call) {
        try { return call.get(); } catch (Throwable failure) { failed("bridge:ArsItemCastAuditBridge.safely", failure); return null; }
    }
    private static void observe(Runnable call) {
        try { call.run(); } catch (Throwable failure) {
            failed("bridge:ArsItemCastAuditBridge.observe", failure);
            Cast cast = INPUT.get(); if (cast != null) cast.invalid = true;
        }
    }
    private static void failed(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.ARS_ITEM, site, failure);
        if (!(failure instanceof RuntimeException || failure instanceof LinkageError)) taint();
    }
    private static void taint() {
        Network network = NETWORK.get(); if (network != null) network.invalid = true;
        Cast cast = INPUT.get(); if (cast != null) cast.invalid = true;
    }
    private static <T> void restore(ThreadLocal<T> local, T previous) { if (previous == null) local.remove(); else local.set(previous); }
    private static final class Network {
        final AbstractPacket packet; final ServerPlayer player; boolean invalid, used;
        Network(AbstractPacket packet, ServerPlayer player) { this.packet = packet; this.player = player; }
    }
    private static final class Cast {
        final Network network; final ServerPlayer player; final ServerLevel level; final AbstractCaster<?> caster;
        final InteractionHand hand; final ItemStack book; final Spell spell; final AbstractEffect effectMember;
        final AbstractCastMethod methodMember;
        final long generation, started = System.nanoTime();
        SpellResolver resolver; SpellContext context; Object wrapped; ItemStack tool; HitResult hit; Frame frame; UseOnContext touchUse;
        boolean invalid, accepted, methodSucceeded, resolved, resumed, effectUsed;
        Cast(Network network, AbstractCaster<?> caster, InteractionHand hand, Spell spell) {
            this.network = network; player = network.player; level = player.serverLevel(); this.caster = caster; this.hand = hand;
            book = player.getItemInHand(hand); this.spell = spell; effectMember = (AbstractEffect) spell.get(1);
            methodMember = (AbstractCastMethod) spell.get(0);
            generation = EventTrailManager.getInstance().recordingSnapshot().generation();
        }
        boolean inputCurrent() {
            return !invalid && INPUT.get() == this && NETWORK.get() == network && !network.invalid
                    && ArsSpellAuditRuntime.live(player) && player.serverLevel() == level && player.getItemInHand(hand) == book
                    && SpellCasterRegistry.from(book) == caster && EventTrailManager.getInstance().recordingSnapshot().generation() == generation
                    && System.nanoTime() - started >= 0 && System.nanoTime() - started < 1_000_000_000L;
        }
        boolean current() {
            return inputCurrent() && recipeCurrent(spell, methodMember, effectMember) && resolver != null && resolver.getClass() == SpellResolver.class
                    && resolver.spellContext == context && resolver.spell == spell && resolver.castType == methodMember
                    && resolver.previousResolver == null && context.getSpell() == spell && context.level == level
                    && context.getPreviousContext() == null && context.getCaster() == wrapped && wrapped instanceof PlayerCaster p
                    && p.getClass() == PlayerCaster.class && p.player == player && p.livingEntity == player
                    && context.getUnwrappedCaster() == player && context.getCasterTool() == tool
                    && context.castingTile == null && !context.isCanceled() && !context.isDelayed();
        }
        void finish(boolean normal) {
            Frame pending = frame; frame = null;
            if (pending == null) return;
            try {
                if (normal) {
                    observe(() -> ArsItemEvidenceBridge.finishPickup(pending.pickup));
                    observe(() -> ArsItemEvidenceBridge.finishToss(pending.toss));
                    observe(() -> ArsHandlerDepositEvidenceBridge.finish(pending.deposit));
                }
            } finally {
                observe(() -> ArsItemEvidenceBridge.abortPickup(pending.pickup));
                observe(() -> ArsItemEvidenceBridge.abortToss(pending.toss));
                observe(() -> ArsHandlerDepositEvidenceBridge.abort(pending.deposit));
            }
        }
    }
    private static final class Frame {
        final Cast cast; final AbstractEffect effect; final SpellStats stats; boolean queried, entered;
        ItemEntity item; ExtractedStack extracted; ArsItemEvidenceBridge.PickupBoundary pickup; ArsItemEvidenceBridge.TossBoundary toss;
        ArsHandlerDepositEvidenceBridge.Boundary deposit;
        IItemHandler handler; ItemStack insertedRemainder, finalRemainder;
        boolean lookupSeen, extractionSeen, handlerUsed, insertSeen, returnUsed, returnEntered, returnStackSeen;
        Frame(Cast cast, AbstractEffect effect, SpellStats stats) { this.cast = cast; this.effect = effect; this.stats = stats; }
        boolean current() { return EFFECT.get() == this && EFFECT_BODY.get() == this && cast.current() && cast.context.getCurrentIndex() == 2 && plain(stats); }
    }
}
