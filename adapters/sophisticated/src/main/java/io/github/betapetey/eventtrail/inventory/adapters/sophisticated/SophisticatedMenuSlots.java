package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.MenuObservedSlot;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBacking;
import io.github.betapetey.eventtrail.inventory.spi.MenuSlotAccess;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedcore.api.ISlotChangeResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IInsertResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IExtractResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IOverflowResponseUpgrade;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/** Exact Core slot contract shared by independently admitted Storage and Backpack hosts. */
public final class SophisticatedMenuSlots {
    private SophisticatedMenuSlots() {}

    public static Optional<MenuPhysicalBacking.Description> describe(StorageContainerMenuBase<?> menu,
            Inventory playerInventory, Object owner, List<Object> members, boolean durable) {
        if (menu == null || playerInventory == null || menu.getStorageWrapper() == null) return Optional.empty();
        var inventory = menu.getStorageWrapper().getInventoryHandler();
        var upgrades = menu.getStorageWrapper().getUpgradeHandler();
        int custodyLimit = MenuInventoryLimits.MAX_DEVICE_CUSTODY_CELLS;
        int descriptorLimit = MenuInventoryLimits.MAX_MENU_DESCRIPTORS;
        if (inventory == null || upgrades == null || inventory.getSlots() < 1
                || upgrades.getSlots() < 0 || inventory.getSlots() > custodyLimit
                || upgrades.getSlots() > custodyLimit - inventory.getSlots()
                || menu.slots.size() > descriptorLimit || menu.upgradeSlots.size() > descriptorLimit) return Optional.empty();
        // Same enabled/main-storage accessor as actual Core synchronous dispatch.
        if (!upgrades.getWrappersThatImplementFromMainStorage(ISlotChangeResponseUpgrade.class).isEmpty()
                || !upgrades.getWrappersThatImplementFromMainStorage(IInsertResponseUpgrade.class).isEmpty()
                || !upgrades.getWrappersThatImplementFromMainStorage(IExtractResponseUpgrade.class).isEmpty()
                || !upgrades.getWrappersThatImplementFromMainStorage(IOverflowResponseUpgrade.class).isEmpty()) return Optional.empty();
        List<MenuPhysicalBacking.Backing> backings = new ArrayList<>();
        backings.add(new MenuPhysicalBacking.Backing(inventory, inventory.getSlots(), inventory::getStackInSlot));
        if (upgrades.getSlots() > 0) backings.add(new MenuPhysicalBacking.Backing(upgrades, upgrades.getSlots(), upgrades::getStackInSlot));
        List<MenuPhysicalBacking.DeclaredSlot> declared = new ArrayList<>();
        IdentityHashMap<Slot, Boolean> seen = new IdentityHashMap<>();
        List<MenuObservedSlot> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot == null || seen.put(slot, true) != null) return Optional.empty();
            slots.add(new MenuObservedSlot(i, slot));
        }
        for (Slot slot : menu.upgradeSlots) {
            if (slot == null) return Optional.empty();
            if (seen.put(slot, true) == null) {
                if (slots.size() == descriptorLimit) return Optional.empty();
                slots.add(new MenuObservedSlot(slot.index, slot));
            }
        }
        for (MenuObservedSlot observed : slots) {
            Slot slot = observed.slot();
            InventorySlotRole role = classify(menu, playerInventory, slot);
            if (role == InventorySlotRole.UNKNOWN) return Optional.empty();
            IItemHandler backing = null;
            if (role == InventorySlotRole.STORAGE) {
                var storageSlot = (StorageInventorySlot) slot;
                if (storageSlot.getItemHandler() != inventory || storageSlot.isInfinite()) return Optional.empty();
                backing = inventory;
            } else if (role == InventorySlotRole.REAL_UPGRADE) {
                var upgradeSlot = (StorageContainerMenuBase<?>.StorageUpgradeSlot) slot;
                if (upgradeSlot.getItemHandler() != upgrades) return Optional.empty();
                backing = upgrades;
            }
            Optional<MenuSlotAccess> access = Optional.empty();
            if (backing != null) {
                int cell = slot.getContainerSlot();
                if (cell < 0 || cell >= backing.getSlots()) return Optional.empty();
                IItemHandler exact = backing;
                access = Optional.of(new MenuSlotAccess(exact, cell, () -> exact.getStackInSlot(cell)));
            }
            declared.add(new MenuPhysicalBacking.DeclaredSlot(observed.menuSlotIndex(), slot, role, access));
        }
        return Optional.of(new MenuPhysicalBacking.Description(owner, backings, declared, durable, members));
    }

    public static InventorySlotRole classify(StorageContainerMenuBase<?> menu, Inventory playerInventory, Slot slot) {
        if (menu == null || slot == null || playerInventory == null) return InventorySlotRole.UNKNOWN;
        if (slot.container == playerInventory) return InventorySlotRole.PLAYER;
        if (slot.container instanceof Inventory) return InventorySlotRole.UNKNOWN;
        if (slot instanceof IFilterSlot) return InventorySlotRole.GHOST_FILTER;
        if (slot instanceof StorageInventorySlot) return InventorySlotRole.STORAGE;
        if (slot instanceof StorageContainerMenuBase<?>.StorageUpgradeSlot && menu.upgradeSlots.contains(slot)) return InventorySlotRole.REAL_UPGRADE;
        return InventorySlotRole.UNKNOWN;
    }

    public static List<MenuObservedSlot> additional(StorageContainerMenuBase<?> menu) {
        return menu.upgradeSlots.stream().filter(slot -> slot instanceof StorageContainerMenuBase<?>.StorageUpgradeSlot)
                .map(slot -> new MenuObservedSlot(slot.index, slot)).toList();
    }
}
