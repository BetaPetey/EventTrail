package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.common.entity.EntityChimeraProjectile;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native Chimera damage can deliberately discard its owner's identity. */
@Mixin(value = EntityChimeraProjectile.class, remap = false)
abstract class ArsChimeraProjectileMixin {
    @WrapMethod(method = "onHitEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$observeRetainedFire(EntityHitResult hit, Operation<Void> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial).
        HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsChimeraProjectileMixin.observeRetainedFire", original,
                once -> {
                    Entity victim = null;
                    Integer before = null;
                    try { victim = hit.getEntity(); before = victim.getRemainingFireTicks(); }
                    catch (RuntimeException | LinkageError | StackOverflowError failure) {
                        AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsChimeraProjectileMixin.observeRetainedFire", failure);
                    }
                    try { once.call(); }
                    finally {
                        // The native failed-hurt branch restores the old fire count. Observe
                        // the complete callback so that rollback cannot become exclusion.
                        try {
                            if (before != null && victim instanceof LivingEntity living
                                    && living.level() instanceof ServerLevel level && level.getServer().isSameThread()
                                    && ArsEngineCreditGuard.changedPositiveFire(before, victim.getRemainingFireTicks())) {
                                ArsEngineCreditGuard.excludeHistoricalCredit(living);
                            }
                        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                            AdapterFailSafe.report(AdapterKey.ARS_CAST, "hook:ArsChimeraProjectileMixin.observeRetainedFire", failure);
                        }
                    }
                }, hit);
    }

    @WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$denyUnownedChimeraDamage(Entity victim, DamageSource source, float damage, Operation<Boolean> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial). The negative frame is
        // closed in a finally after the mod's call, not by try-with-resources (HookGuard class note). If opening the
        // frame fails, the body still makes the mod's call and excludes an accepted hurt, then hands the failure on.
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsChimeraProjectileMixin.denyUnownedChimeraDamage", original,
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
