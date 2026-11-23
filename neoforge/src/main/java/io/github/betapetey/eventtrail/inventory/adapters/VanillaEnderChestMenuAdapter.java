package io.github.betapetey.eventtrail.inventory.adapters;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

/** Exact player-bound ownership for the vanilla Ender Chest menu. */
public final class VanillaEnderChestMenuAdapter implements MenuTransactionAdapter {
    @Override
    public String adapterId() {
        return "minecraft-1.21.1-ender-chest";
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || context.menu() == null || context.player() == null) {
            return AdapterMatch.NO_MATCH;
        }
        return ownsVanillaEnderChestMenu(
                context.menu(), context.player().getEnderChestInventory())
                ? AdapterMatch.SUPPORTED
                : AdapterMatch.NO_MATCH;
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.ACTION_SCOPED_PHYSICAL;
    }

    @Override
    public InventorySlotRole classifySlot(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        if (context == null || context.player() == null || slot == null) {
            return InventorySlotRole.UNKNOWN;
        }
        if (slot.container == context.player().getInventory()) return InventorySlotRole.PLAYER;
        return slot.container == context.player().getEnderChestInventory()
                ? InventorySlotRole.STORAGE
                : InventorySlotRole.UNKNOWN;
    }

    static boolean ownsVanillaEnderChestMenu(AbstractContainerMenu menu, Object playerBoundInventory) {
        // Integrated and other multi-tab menus may expose the player's Ender Chest
        // as one tab. A slot witness alone does not transfer ownership of that menu.
        return menu != null && menu.getClass() == ChestMenu.class
                && containsPlayerBoundInventory(menu, playerBoundInventory);
    }

    static boolean containsPlayerBoundInventory(
            AbstractContainerMenu menu,
            Object playerBoundInventory) {
        if (menu == null || playerBoundInventory == null) return false;
        for (Slot slot : menu.slots) {
            if (slot.container == playerBoundInventory) return true;
        }
        return false;
    }
}
