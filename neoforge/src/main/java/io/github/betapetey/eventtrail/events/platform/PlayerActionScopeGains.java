package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GF-SCOPE: the one inventory-gain rule shared with GF-DROPPICK (plan review; Owner Q11, C11). In a use scope with no
 * player break session, when a block vanished and the player's inventory gained items, a withdrawal row at that block
 * is written only for the vanished block's own item or for items the block held (its stored contents), for the amount
 * the inventory actually gained. Every other gain is not a withdrawal: drops gained inside a break session, a crop's
 * produce (a harvest is his pickup, R2-1) and anything that matches neither are GF-DROPPICK's to judge.
 */
final class PlayerActionScopeGains {
    /** What the inventory gained of one exact item (components included, count excluded). */
    record Gain(ItemStack sample, long count) {
    }

    /** A block that vanished in the scope: its own item and the stacks it held at its first touch. */
    record Vanished(PlayerActionScopeFrame.Touch touch, Item ownItem, List<ItemStack> contents) {
    }

    /** One withdrawal row: this much of this exact item, at this vanished block. */
    record Grant(PlayerActionScopeFrame.Touch touch, ItemStack sample, long count) {
    }

    private PlayerActionScopeGains() {
    }

    /** The net gain of each exact item between two snapshots of the same inventory slots. */
    static Map<InventoryItemIdentity, Gain> gains(List<ItemStack> before, List<ItemStack> after) {
        Map<InventoryItemIdentity, long[]> delta = new LinkedHashMap<>();
        Map<InventoryItemIdentity, ItemStack> samples = new LinkedHashMap<>();
        add(after, delta, samples, 1);
        add(before, delta, samples, -1);
        Map<InventoryItemIdentity, Gain> gains = new LinkedHashMap<>();
        for (Map.Entry<InventoryItemIdentity, long[]> entry : delta.entrySet()) {
            if (entry.getValue()[0] > 0L) {
                gains.put(entry.getKey(), new Gain(samples.get(entry.getKey()), entry.getValue()[0]));
            }
        }
        return gains;
    }

    private static void add(List<ItemStack> stacks, Map<InventoryItemIdentity, long[]> delta,
                            Map<InventoryItemIdentity, ItemStack> samples, int sign) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            InventoryItemIdentity identity = InventoryItemIdentity.of(stack);
            delta.computeIfAbsent(identity, ignored -> new long[1])[0] += (long) sign * stack.getCount();
            samples.putIfAbsent(identity, stack.copyWithCount(1));
        }
    }

    /**
     * Matches the gains to the vanished blocks, in first-touch order: first the exact stacks each block held, then one
     * of the block's own item (any components). A gain is used once; what is left matches nothing.
     */
    static List<Grant> allocate(List<Vanished> vanished, Map<InventoryItemIdentity, Gain> gains) {
        Map<InventoryItemIdentity, long[]> left = new LinkedHashMap<>();
        for (Map.Entry<InventoryItemIdentity, Gain> entry : gains.entrySet()) {
            left.put(entry.getKey(), new long[] {entry.getValue().count()});
        }
        List<Grant> grants = new ArrayList<>();
        for (Vanished block : vanished) {
            Map<InventoryItemIdentity, long[]> held = new LinkedHashMap<>();
            Map<InventoryItemIdentity, ItemStack> heldSamples = new LinkedHashMap<>();
            add(block.contents(), held, heldSamples, 1);
            for (Map.Entry<InventoryItemIdentity, long[]> entry : held.entrySet()) {
                long[] remaining = left.get(entry.getKey());
                if (remaining == null || remaining[0] <= 0L) continue;
                long take = Math.min(remaining[0], entry.getValue()[0]);
                remaining[0] -= take;
                grants.add(new Grant(block.touch(), heldSamples.get(entry.getKey()), take));
            }
            if (block.ownItem() == null) continue;
            for (Map.Entry<InventoryItemIdentity, long[]> entry : left.entrySet()) {
                Gain gain = gains.get(entry.getKey());
                if (entry.getValue()[0] <= 0L || gain.sample().getItem() != block.ownItem()) continue;
                entry.getValue()[0] -= 1L;
                grants.add(new Grant(block.touch(), gain.sample(), 1L));
                break;
            }
        }
        return List.copyOf(grants);
    }
}
