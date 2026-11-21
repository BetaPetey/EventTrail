package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Mixin(value = SubLevelContainer.class, remap = false)
abstract class SableSubLevelContainerAuditMixin {
    @WrapOperation(method = "allocateSubLevel", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private Object eventtrail$observeAllocation(Map<?, ?> owners, Object uuid, Object added, Operation<Object> original) {
        return HookGuard.wrap(AdapterKey.SABLE_FRAME, "hook:SableSubLevelContainerAuditMixin.observeAllocation", original,
                once -> {
                    Object prior = once.call();
                    if (prior instanceof SubLevel previous && added instanceof SubLevel allocated)
                        SableNativeAuditHooks.duplicate(previous, allocated);
                }, owners, uuid, added);
    }
}
