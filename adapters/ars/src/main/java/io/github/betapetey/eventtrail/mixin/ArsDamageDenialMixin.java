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

/**
 * Exact unchanged native hurt contract when positive Ars admission is unavailable.
 *
 * <p>Reports under {@code ARS_CAST}, the one key of all 20 Ars cast hooks, through the denial entry
 * {@code HookGuard.wrapDenial}: this hook only takes credit away, so its body runs even while {@code ARS_CAST}
 * is OFF or its hook family is stopped (that is exactly when it is applied instead of the positive group). A
 * failure here is contained and reported, and never turns the other denial-only hooks off. The negative frame
 * is closed in a finally after the mod's call, not by try-with-resources, so a {@code close()} failure never
 * becomes a suppressed exception of the mod's own exception (HookGuard class note). If opening the frame fails,
 * the body still makes the mod's call itself and keeps an accepted hurt out of historical credit, then hands the
 * failure to the guard.</p>
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.api.spell.IDamageEffect", remap = false)
interface ArsDamageDenialMixin {
    @WrapOperation(method = "attemptDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    default boolean eventtrail$denyUnadmittedDamage(Entity target, DamageSource source, float amount,
                                                  Operation<Boolean> original) {
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsDamageDenialMixin.denyUnadmittedDamage", original,
                once -> {
                    ArsSpellAuditRuntime.DamageBoundary scope;
                    try {
                        scope = ArsSpellAuditRuntime.beginDamage(target, source, null, () -> false);
                    } catch (Throwable frameFailure) {
                        // No negative frame: still make the mod's call and keep an accepted hurt out of historical
                        // credit, then hand the frame failure to the guard. A failure of the call is the mod's own
                        // exception, which the guard re-throws unchanged; a failed exclusion is the same skip.
                        try {
                            if (once.call() && target instanceof LivingEntity living) ArsEngineCreditGuard.excludeHistoricalCredit(living);
                        } catch (Throwable alsoFailed) { }
                        throw frameFailure;
                    }
                    try {
                        boolean accepted = once.call();
                        if (accepted && target instanceof LivingEntity living) ArsEngineCreditGuard.excludeHistoricalCredit(living);
                    } finally {
                        scope.close();
                    }
                }, target, source, amount);
    }
}
