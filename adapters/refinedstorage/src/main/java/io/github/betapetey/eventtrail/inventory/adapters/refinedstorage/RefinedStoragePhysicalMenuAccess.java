package io.github.betapetey.eventtrail.inventory.adapters.refinedstorage;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact server-constructor capture of the physical RS grid access block. */
public interface RefinedStoragePhysicalMenuAccess {
    BlockEntity eventtrail$getAccessBlockEntity();
}
