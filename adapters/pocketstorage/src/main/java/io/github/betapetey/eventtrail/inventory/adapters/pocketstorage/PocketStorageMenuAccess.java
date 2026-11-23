package io.github.betapetey.eventtrail.inventory.adapters.pocketstorage;

import net.minecraft.world.entity.player.Inventory;
import java.util.UUID;

/** Exact native menu fields plus its captured item-opening authority. */
public interface PocketStorageMenuAccess {
    Inventory eventtrail$pocketPlayerInventory();
    UUID eventtrail$pocketUuid();
    PocketStorageBinding eventtrail$pocketBinding();
    void eventtrail$pocketBinding(PocketStorageBinding binding);
}
