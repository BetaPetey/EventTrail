package io.github.betapetey.eventtrail.inventory.adapters.pocketstorage;

import com.flanks255.pocketstorage.PocketStorage;
import com.flanks255.pocketstorage.gui.LockableSlot;
import com.flanks255.pocketstorage.gui.PSUContainer;
import com.flanks255.pocketstorage.inventory.PSUData;
import com.flanks255.pocketstorage.inventory.PSUItemHandler;
import com.flanks255.pocketstorage.inventory.StorageManager;
import com.flanks255.pocketstorage.items.PSUTier;
import com.flanks255.pocketstorage.items.PocketStorageUnit;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.Optional;
import java.util.UUID;

/** One menu's item authority. No retained inventory map or global UUID registry. */
public final class PocketStorageBinding {
    final ServerPlayer player;
    final PSUContainer menu;
    final ItemStack item;
    final UUID uuid;
    final StorageManager manager;
    final PSUData data;
    final PSUItemHandler handler;
    private final Item itemType;
    private final String itemId;
    private final PSUTier tier;
    private final ServerLevel level;
    private final long generation;
    private boolean valid = true;

    PocketStorageBinding(ServerPlayer player, PSUContainer menu, ItemStack item, UUID uuid,
                         StorageManager manager, PSUData data) {
        this.player = player; this.menu = menu; this.item = item; this.uuid = uuid;
        this.manager = manager; this.data = data; this.handler = data.getHandler();
        this.itemType = item.getItem(); this.itemId = canonicalItem(item);
        this.tier = data.getTier(); this.level = player.serverLevel();
        this.generation = UniversalContainerTracker.runtimeGeneration();
    }

    static String canonicalItem(ItemStack item) {
        if (item == null || item.isEmpty() || item.getCount() != 1
                || item.getItem().getClass() != PocketStorageUnit.class) return null;
        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        return switch (id) {
            case "pocketstorage:psu_1", "pocketstorage:psu_2", "pocketstorage:psu_3", "pocketstorage:psu_4" -> id;
            default -> null;
        };
    }

    /** Exact stack reference may move; a copied UUID does not replace that reference. */
    static boolean ownsReference(Inventory inventory, ItemStack expected) {
        if (inventory == null || expected == null || expected.isEmpty()
                || inventory.getContainerSize() != 41) return false;
        int occurrences = 0;
        for (int i = 0; i < 41; i++) if (inventory.getItem(i) == expected) occurrences++;
        return occurrences == 1;
    }

    static boolean nativeMenu(PSUContainer menu, Inventory inventory, UUID uuid, PSUItemHandler handler) {
        if (menu == null || menu.getClass() != PSUContainer.class || handler == null
                || handler.getClass() != PSUItemHandler.class || menu.handler != handler
                || menu.getType() != PocketStorage.PSUCONTAINER.get()
                || !(menu instanceof PocketStorageMenuAccess access)
                || access.eventtrail$pocketPlayerInventory() != inventory
                || !uuid.equals(access.eventtrail$pocketUuid()) || menu.slots.size() != 36) return false;
        for (int i = 0; i < 36; i++) {
            var slot = menu.slots.get(i);
            if (slot == null || slot.getClass() != LockableSlot.class || slot.index != i
                    || slot.container != inventory || slot.getContainerSlot() != i) return false;
        }
        return true;
    }

    public boolean current() {
        if (!valid) return false;
        try {
            valid = !PocketStorageOpening.disabled() && PocketStorageOpening.human(player)
                    && player.level() == level && player.containerMenu == menu
                    && generation == UniversalContainerTracker.runtimeGeneration()
                    && item.getItem() == itemType && itemId != null && itemId.equals(canonicalItem(item))
                    && ownsReference(player.getInventory(), item)
                    && uuid.equals(item.get(PocketStorage.PSU_UUID.get()))
                    && StorageManager.get() == manager && manager != StorageManager.blankClient
                    && manager.getStorage(uuid).orElse(null) == data
                    && data.getUuid().equals(uuid) && data.getHandler() == handler && data.getTier() == tier
                    && handler.getSlots() == tier.slots && handler.getSlotLimit(0) == tier.capacity
                    && nativeMenu(menu, player.getInventory(), uuid, handler)
                    && ((PocketStorageMenuAccess) menu).eventtrail$pocketBinding() == this;
        } catch (RuntimeException | LinkageError failure) {
            valid = false; PocketStorageOpening.observationFailed("adapter:PocketStorageBinding.current", failure);
        }
        return valid;
    }

    public Optional<ContainerTarget> target() {
        if (!current()) return Optional.empty();
        return Optional.of(target(level.dimension().location().toString(), player.blockPosition(), uuid, itemId));
    }
    static ContainerTarget target(String dimension, BlockPos position, UUID uuid, String itemId) {
        String label = "Pocket Storage Tier " + itemId.charAt(itemId.length() - 1);
        return new ContainerTarget(dimension, position.getX(), position.getY(), position.getZ(), label,
                AccessKind.PORTABLE_MENU, BackingContextKind.ITEM_BOUND, backingKey(uuid));
    }
    static String backingKey(UUID uuid) { return "pocketstorage:1.2.5+1.21.1-b3:psu:" + uuid; }
    public String itemId() { return itemId; }
    public void invalidate() { valid = false; }
}
