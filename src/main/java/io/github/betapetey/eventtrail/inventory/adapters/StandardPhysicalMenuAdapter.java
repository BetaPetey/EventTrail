package io.github.betapetey.eventtrail.inventory.adapters;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.OpeningPlayerSlots;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.Slot;

/** Final bounded fallback for ordinary physical menu actions. */
public final class StandardPhysicalMenuAdapter implements MenuTransactionAdapter {
    @Override
    public String adapterId() {
        return "standard-bounded-physical";
    }

    @Override
    public boolean exact() {
        return false;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        return context != null && context.boundedPhysicalCandidate()
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
        if (slot.container == context.player().getInventory()
                || slot.container instanceof Inventory
                || OpeningPlayerSlots.isOpeningPlayerSlot(slot, context.player().getInventory())) {
            return InventorySlotRole.PLAYER;
        }
        if (!context.boundedPhysicalCandidate()) return InventorySlotRole.UNKNOWN;
        if (context.menu() instanceof AbstractFurnaceMenu) {
            return switch (slot.getSlotIndex()) {
                case 0 -> InventorySlotRole.MACHINE_INPUT;
                case 1 -> InventorySlotRole.MACHINE_FUEL;
                case 2 -> InventorySlotRole.MACHINE_OUTPUT;
                default -> InventorySlotRole.UNKNOWN;
            };
        }
        if (context.menu() instanceof BrewingStandMenu) {
            return switch (slot.getSlotIndex()) {
                case 0, 1, 2 -> InventorySlotRole.MACHINE_BUFFER;
                case 3 -> InventorySlotRole.MACHINE_CATALYST;
                case 4 -> InventorySlotRole.MACHINE_FUEL;
                default -> InventorySlotRole.UNKNOWN;
            };
        }
        return InventorySlotRole.STORAGE;
    }
}
