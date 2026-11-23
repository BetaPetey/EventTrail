package io.github.betapetey.eventtrail.inventory.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Component-exact item identity whose comparison deliberately excludes count.
 */
public final class InventoryItemIdentity {
    private final ItemStack identityStack;
    private final String registryId;
    private final String displayName;

    private InventoryItemIdentity(ItemStack identityStack, String registryId, String displayName) {
        this.identityStack = identityStack;
        this.registryId = registryId;
        this.displayName = displayName;
    }

    public static InventoryItemIdentity of(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Empty stacks do not have an audit identity");
        }

        ItemStack identity = stack.copy();
        identity.setCount(1);
        String registryId = BuiltInRegistries.ITEM.getResourceKey(identity.getItem())
                .map(key -> key.location().toString())
                .orElseThrow(() -> new IllegalArgumentException("Item is not registered"));

        String displayName;
        try {
            displayName = stack.getHoverName().getString();
        } catch (Throwable ignored) {
            // Any failure of a mod's name code (including StackOverflowError,
            // AssertionError or LinkageError) must not lose a transfer capture;
            // this name is only a sort tie-breaker.
            displayName = registryId;
        }
        if (displayName == null || displayName.isBlank()) displayName = registryId;

        return new InventoryItemIdentity(identity, registryId, displayName);
    }

    public String registryId() {
        return registryId;
    }

    public String displayName() {
        return displayName;
    }

    public ItemStack stackWithCount(int count) {
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        ItemStack result = identityStack.copy();
        result.setCount(count);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof InventoryItemIdentity other
                && ItemStack.isSameItemSameComponents(identityStack, other.identityStack);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(identityStack);
    }

    @Override
    public String toString() {
        return registryId;
    }
}
