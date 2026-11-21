package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GF-NESTED: the contents of an item-held store, read from one vanilla component only (BUNDLE_CONTENTS or CONTAINER).
 *
 * <p>Used only to check the amount of a nested transfer the item-click override proof already accepted; contents are
 * never counted into custody. Depth one: an entry that is itself a container counts as one identity (its own contents
 * are part of that identity, never read). A stack with both components, or with more than {@link #MAX_ENTRIES}
 * entries, is unreadable, so the nested transfer is refused. No item-handler capability is ever read.</p>
 */
public final class ItemShellContents {
    /** CONTAINER holds at most 256 stacks; a bundle holds fewer. Anything larger is refused. */
    public static final int MAX_ENTRIES = 256;

    private ItemShellContents() {
    }

    public enum Source {
        /** Neither component: the amount comes from the click alone (Owner decision Q17/Q45). */
        NONE,
        BUNDLE_CONTENTS,
        CONTAINER,
        /** Both components, too many entries, or an entry with no identity: the nested transfer is refused. */
        UNREADABLE
    }

    /** Component-exact contents totals, read from {@link #source()} only. */
    public record Reading(Source source, Map<InventoryItemIdentity, Long> totals) {
        public Reading {
            source = Objects.requireNonNull(source, "source");
            totals = Map.copyOf(Objects.requireNonNull(totals, "totals"));
        }

        static Reading of(Source source) {
            return new Reading(source, Map.of());
        }
    }

    public static Reading read(ItemStack shell) {
        Objects.requireNonNull(shell, "shell");
        BundleContents bundle = shell.get(DataComponents.BUNDLE_CONTENTS);
        ItemContainerContents container = shell.get(DataComponents.CONTAINER);
        if (bundle != null && container != null) return Reading.of(Source.UNREADABLE);
        if (bundle == null && container == null) return Reading.of(Source.NONE);
        Source source = bundle != null ? Source.BUNDLE_CONTENTS : Source.CONTAINER;
        Iterable<ItemStack> entries = bundle != null ? bundle.items() : container.nonEmptyItems();
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        int count = 0;
        try {
            for (ItemStack entry : entries) {
                if (entry == null || entry.isEmpty()) continue;
                if (++count > MAX_ENTRIES) return Reading.of(Source.UNREADABLE);
                totals.merge(InventoryItemIdentity.of(entry), (long) entry.getCount(), Math::addExact);
            }
        } catch (IllegalArgumentException | ArithmeticException unreadable) {
            return Reading.of(Source.UNREADABLE);
        }
        return new Reading(source, totals);
    }
}
