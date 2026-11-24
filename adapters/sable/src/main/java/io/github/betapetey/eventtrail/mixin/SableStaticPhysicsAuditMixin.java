package io.github.betapetey.eventtrail.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.physics.impl.none.StaticPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StaticPhysicsPipeline.class, remap = false)
abstract class SableStaticPhysicsAuditMixin {
    @Inject(method = "teleport", at = {@At("HEAD"), @At("RETURN")}, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$teleport(PhysicsPipelineBody body, Vector3dc position, Quaterniondc orientation, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableStaticPhysicsAuditMixin.teleport", () -> {
            if (body instanceof ServerSubLevel subLevel) SableNativeAuditHooks.observe(subLevel);
        });
    }
}
