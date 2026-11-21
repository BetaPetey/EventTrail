package io.github.betapetey.eventtrail.mixin;

import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.storage.DiskInventory;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.refinedstorage.RefinedStorageMobileMenuAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the exact portable Grid item and its installed durable disk. */
@Pseudo
@Mixin(
        targets = "com.refinedmods.refinedstorage.common.storage.portablegrid."
                + "PortableGridItemContainerMenu",
        remap = false)
abstract class RefinedStoragePortableGridItemAccessMixin
        implements RefinedStorageMobileMenuAccess {
    @Unique
    private SlotReference eventtrail$accessItemReference;
    @Unique
    private DiskInventory eventtrail$diskInventory;

    @Inject(
            method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
                    + "Lcom/refinedmods/refinedstorage/common/storage/DiskInventory;"
                    + "Lcom/refinedmods/refinedstorage/common/api/grid/Grid;"
                    + "Lcom/refinedmods/refinedstorage/api/network/energy/EnergyStorage;"
                    + "Lcom/refinedmods/refinedstorage/common/api/support/slotreference/"
                    + "SlotReference;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$capturePortableBacking(
            int containerId,
            Inventory inventory,
            DiskInventory diskInventory,
            Grid grid,
            EnergyStorage energyStorage,
            SlotReference slotReference,
            CallbackInfo callback) {
        HookGuard.observe(AdapterKey.REFINED_STORAGE, "hook:RefinedStoragePortableGridItemAccessMixin.capturePortableBacking", () -> {
            eventtrail$diskInventory = diskInventory;
            eventtrail$accessItemReference = slotReference;
        });
    }

    @Override
    public ItemStack eventtrail$resolveAccessItem(Player player) {
        if (player == null || eventtrail$accessItemReference == null) return ItemStack.EMPTY;
        return eventtrail$accessItemReference.resolve(player).orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack eventtrail$getBackingStorageItem() {
        if (eventtrail$diskInventory == null || eventtrail$diskInventory.getContainerSize() != 1) {
            return ItemStack.EMPTY;
        }
        return eventtrail$diskInventory.getItem(0);
    }
}
