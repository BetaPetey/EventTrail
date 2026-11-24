package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.common.entity.LightningEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact Ars thunder callback; its cached vanilla damage source contains no Ars identity. */
@Mixin(value = LightningEntity.class, remap = false)
abstract class ArsLightningEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;thunderHit(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LightningBolt;)V"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$denyUnownedThunderHit(Entity victim, ServerLevel level, LightningBolt lightning, Operation<Void> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial). The negative frame is
        // closed in a finally right after the mod's call, not by try-with-resources, and the credit check still runs
        // after it on every path (HookGuard class note). If opening the frame fails, the body still makes the mod's
        // call, so the credit check runs after it, then hands the failure on.
        HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsLightningEntityMixin.denyUnownedThunderHit", original,
                once -> {
                    DamageSource source = null;
                    Integer fireBefore = null;
                    Float healthBefore = null;
                    try {
                        source = level.damageSources().lightningBolt();
                        fireBefore = victim.getRemainingFireTicks();
                        if (victim instanceof LivingEntity living) healthBefore = living.getHealth();
                    } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                        AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsLightningEntityMixin.denyUnownedThunderHit", failure);
                    }
                    try {
                        ArsSpellAuditRuntime.DamageBoundary boundary;
                        try {
                            boundary = ArsSpellAuditRuntime.beginDamage(victim, source, null, () -> false);
                        } catch (Throwable frameFailure) {
                            // The guard re-throws a failure of the call as the mod's own exception.
                            try {
                                once.call();
                            } catch (Throwable alsoFailed) { }
                            throw frameFailure;
                        }
                        try {
                            once.call();
                        } finally {
                            boundary.close();
                        }
                    } finally {
                        try {
                            if (victim instanceof LivingEntity living && living.level() instanceof ServerLevel serverLevel
                                    && serverLevel.getServer().isSameThread()
                                    && (fireBefore != null && ArsEngineCreditGuard.changedPositiveFire(fireBefore, victim.getRemainingFireTicks())
                                    || healthBefore != null && living.getHealth() < healthBefore)) {
                                ArsEngineCreditGuard.excludeHistoricalCredit(living);
                            }
                        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                            AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsLightningEntityMixin.denyUnownedThunderHit", failure);
                        }
                    }
                }, victim, level, lightning);
    }
}
