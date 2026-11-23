package io.github.betapetey.eventtrail.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
abstract class SablePhysicsPoseAuditMixin {
    @Inject(method = "updatePose", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$beforePose(ServerSubLevel subLevel, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SablePhysicsPoseAuditMixin.beforePose",
                () -> SableNativeAuditHooks.observe(subLevel));
    }

    @Inject(method = "updatePose", at = @At("RETURN"), require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$afterPose(ServerSubLevel subLevel, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SablePhysicsPoseAuditMixin.afterPose",
                () -> SableNativeAuditHooks.observe(subLevel));
    }
}
