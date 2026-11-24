package io.github.betapetey.eventtrail.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableAuditCarrier;
import io.github.betapetey.eventtrail.events.location.sable.SableAuditState;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SubLevel.class, remap = false)
abstract class SableSubLevelAuditMixin implements SableAuditCarrier {
    @Unique private final SableAuditState eventtrail$sableState = new SableAuditState();

    @Override public SableAuditState eventtrail$sableAuditState() { return eventtrail$sableState; }

    @Inject(method = "logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;", at = @At("RETURN"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$observePoseRead(CallbackInfoReturnable<Pose3d> callback) {
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableSubLevelAuditMixin.observePoseRead",
                () -> SableNativeAuditHooks.observe((SubLevel) (Object) this, callback.getReturnValue()));
    }
}
