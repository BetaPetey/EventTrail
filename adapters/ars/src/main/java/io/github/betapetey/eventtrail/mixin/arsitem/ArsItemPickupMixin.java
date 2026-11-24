package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.item.inv.InventoryManager;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectPickup;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Mixin(value = EffectPickup.class, remap = false)
abstract class ArsItemPickupMixin {
    @WrapMethod(method = "onResolve", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemBody(HitResult hit, Level level, LivingEntity actor, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemPickupMixin.itemBody", original,
                once -> ArsItemCastAuditBridge.itemBody((EffectPickup) (Object) this, hit, level, actor, stats, context,
                        resolver, once::call),
                hit, level, actor, stats, context, resolver);
    }
    @WrapOperation(method = "onResolve", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"), require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private <T extends Entity> List<T> eventtrail$itemTargets(Level level, Class<T> type, AABB box, Operation<List<T>> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemPickupMixin.itemTargets", original,
                once -> ArsItemCastAuditBridge.pickupTargets(type, once::call), level, type, box);
    }
    @WrapOperation(method = "onResolve", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/item/inv/InventoryManager;insertStack(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$itemInsert(InventoryManager manager, ItemStack stack, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemPickupMixin.itemInsert", original,
                once -> ArsItemCastAuditBridge.pickupInsert(manager, stack, once::call), manager, stack);
    }
    @WrapOperation(method = "onResolve", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;setItem(Lnet/minecraft/world/item/ItemStack;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemRemainder(ItemEntity entity, ItemStack remainder, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemPickupMixin.itemRemainder", original,
                once -> ArsItemCastAuditBridge.pickupUpdated(entity, once::call), entity, remainder);
    }
}
