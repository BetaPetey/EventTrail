package io.github.betapetey.eventtrail.mixin;

import com.refinedmods.refinedstorage.common.storage.portablegrid.AbstractPortableGridBlockEntity;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.refinedstorage.RefinedStoragePhysicalMenuAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact access block supplied to an in-world Portable Grid menu. */
@Pseudo
@Mixin(
        targets = "com.refinedmods.refinedstorage.common.storage.portablegrid."
                + "PortableGridBlockContainerMenu",
        remap = false)
abstract class RefinedStoragePortableGridBlockTargetMixin
        implements RefinedStoragePhysicalMenuAccess {
    @Unique
    private BlockEntity eventtrail$accessBlockEntity;

    @Inject(
            method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
                    + "Lcom/refinedmods/refinedstorage/common/storage/portablegrid/"
                    + "AbstractPortableGridBlockEntity;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$captureAccessBlock(
            int containerId,
            Inventory inventory,
            AbstractPortableGridBlockEntity blockEntity,
            CallbackInfo callback) {
        HookGuard.observe(AdapterKey.REFINED_STORAGE, "hook:RefinedStoragePortableGridBlockTargetMixin.captureAccessBlock",
                () -> eventtrail$accessBlockEntity = blockEntity);
    }

    @Override
    public BlockEntity eventtrail$getAccessBlockEntity() {
        return eventtrail$accessBlockEntity;
    }
}
