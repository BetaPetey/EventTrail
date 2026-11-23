package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractMagicProjectile.class, remap = false)
abstract class IronsMagicProjectileMixin {
    @WrapMethod(method = "tick", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$tick(Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsMagicProjectileMixin.tick", original,
                once -> IronsCastAuditBridge.projectileTick((Entity) (Object) this, once::call));
    }
    @WrapOperation(method = "handleHitDetection", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/entity/spells/AbstractMagicProjectile;onHit(Lnet/minecraft/world/phys/HitResult;)V"),
            require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$hit(AbstractMagicProjectile entity, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsMagicProjectileMixin.hit", original,
                once -> IronsCastAuditBridge.hit(entity, hit, once::call), entity, hit);
    }
}
