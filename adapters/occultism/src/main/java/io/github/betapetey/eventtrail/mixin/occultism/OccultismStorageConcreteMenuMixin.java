package io.github.betapetey.eventtrail.mixin.occultism;

import com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOpening;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {
        "com.klikli_dev.occultism.common.container.storage.StorageControllerContainer",
        "com.klikli_dev.occultism.common.container.storage.StableWormholeContainer",
        "com.klikli_dev.occultism.common.container.storage.StorageRemoteContainer"}, remap = false)
public abstract class OccultismStorageConcreteMenuMixin {
    @Inject(method = "<init>", at = @At("TAIL"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$bind(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.OCCULTISM, "hook:OccultismStorageConcreteMenuMixin.bind",
                () -> OccultismStorageOpening.bind((StorageControllerContainerBase) (Object) this));
    }
}
