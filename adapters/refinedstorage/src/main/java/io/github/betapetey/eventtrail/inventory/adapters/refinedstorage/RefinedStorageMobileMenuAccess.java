package io.github.betapetey.eventtrail.inventory.adapters.refinedstorage;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Exact server-menu state exposed by the Refined Storage 2.0.9 mixins. */
public interface RefinedStorageMobileMenuAccess {
    /** Resolves the item that established this menu for the same player. */
    ItemStack eventtrail$resolveAccessItem(Player player);

    /** Returns the durable backing-storage item, or empty when none exists. */
    ItemStack eventtrail$getBackingStorageItem();
}
