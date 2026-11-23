package io.github.betapetey.eventtrail.mixin.occultism;

import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageRemoteAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.container.storage.StorageRemoteContainer", remap = false)
public abstract class OccultismStorageRemoteMenuMixin implements OccultismStorageRemoteAccess {
    @Shadow protected int selectedSlot;
    @Override public int eventtrail$occultismSelectedSlot() { return selectedSlot; }
}
