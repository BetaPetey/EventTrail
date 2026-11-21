package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.common.entity.EntityEvokerFangs;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Both native fang damage branches remain autonomous/unproven spell effects. */
@Mixin(value = EntityEvokerFangs.class, remap = false)
abstract class ArsEvokerFangsMixin {
    @WrapOperation(method = "damage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private boolean eventtrail$denyUnownedFangDamage(LivingEntity victim, DamageSource source, float damage, Operation<Boolean> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial). The negative frame is
        // closed in a finally after the mod's call, not by try-with-resources (HookGuard class note). If opening the
        // frame fails, the body still makes the mod's call and excludes an accepted hurt, then hands the failure on.
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsEvokerFangsMixin.denyUnownedFangDamage", original,
                once -> {
                    ArsSpellAuditRuntime.DamageBoundary boundary;
                    try {
                        boundary = ArsSpellAuditRuntime.beginDamage(victim, source, null, () -> false);
                    } catch (Throwable frameFailure) {
                        // The guard re-throws a failure of the call as the mod's own exception.
                        try {
                            ArsEngineCreditGuard.excludeAfterAcceptedDamage(victim, once.call());
                        } catch (Throwable alsoFailed) { }
                        throw frameFailure;
                    }
                    try {
                        boolean accepted = once.call();
                        ArsEngineCreditGuard.excludeAfterAcceptedDamage(victim, accepted);
                    } finally {
                        boundary.close();
                    }
                }, victim, source, damage);
    }
}
