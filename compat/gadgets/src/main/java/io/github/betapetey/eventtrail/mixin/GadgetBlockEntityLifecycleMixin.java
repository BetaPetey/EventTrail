package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * O(1) invalidation of an already-owned placeholder; never discovers or records a world change.
 *
 * <p>Shared by both gadget mods (the plugin applies it when either is admitted). Keyed MINING_GADGETS: it is the
 * only {@code setRemoved} hook that reaches a Mining Gadgets placeholder, while a Building Gadgets placeholder is
 * also invalidated by {@code BuildingGadgetsRenderAuditMixin}'s own {@code setRemoved} hook under BUILDING_GADGETS. H's hook-to-key
 * table wins over this lane choice (lead decision L14 F4, L21 P7).</p>
 */
@Mixin(BlockEntity.class)
abstract class GadgetBlockEntityLifecycleMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$discardRemovedGadgetMember(CallbackInfo ignored) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:GadgetBlockEntityLifecycleMixin.discardRemovedGadgetMember",
                () -> GadgetAuditRuntime.invalidate(this));
    }
}
