package io.github.betapetey.eventtrail.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
abstract class SableRapierPhysicsAuditMixin {
    @Inject(method = "teleport", at = {@At("HEAD"), @At("RETURN")}, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$teleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableRapierPhysicsAuditMixin.teleport", () -> {
            if (body instanceof ServerSubLevel subLevel) SableNativeAuditHooks.observe(subLevel);
        });
    }

    @Inject(method = "add(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3dc;)V",
            at = {@At("HEAD"), @At("RETURN")}, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$add(ServerSubLevel subLevel, Pose3dc pose, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableRapierPhysicsAuditMixin.add",
                () -> SableNativeAuditHooks.observe(subLevel));
    }

    @Inject(method = "onStatsChanged", at = {@At("HEAD"), @At("RETURN")}, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$stats(ServerSubLevel subLevel, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableRapierPhysicsAuditMixin.stats",
                () -> SableNativeAuditHooks.observe(subLevel));
    }
}
