package io.github.betapetey.eventtrail.inventory.spi;

import net.minecraft.world.inventory.Slot;

import java.util.Objects;

/**
 * One proven server menu slot that is not present in Minecraft's ordinary
 * {@code AbstractContainerMenu.slots} list.
 *
 * <p>Some mod menus assign valid packet indices to separately synchronized
 * slots. Exact adapters may expose those slots here; generic discovery would
 * risk treating ghost or configuration controls as real item custody.</p>
 */
public record MenuObservedSlot(int menuSlotIndex, Slot slot) {
    public MenuObservedSlot {
        if (menuSlotIndex < 0) throw new IllegalArgumentException("menuSlotIndex");
        Objects.requireNonNull(slot, "slot");
    }
}
