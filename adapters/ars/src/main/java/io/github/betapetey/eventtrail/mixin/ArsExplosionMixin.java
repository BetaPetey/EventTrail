package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ars explosions carry vanilla playerExplosion sources, including stored-owner rune casts. */
@Mixin(targets = "com.hollingsworth.arsnouveau.api.util.ANExplosion", remap = false)
abstract class ArsExplosionMixin {
    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$exclude(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial). The negative frame is
        // closed in a finally after the mod's call, not by try-with-resources (HookGuard class note). If opening the
        // frame fails, the body still makes the mod's call and excludes an accepted hurt, then hands the failure on.
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsExplosionMixin.exclude", original,
                once -> {
                    ArsSpellAuditRuntime.DamageBoundary boundary;
                    try {
                        boundary = ArsSpellAuditRuntime.beginDamage(target, source, null, () -> false);
                    } catch (Throwable frameFailure) {
                        // The guard re-throws a failure of the call as the mod's own exception.
                        try {
                            if (once.call() && target instanceof LivingEntity living) ArsEngineCreditGuard.excludeHistoricalCredit(living);
                        } catch (Throwable alsoFailed) { }
                        throw frameFailure;
                    }
                    try {
                        boolean accepted = once.call();
                        if (accepted && target instanceof LivingEntity living) ArsEngineCreditGuard.excludeHistoricalCredit(living);
                    } finally {
                        boundary.close();
                    }
                }, target, source, amount);
    }
}
