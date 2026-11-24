package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The read-only Curios read of graves design r6, 1.5, with no Curios API: the player's saved data
 * ({@code saveWithoutId}), its NeoForge attachment {@value #ATTACHMENT}, and every item compound in that subtree, each
 * parsed as a full stack with the server's registries, so an enchanted or renamed curio has the same
 * {@link InventoryItemIdentity} as in the grave.
 *
 * <p>An item compound is a compound with an {@code id} and a {@code count}; the walk does not go into it (its own
 * components are part of the stack). A read gives no result ({@link Optional#empty()}) when Curios is not loaded, the
 * attachment is missing, or any part of the subtree does not read; the caller then counts no Curios gain and counts
 * {@code grave-curios-unread} (1.5).</p>
 */
public final class CuriosItems {
    /** Curios' mod id. */
    static final String MOD_ID = "curios";
    /** NeoForge's attachment key in an entity's saved data. */
    static final String ATTACHMENTS = "neoforge:attachments";
    /** Curios' inventory attachment. */
    static final String ATTACHMENT = "curios:inventory";

    private static final int MAX_DEPTH = 32;

    private CuriosItems() {
    }

    /** The player's Curios items, or empty when they cannot be read (1.5). Never throws. */
    public static Optional<Map<InventoryItemIdentity, Long>> read(ServerPlayer player,
            HolderLookup.Provider registries) {
        try {
            ModList mods = ModList.get();
            if (mods == null || !mods.isLoaded(MOD_ID)) return Optional.empty();
            return fromPlayerData(player.saveWithoutId(new CompoundTag()), registries);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return Optional.empty();
        }
    }

    /** {@link #read} after the player's save (test seam). */
    static Optional<Map<InventoryItemIdentity, Long>> fromPlayerData(CompoundTag playerData,
            HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (playerData == null || !(playerData.get(ATTACHMENTS) instanceof CompoundTag attachments)) {
            return Optional.empty();
        }
        if (!(attachments.get(ATTACHMENT) instanceof CompoundTag curios)) return Optional.empty();
        Map<InventoryItemIdentity, Long> counts = new LinkedHashMap<>();
        return walk(curios, registries, counts, 0) ? Optional.of(counts) : Optional.empty();
    }

    private static boolean walk(Tag tag, HolderLookup.Provider registries, Map<InventoryItemIdentity, Long> counts,
            int depth) {
        if (depth > MAX_DEPTH) return false;
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id") && compound.contains("count")) {
                ItemStack stack = GraveContents.parse(compound, registries);
                if (stack == null) return false;
                if (!stack.isEmpty()) {
                    counts.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
                }
                return true;
            }
            for (String key : compound.getAllKeys()) {
                if (!walk(compound.get(key), registries, counts, depth + 1)) return false;
            }
            return true;
        }
        if (tag instanceof ListTag list) {
            for (Tag element : list) {
                if (!walk(element, registries, counts, depth + 1)) return false;
            }
        }
        return true;
    }
}
