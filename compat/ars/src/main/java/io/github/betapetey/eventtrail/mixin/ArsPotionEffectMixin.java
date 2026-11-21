package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.IPotionEffect;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The exact default method used by Harm's timed poison and Wither glyphs. */
@Mixin(value = IPotionEffect.class, remap = false)
interface ArsPotionEffectMixin {
    @WrapOperation(method = "applyPotion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    default boolean eventtrail$observeCommittedDamageStatus(
            LivingEntity victim, MobEffectInstance requested, Operation<Boolean> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial).
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsPotionEffectMixin.observeCommittedDamageStatus", original,
                once -> {
                    boolean accepted = once.call();
                    if (accepted) {
                        try {
                            if (ArsEngineCreditGuard.isDamageStatus(requested) && victim.level() instanceof ServerLevel level
                                    && level.getServer().isSameThread()) {
                                MobEffectInstance current = victim.getEffect(requested.getEffect());
                                if (current != null && current.getDuration() != 0) {
                                    ArsEngineCreditGuard.excludeHistoricalCredit(victim);
                                }
                            }
                        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                            AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsPotionEffectMixin.observeCommittedDamageStatus", failure);
                        }
                    }
                }, victim, requested);
    }

}
