package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectHarm;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = EffectHarm.class, remap = false)
abstract class ArsHarmMixin {
    @WrapMethod(method = "onResolveEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$harm(EntityHitResult hit, Level level, LivingEntity player, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsHarmMixin.harm", original,
                once -> ArsCastAuditBridge.harmBody(hit, level, player, stats, context, resolver, once::call),
                hit, level, player, stats, context, resolver);
    }
    @WrapOperation(method = "onResolveEntity", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/common/spell/effect/EffectHarm;attemptDamage(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;F)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$attempt(EffectHarm effect, Level level, LivingEntity player, SpellStats stats,
            SpellContext context, SpellResolver resolver, Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsHarmMixin.attempt", original,
                once -> ArsCastAuditBridge.attemptDamage(level, player, stats, context, resolver, target, once::call),
                effect, level, player, stats, context, resolver, target, source, amount);
    }
}
