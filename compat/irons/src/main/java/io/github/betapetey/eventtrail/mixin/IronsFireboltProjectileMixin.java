package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.entity.spells.firebolt.FireboltProjectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = FireboltProjectile.class, remap = false)
abstract class IronsFireboltProjectileMixin {
    @WrapMethod(method = "onHitEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$impact(EntityHitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsFireboltProjectileMixin.impact", original,
                once -> IronsCastAuditBridge.impact((Entity) (Object) this, hit, once::call), hit);
    }
    @WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/damage/DamageSources;applyDamage(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/damagesource/DamageSource;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$damage(Entity target, float damage, DamageSource source, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsFireboltProjectileMixin.damage", original,
                once -> IronsCastAuditBridge.projectileDamage((Entity) (Object) this, target, source, once::call),
                target, damage, source);
    }
}
