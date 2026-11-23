package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MethodTouch.class, remap = false)
abstract class ArsTouchMixin {
    @WrapMethod(method = "onCastOnEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$touch(ItemStack stack, LivingEntity player, Entity target, InteractionHand hand,
            SpellStats stats, SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsTouchMixin.touch", original,
                once -> ArsCastAuditBridge.methodBody(resolver, player, stats, false, once::call),
                stack, player, target, hand, stats, context, resolver);
    }
    @WrapOperation(method = "onCastOnEntity", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;onResolveEffect(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$resolve(SpellResolver resolver, Level level, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsTouchMixin.resolve", original,
                once -> ArsCastAuditBridge.resolve(resolver, level, hit, null, once::call), resolver, level, hit);
    }
}
