package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = IDamageEffect.class, remap = false)
interface ArsDamageEffectMixin {
    @WrapMethod(method = "attemptDamage", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    default boolean eventtrail$body(Level level, LivingEntity player, SpellStats stats, SpellContext context,
            SpellResolver resolver, Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsDamageEffectMixin.body", original,
                once -> ArsCastAuditBridge.damageBody((IDamageEffect) this, level, player, stats, context, resolver, target,
                        once::call),
                level, player, stats, context, resolver, target, source, amount);
    }
    @WrapOperation(method = "attemptDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    default boolean eventtrail$committedDamage(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsDamageEffectMixin.committedDamage", original,
                once -> ArsCastAuditBridge.damage(target, source, once::call), target, source, amount);
    }
}
