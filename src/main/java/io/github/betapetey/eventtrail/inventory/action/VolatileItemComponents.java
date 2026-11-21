package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A fixed list of data components that a mod stamps on an item as it enters a container.
 *
 * <p>Used only by the custody correlator's fallback for one action whose exact item
 * identities do not balance (GF-IDENTITYNORM). Item identity itself stays component-exact.
 * Each entry names a data component by its registry id and, for a general component, the one
 * item it applies to. The ids are data: no mod class is referenced, and an id that is not
 * registered (the mod is absent) leaves its entry inert.</p>
 */
public final class VolatileItemComponents {
    /**
     * The shipped list. Each id was read from the mod jar's own bytes (GF-IDENTITYNORM build
     * NOTES): Super Factory Manager's compile output on a disk, and Create Thrusters' cooling
     * finish tick stamped when a timed item enters a container.
     */
    public static final VolatileItemComponents DEFAULT = new VolatileItemComponents(List.of(
            Entry.anyItem("sfm:warnings"),
            Entry.anyItem("sfm:errors"),
            Entry.forItem("minecraft:item_name", "sfm:disk"),
            Entry.anyItem("createthrusters:cooling_finish_tick")));

    /** No volatile components: the correlator stays component-exact. */
    public static final VolatileItemComponents NONE = new VolatileItemComponents(List.of());

    private final List<Entry> entries;

    public VolatileItemComponents(List<Entry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public List<Entry> entries() {
        return entries;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * True only when both identities are the same item and their components are equal after
     * removing the listed components that apply to that item, and at least one listed
     * component is registered for it. Callers use this only for identities that are not equal.
     */
    boolean sameItemIgnoringVolatile(InventoryItemIdentity first, InventoryItemIdentity second) {
        ItemStack left = first.stackWithCount(1);
        ItemStack right = second.stackWithCount(1);
        if (!SameStackRule.sameItem(first, left, second, right)) {
            return false;
        }

        boolean anyApplied = false;
        for (Entry entry : entries) {
            if (entry.itemId().isPresent() && !entry.itemId().orElseThrow().equals(first.registryId())) {
                continue;
            }
            Optional<DataComponentType<?>> type = resolve(entry.componentId());
            if (type.isEmpty()) {
                continue;
            }
            left.remove(type.orElseThrow());
            right.remove(type.orElseThrow());
            anyApplied = true;
        }
        return anyApplied && ItemStack.isSameItemSameComponents(left, right);
    }

    private static Optional<DataComponentType<?>> resolve(String componentId) {
        ResourceLocation location = ResourceLocation.tryParse(componentId);
        if (location == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(location);
    }

    /** One listed component id, optionally limited to one item id. */
    public record Entry(String componentId, Optional<String> itemId) {
        public Entry {
            componentId = Objects.requireNonNull(componentId, "componentId");
            itemId = Objects.requireNonNull(itemId, "itemId");
        }

        public static Entry anyItem(String componentId) {
            return new Entry(componentId, Optional.empty());
        }

        public static Entry forItem(String componentId, String itemId) {
            return new Entry(componentId, Optional.of(Objects.requireNonNull(itemId, "itemId")));
        }
    }
}
