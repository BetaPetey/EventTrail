package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.common.spell.effect.EffectIgnite;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes only the native entity ignition mutation; no fire or damage is added. */
@Mixin(value = EffectIgnite.class, remap = false)
abstract class ArsIgniteMixin {
    @WrapOperation(method = "onResolveEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$observeCommittedIgnition(Entity target, int ticks, Operation<Void> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial).
        HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsIgniteMixin.observeCommittedIgnition", original,
                once -> {
                    Integer before = null;
                    try { before = target.getRemainingFireTicks(); }
                    catch (RuntimeException | LinkageError | StackOverflowError failure) {
                        AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsIgniteMixin.observeCommittedIgnition", failure);
                    }
                    once.call();
                    try {
                        if (before != null && target instanceof LivingEntity victim
                                && victim.level() instanceof ServerLevel level && level.getServer().isSameThread()
                                && ArsEngineCreditGuard.changedPositiveFire(before, target.getRemainingFireTicks())) {
                            ArsEngineCreditGuard.excludeHistoricalCredit(victim);
                        }
                    } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                        AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsIgniteMixin.observeCommittedIgnition", failure);
                    }
                }, target, ticks);
    }

}
