package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.world.item.ItemStack;

/**
 * The one "same stack, changed components" rule that GF-IDENTITYNORM and GF-NESTED share.
 *
 * <p>Item identity stays component-exact everywhere else. These two checks only say when two different identities, or
 * the before and after stacks of one slot, are still the same item: GF-IDENTITYNORM's volatile-component pair and
 * GF-NESTED's shell (a container item whose contents changed in place) both start from them.</p>
 */
public final class SameStackRule {
    private SameStackRule() {
    }

    /** Both identities are the same registered item; their components are not compared. */
    public static boolean sameItem(InventoryItemIdentity first, InventoryItemIdentity second) {
        return sameItem(first, first.stackWithCount(1), second, second.stackWithCount(1));
    }

    /**
     * {@link #sameItem(InventoryItemIdentity, InventoryItemIdentity)} on the two count-1 stacks the caller has already
     * built from those identities, so they are built once.
     */
    static boolean sameItem(
            InventoryItemIdentity first, ItemStack firstStack, InventoryItemIdentity second, ItemStack secondStack) {
        return first.registryId().equals(second.registryId()) && firstStack.getItem() == secondStack.getItem();
    }

    /**
     * The stack in one place kept its item and its count across a click; its components may have changed. Empty
     * stacks never match.
     */
    public static boolean keptItemAndCount(ItemStack before, ItemStack after) {
        return before != null && after != null
                && !before.isEmpty() && !after.isEmpty()
                && before.getItem() == after.getItem()
                && before.getCount() == after.getCount();
    }
}
