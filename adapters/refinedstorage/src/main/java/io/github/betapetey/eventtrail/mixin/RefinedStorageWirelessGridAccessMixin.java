package io.github.betapetey.eventtrail.mixin;

import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
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

/** Captures the exact item slot that opened a Refined Storage wireless Grid. */
@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.common.grid.WirelessGridContainerMenu",
        remap = false)
abstract class RefinedStorageWirelessGridAccessMixin
        implements RefinedStorageMobileMenuAccess {
    @Unique
    private SlotReference eventtrail$accessItemReference;

    @Inject(
            method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
                    + "Lcom/refinedmods/refinedstorage/common/api/grid/Grid;"
                    + "Lcom/refinedmods/refinedstorage/common/api/support/slotreference/"
                    + "SlotReference;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$captureAccessItem(
            int containerId,
            Inventory inventory,
            Grid grid,
            SlotReference slotReference,
            CallbackInfo callback) {
        HookGuard.observe(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageWirelessGridAccessMixin.captureAccessItem",
                () -> eventtrail$accessItemReference = slotReference);
    }

    @Override
    public ItemStack eventtrail$resolveAccessItem(Player player) {
        if (player == null || eventtrail$accessItemReference == null) return ItemStack.EMPTY;
        return eventtrail$accessItemReference.resolve(player).orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack eventtrail$getBackingStorageItem() {
        return ItemStack.EMPTY;
    }
}
