package io.github.betapetey.eventtrail.mixin;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.location.sable.SableNativeAuditHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
abstract class SableAssemblyAuditMixin {
    @Inject(method = "assembleBlocks", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$invalidateSourceFrame(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks,
            BoundingBox3ic bounds, CallbackInfoReturnable<ServerSubLevel> callback) {
        // A failed plot or sub-level lookup makes every frame non-current before the guard sees the failure.
        HookGuard.observe(AdapterKey.SABLE_FRAME, "hook:SableAssemblyAuditMixin.invalidateSourceFrame",
                () -> SableNativeAuditHooks.invalidateSourceFrame(level, anchor));
    }
}
