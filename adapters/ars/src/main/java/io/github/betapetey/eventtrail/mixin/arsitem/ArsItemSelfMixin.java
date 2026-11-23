package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.method.MethodSelf;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MethodSelf.class, remap = false)
abstract class ArsItemSelfMixin {
    @WrapMethod(method = "onCast", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$itemSelfAir(ItemStack stack, LivingEntity actor, Level world, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemSelfMixin.itemSelfAir", original,
                once -> ArsItemCastAuditBridge.selfBody(resolver, actor, stats, context, once::call),
                stack, actor, world, stats, context, resolver);
    }
    @WrapMethod(method = "onCastOnEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$itemSelfEntity(ItemStack stack, LivingEntity actor, Entity target, InteractionHand hand,
            SpellStats stats, SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemSelfMixin.itemSelfEntity", original,
                once -> ArsItemCastAuditBridge.selfBody(resolver, actor, stats, context, once::call),
                stack, actor, target, hand, stats, context, resolver);
    }
    @WrapMethod(method = "onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$itemSelfBlock(UseOnContext use, SpellStats stats, SpellContext context,
            SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemSelfMixin.itemSelfBlock", original,
                once -> ArsItemCastAuditBridge.selfBody(resolver, use.getPlayer(), stats, context, once::call),
                use, stats, context, resolver);
    }
    @WrapOperation(method = {"onCast", "onCastOnEntity", "onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;"}, at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;onResolveEffect(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;)V"), require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private void eventtrail$itemSelfResolve(SpellResolver resolver, Level world, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemSelfMixin.itemSelfResolve", original,
                once -> ArsItemCastAuditBridge.resolve(resolver, world, hit, once::call), resolver, world, hit);
    }
}
