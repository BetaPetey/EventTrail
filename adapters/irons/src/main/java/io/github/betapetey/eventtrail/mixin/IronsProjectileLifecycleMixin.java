package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
abstract class IronsProjectileLifecycleMixin {
    @Inject(method = {"setOwner", "readAdditionalSaveData"}, at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$invalidate(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.IRONS, "hook:IronsProjectileLifecycleMixin.invalidate",
                () -> IronsCastAuditBridge.invalidateEntity(this));
    }
}
