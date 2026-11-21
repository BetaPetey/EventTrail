package io.github.betapetey.eventtrail.inventory.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

/**
 * Recognises a menu slot that shows the opening player's own inventory through
 * NeoForge's item-handler wrappers (Iron Furnaces builds its player rows this
 * way), so the bounded physical path treats it like a vanilla player slot.
 *
 * <p>Identity only, exact NeoForge classes only. The slot must be exactly
 * {@link SlotItemHandler}; its handler exactly {@link InvWrapper} or
 * {@link PlayerMainInvWrapper}; the wrapped object the very {@link Inventory}
 * the caller names. Anything else (another player's inventory, a block's
 * storage, ender-chest storage, {@code PlayerInvWrapper}, any subclass) is not
 * a player slot, so the one-device rule still refuses the menu. Only NeoForge
 * getters run here, never mod code. The same identity check as
 * {@code RftoolsScannerBinding.nativeMenu}.</p>
 */
public final class OpeningPlayerSlots {
    private OpeningPlayerSlots() {}

    public static boolean isOpeningPlayerSlot(Slot slot, Container openingPlayerInventory) {
        if (slot == null || !(openingPlayerInventory instanceof Inventory)
                || slot.getClass() != SlotItemHandler.class) {
            return false;
        }
        IItemHandler handler = ((SlotItemHandler) slot).getItemHandler();
        if (handler == null) return false;
        if (handler.getClass() == InvWrapper.class) {
            return ((InvWrapper) handler).getInv() == openingPlayerInventory;
        }
        if (handler.getClass() == PlayerMainInvWrapper.class) {
            return ((PlayerMainInvWrapper) handler).getInventoryPlayer() == openingPlayerInventory;
        }
        return false;
    }
}
