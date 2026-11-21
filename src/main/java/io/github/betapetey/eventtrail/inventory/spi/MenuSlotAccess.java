package io.github.betapetey.eventtrail.inventory.spi;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Supplier;

/** Adapter-proven bounded access to one real menu backing slot. */
public record MenuSlotAccess(
        Object backingIdentity,
        int backingSlotIndex,
        Supplier<ItemStack> stackReader) {
    public MenuSlotAccess {
        backingIdentity = Objects.requireNonNull(backingIdentity, "backingIdentity");
        stackReader = Objects.requireNonNull(stackReader, "stackReader");
        if (backingSlotIndex < 0) {
            throw new IllegalArgumentException("backingSlotIndex must not be negative");
        }
    }
}
