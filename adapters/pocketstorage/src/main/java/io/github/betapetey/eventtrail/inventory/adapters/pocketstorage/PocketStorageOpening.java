package io.github.betapetey.eventtrail.inventory.adapters.pocketstorage;

import com.flanks255.pocketstorage.PocketStorage;
import com.flanks255.pocketstorage.gui.PSUContainer;
import com.flanks255.pocketstorage.inventory.PSUItemHandler;
import com.flanks255.pocketstorage.inventory.StorageManager;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Exact native item-open scope; constructor binding precedes the ordinary OPEN event. */
public final class PocketStorageOpening {
    private static final ThreadLocal<Opening> OPENING = new ThreadLocal<>();
    private PocketStorageOpening() {}

    public static void open(Level level, Player actor, InteractionHand hand, Runnable original) {
        Opening prior = OPENING.get();
        Opening opening = null;
        try {
            if (prior != null) prior.valid = false;
            if (prior == null && !disabled() && actor instanceof ServerPlayer player && human(player)
                    && player.level() == level && hand != null) {
                ItemStack item = player.getItemInHand(hand);
                if (PocketStorageBinding.canonicalItem(item) != null
                        && PocketStorageBinding.ownsReference(player.getInventory(), item))
                    opening = new Opening(player, item, UniversalContainerTracker.runtimeGeneration());
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:PocketStorageOpening.open", failure); }
        if (opening == null) OPENING.remove(); else OPENING.set(opening);
        try { original.run(); }
        finally { if (prior == null) OPENING.remove(); else OPENING.set(prior); }
    }

    public static void bind(PSUContainer menu, Inventory inventory, UUID uuid, PSUItemHandler handler) {
        try {
            Opening opening = OPENING.get();
            if (opening == null || !opening.valid || disabled() || opening.used
                    || inventory.player != opening.player || !human(opening.player)
                    || opening.generation != UniversalContainerTracker.runtimeGeneration()
                    || !PocketStorageBinding.ownsReference(inventory, opening.item)
                    || !uuid.equals(opening.item.get(PocketStorage.PSU_UUID.get()))) return;
            if (!PocketStorageBinding.nativeMenu(menu, inventory, uuid, handler)) {
                shapeFailed(menu);
                return;
            }
            StorageManager manager = StorageManager.get();
            var data = manager.getStorage(uuid).orElse(null);
            if (manager == StorageManager.blankClient || data == null) return;
            if (data.getHandler() != handler || data.getTier() == null || handler.getSlots() != data.getTier().slots
                    || handler.getSlotLimit(0) != data.getTier().capacity) {
                shapeFailed(menu);
                return;
            }
            opening.used = true;
            ((PocketStorageMenuAccess) menu).eventtrail$pocketBinding(
                    new PocketStorageBinding(opening.player, menu, opening.item, uuid, manager, data));
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:PocketStorageOpening.bind", failure); }
    }

    /** GF-WARNINGS (B48): only a menu the adapter answers SUPPORTED for (the exact class) is warned about. */
    static void shapeFailed(PSUContainer menu) {
        if (menu != null && menu.getClass() == PSUContainer.class) {
            RecordingGaps.bindingShapeFailed(AdapterKey.POCKET_STORAGE, "adapter:PocketStorageOpening.bind");
        }
    }
    public static boolean human(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player) && !player.isSpectator()
                && !player.isCreative() && !player.isRemoved() && player.getServer() != null
                && player.getServer().isSameThread() && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    public static boolean disabled() {
        return OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(PocketStorageMenuAdapter.ID);
    }
    /** Reports to the fail-safe under POCKET_STORAGE: a missing member switches the key OFF, any other error skips this action. */
    public static void observationFailed(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.POCKET_STORAGE, site, failure);
    }
    public static int retainedScopes() { return OPENING.get() == null ? 0 : 1; }
    private static final class Opening {
        final ServerPlayer player; final ItemStack item; final long generation;
        boolean valid = true; boolean used;
        Opening(ServerPlayer player, ItemStack item, long generation) {
            this.player = player; this.item = item; this.generation = generation;
        }
    }
}
