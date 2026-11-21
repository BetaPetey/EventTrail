package io.github.betapetey.eventtrail.mixin.occultism;

import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageLinkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Link epoch producer. It runs only while the OCCULTISM key is active; {@code OccultismStorageBinding} therefore treats
 * a binding as current only while that key is active, so a skipped bump can never keep a wormhole binding open.
 */
@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.blockentity.StableWormholeBlockEntity", remap = false)
public abstract class OccultismStorageWormholeMixin implements OccultismStorageLinkAccess {
    @Unique private long eventtrail$occultismLinkEpoch;
    @Override public long eventtrail$occultismLinkEpoch() { return eventtrail$occultismLinkEpoch; }
    @Inject(method = {"setLinkedStorageControllerPosition", "loadNetwork", "applyImplicitComponents"},
            at = @At("HEAD"), remap = false, require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private void eventtrail$relink(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.OCCULTISM, "hook:OccultismStorageWormholeMixin.relink", () -> eventtrail$occultismLinkEpoch++);
    }
}
