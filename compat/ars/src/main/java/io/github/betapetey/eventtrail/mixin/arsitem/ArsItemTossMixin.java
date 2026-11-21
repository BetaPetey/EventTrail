package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.item.inv.ExtractedStack;
import com.hollingsworth.arsnouveau.api.item.inv.InventoryManager;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectToss;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.Predicate;

@Mixin(value = EffectToss.class, remap = false)
abstract class ArsItemTossMixin {
    @WrapMethod(method = "onResolveEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemBody(EntityHitResult hit, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemBody", original,
                once -> ArsItemCastAuditBridge.itemBody((EffectToss) (Object) this, hit, level, actor, stats, context,
                        resolver, once::call),
                hit, level, actor, stats, context, resolver);
    }
    @WrapMethod(method = "onResolveBlock", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemBlockBody(BlockHitResult hit, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemBlockBody", original,
                once -> ArsItemCastAuditBridge.itemBody((EffectToss) (Object) this, hit, level, actor, stats, context,
                        resolver, once::call),
                hit, level, actor, stats, context, resolver);
    }
    @WrapOperation(method = "onResolveBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getCapability(Lnet/neoforged/neoforge/capabilities/BlockCapability;Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Ljava/lang/Object;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private <T, C> T eventtrail$itemHandler(Level level, BlockCapability<T, C> capability, BlockPos position,
            C context, Operation<T> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemHandler", original,
                once -> ArsItemCastAuditBridge.handlerLookup(level, capability, position, context, once::call),
                level, capability, position, context);
    }
    @WrapMethod(method = "lambda$onResolveBlock$2", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$itemHandlerBody(IItemHandler handler, Level level, BlockPos position,
            ExtractedStack extracted, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemHandlerBody", original,
                once -> ArsItemCastAuditBridge.handlerBody(handler, level, position, extracted, once::call),
                handler, level, position, extracted);
    }
    @WrapOperation(method = "lambda$onResolveBlock$2", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItemStacked(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static ItemStack eventtrail$itemHandlerInsert(IItemHandler handler, ItemStack stack, boolean simulate,
            Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemHandlerInsert", original,
                once -> ArsItemCastAuditBridge.handlerInsert(handler, stack, simulate, once::call), handler, stack, simulate);
    }
    @WrapOperation(method = "lambda$onResolveBlock$2", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/item/inv/ExtractedStack;returnOrDrop(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$itemHandlerReturn(ExtractedStack extracted, Level level, BlockPos position,
            Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemHandlerReturn", original,
                once -> ArsItemCastAuditBridge.handlerReturn(extracted, level, position, once::call),
                extracted, level, position);
    }
    @WrapMethod(method = "extractItem", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ExtractedStack eventtrail$itemSource(InventoryManager manager, Predicate<ItemStack> predicate, int count,
            boolean randomized, Operation<ExtractedStack> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemSource", original,
                once -> ArsItemCastAuditBridge.tossExtract(manager, once::call), manager, predicate, count, randomized);
    }
    @WrapOperation(method = "lambda$summonStack$1", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static boolean eventtrail$itemWorld(Level level, Entity entity, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTossMixin.itemWorld", original,
                once -> ArsItemCastAuditBridge.tossSpawn(level, entity, once::call), level, entity);
    }
}
