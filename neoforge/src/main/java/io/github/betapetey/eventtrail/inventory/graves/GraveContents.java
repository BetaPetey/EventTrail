package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The contents read of graves design r6, 1.1-3: a per-mod list spec over the grave's saved data, giving counts per
 * {@link InventoryItemIdentity} (components included). Each named path holds a list of item compounds, each parsed as a
 * full stack with the server's registries ({@code ItemStack.parseOptional}).
 *
 * <p>An empty slot compound (no id, or air) is skipped. A compound with an item id that does not parse as a stack is
 * left out and counted {@code grave-contents-unread}: its units are never guessed. A missing path reads as empty, so a
 * grave that is gone counts as empty (1.3 step 4).</p>
 */
public final class GraveContents {
    private static final ResourceLocation AIR = ResourceLocation.withDefaultNamespace("air");

    private GraveContents() {
    }

    /** A mod's contents paths: dotted paths to lists of item compounds ({@code "Death.MainInventory"}). */
    public record Spec(List<String> listPaths) {
        public Spec {
            listPaths = List.copyOf(listPaths);
            if (listPaths.isEmpty()) throw new IllegalArgumentException("A spec names at least one list");
            for (String path : listPaths) {
                if (path == null || path.isEmpty() || path.startsWith(".") || path.endsWith(".")
                        || path.contains("..")) {
                    throw new IllegalArgumentException("A list path is a dotted list of names");
                }
            }
        }
    }

    /** The counts per identity of {@code data} by {@code spec}; null data reads as an empty grave. */
    public static Map<InventoryItemIdentity, Long> read(Spec spec, CompoundTag data,
            HolderLookup.Provider registries) {
        Objects.requireNonNull(spec, "spec");
        Map<InventoryItemIdentity, Long> counts = new LinkedHashMap<>();
        if (data == null) return counts;
        for (String path : spec.listPaths()) {
            if (!(GraveOwnerReader.at(data, path) instanceof ListTag list)) continue;
            if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
                GraveCounts.count(GraveCounts.Reason.GRAVE_CONTENTS_UNREAD);
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                ItemStack stack = parse(list.getCompound(i), registries);
                if (stack == null) {
                    GraveCounts.count(GraveCounts.Reason.GRAVE_CONTENTS_UNREAD);
                } else if (!stack.isEmpty()) {
                    counts.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
                }
            }
        }
        return counts;
    }

    /**
     * One item compound as a full stack: {@link ItemStack#EMPTY} for an empty slot (no id, or air), null for a compound
     * whose item does not read.
     */
    static ItemStack parse(CompoundTag item, HolderLookup.Provider registries) {
        Tag id = item.get("id");
        if (id == null) return ItemStack.EMPTY;
        if (!(id instanceof StringTag text)) return null;
        ResourceLocation itemId = ResourceLocation.tryParse(text.getAsString());
        if (itemId == null) return null;
        if (itemId.equals(AIR)) return ItemStack.EMPTY;
        try {
            ItemStack stack = ItemStack.parseOptional(Objects.requireNonNull(registries, "registries"), item);
            return stack.isEmpty() ? null : stack;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
