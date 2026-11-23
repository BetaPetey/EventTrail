package io.github.betapetey.eventtrail.inventory.spi;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Server-thread facts available while selecting the sole menu adapter. */
public record MenuAdapterContext(
        AbstractContainerMenu menu,
        ServerPlayer player,
        boolean boundedPhysicalCandidate) {
}
