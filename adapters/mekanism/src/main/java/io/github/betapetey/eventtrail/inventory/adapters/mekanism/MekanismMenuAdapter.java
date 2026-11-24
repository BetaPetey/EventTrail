package io.github.betapetey.eventtrail.inventory.adapters.mekanism;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.RecordingGaps;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBlockTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBacking;
import io.github.betapetey.eventtrail.inventory.spi.MenuSlotAccess;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.tile.DigitalMinerConfigContainer;
import mekanism.common.inventory.container.tile.FactoryContainer;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.FactoryInputInventorySlot;
import mekanism.common.inventory.slot.FluidInventorySlot;
import mekanism.common.inventory.slot.FormulaicCraftingSlot;
import mekanism.common.inventory.slot.FuelInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.InternalInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.slot.SecurityInventorySlot;
import mekanism.common.inventory.slot.UpgradeInventorySlot;
import mekanism.common.inventory.slot.chemical.ChemicalInventorySlot;
import mekanism.common.tile.machine.TileEntityDigitalMiner;
import mekanism.common.tile.machine.TileEntityEnrichmentChamber;
import mekanism.common.tile.machine.TileEntityEnergizedSmelter;
import mekanism.common.tile.machine.TileEntityCrusher;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Exact backing/role producer for Mekanism 10.7.19 factories, Digital Miner,
 * and the bounded enrichment/smelting/crushing electric-machine family.
 *
 * <p>This class deliberately lives behind EventTrail's optional-adapter loader.
 * No neutral EventTrail class has a static reference to Mekanism. Other
 * {@link MekanismTileContainer} families remain unsupported until their slot
 * construction has been proven independently.</p>
 *
 * <p>Built on any installed Mekanism build (R1c); 10.7.19 is the tested build, and
 * the provider id keeps its label. Known limitations on a changed build: a menu
 * whose slots no longer pass {@code describeSlots} gets no description, so
 * Mekanism records nothing for that menu family for the whole run while
 * {@code match} answers SUPPORTED (Advisor M-3; Mekanism has no OFF route of its
 * own). GF-WARNINGS (B48): each such menu is counted, with one warning per site
 * after each server start, and the key stays ON. What a same-shape changed build can miswrite is the slot
 * role only (accepted under design D4 / R1d; Advisor M-4).</p>
 */
public final class MekanismMenuAdapter implements MenuTransactionAdapter {
    public static final String ADAPTER_ID = "mekanism-10.7.19-machine";

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
        Class<?> tileType = null;
        if (context.menu() instanceof MekanismTileContainer<?> tileMenu
                && tileMenu.getTileEntity() != null) {
            tileType = tileMenu.getTileEntity().getClass();
        }
        return classifyMenuFamily(context.menu().getClass(), tileType);
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.ACTION_SCOPED_PHYSICAL;
    }

    @Override
    public boolean usesHandlerBackedView() {
        return true;
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        if (context == null || context.player() == null
                || context.player().containerMenu != context.menu()
                || match(context) != AdapterMatch.SUPPORTED
                || resolvePhysicalBlockTarget(context).isEmpty()
                || !(context.menu() instanceof MekanismTileContainer<?> menu)) return Optional.empty();
        var tile = menu.getTileEntity();
        List<IInventorySlot> owned = new ArrayList<>();
        if (tile.supportsUpgrades()) {
            if (tile.getComponent() == null) return shapeFailed();
            owned.add(tile.getComponent().getUpgradeSlot());
            owned.add(tile.getComponent().getUpgradeOutputSlot());
        }
        if (tile.hasInventory()) owned.addAll(tile.getInventorySlots(null));
        Optional<MenuPhysicalBacking.Description> description =
                describeSlots(menu, context.player().getInventory(), tile, owned, isDigitalMinerMenu(context));
        return description.isPresent() ? description : shapeFailed();
    }

    /** GF-WARNINGS (B48): a SUPPORTED menu with a resolved block whose slots fail the shape check is counted and warned. */
    static Optional<MenuPhysicalBacking.Description> shapeFailed() {
        RecordingGaps.bindingShapeFailed(AdapterKey.MEKANISM, "adapter:MekanismMenuAdapter.describePhysicalBacking");
        return Optional.empty();
    }

    /**
     * Menu construction's exact owner list, not a capability discovered nearby.
     *
     * <p>What this checks is identity, not class (Advisor M-4): every non-player slot
     * must be an {@code InventoryContainerSlot} whose backing cell is one of the
     * tile's own cells, by identity, and no owned cell may repeat in the tile's list
     * (two menu slots backed by the same owned cell are not rejected). The slot and
     * backing classes are not checked; the role comes from the backing class hierarchy, and
     * from {@code ContainerSlotType} for an unknown class. Item and count come from
     * the real cells and the target from the tile, so on a same-shape changed build
     * the role is the only value that can be miswritten.</p>
     */
    static Optional<MenuPhysicalBacking.Description> describeSlots(AbstractContainerMenu menu,
            Container playerInventory, Object owner, List<IInventorySlot> owned, boolean digitalMiner) {
        if (owned == null || owned.isEmpty() || owned.size() > MenuInventoryLimits.MAX_BACKING_OBJECTS
                || menu.slots.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) return Optional.empty();
        List<MenuPhysicalBacking.Backing> backings = new ArrayList<>();
        IdentityHashMap<IInventorySlot, Boolean> ownedIdentities = new IdentityHashMap<>();
        for (IInventorySlot cell : owned) {
            if (cell == null || ownedIdentities.put(cell, true) != null) return Optional.empty();
            backings.add(new MenuPhysicalBacking.Backing(cell, 1, ignored -> cell.getStack()));
        }
        List<MenuPhysicalBacking.DeclaredSlot> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot == null) return Optional.empty();
            if (slot.container == playerInventory) {
                slots.add(new MenuPhysicalBacking.DeclaredSlot(i, slot, InventorySlotRole.PLAYER, Optional.empty()));
            } else if (slot instanceof InventoryContainerSlot inventorySlot) {
                IInventorySlot cell = inventorySlot.getInventorySlot();
                if (!ownedIdentities.containsKey(cell)) return Optional.empty();
                InventorySlotRole role = classifyInventorySlot(digitalMiner, inventorySlot);
                slots.add(new MenuPhysicalBacking.DeclaredSlot(i, slot, role,
                        Optional.of(new MenuSlotAccess(cell, 0, cell::getStack))));
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new MenuPhysicalBacking.Description(owner, backings, slots, true));
    }

    @Override
    public Optional<MenuPhysicalBlockTarget> resolvePhysicalBlockTarget(
            MenuAdapterContext context) {
        if (context == null
                || context.player() == null
                || !(context.menu() instanceof MekanismTileContainer<?> tileMenu)) {
            return Optional.empty();
        }
        var tile = tileMenu.getTileEntity();
        return MenuPhysicalBlockTarget.fromBlockEntity(
                tile,
                context.player().level(),
                context.player().level().dimension());
    }

    @Override
    public InventorySlotRole classifySlot(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        if (context == null || slot == null) return InventorySlotRole.UNKNOWN;
        if (context.player() != null && slot.container == context.player().getInventory()) {
            return InventorySlotRole.PLAYER;
        }
        if (!(slot instanceof InventoryContainerSlot mekanismSlot)) {
            return InventorySlotRole.UNKNOWN;
        }
        return classifyInventorySlot(context, mekanismSlot);
    }

    @Override
    public Optional<MenuSlotAccess> resolveSlotAccess(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        if (!(slot instanceof InventoryContainerSlot mekanismSlot)) return Optional.empty();
        IInventorySlot inventorySlot = mekanismSlot.getInventorySlot();
        return Optional.of(new MenuSlotAccess(
                inventorySlot,
                0,
                inventorySlot::getStack));
    }

    static InventorySlotRole classifyInventorySlot(
            MenuAdapterContext context, InventoryContainerSlot slot) {
        return classifyInventorySlot(isDigitalMinerMenu(context), slot);
    }

    static InventorySlotRole classifyInventorySlot(
            boolean digitalMinerMenu, InventoryContainerSlot slot) {
        IInventorySlot backing = slot.getInventorySlot();

        // A virtual/window marker is not itself proof that the backing is
        // display-only (real upgrade custody also uses one). Only that exact
        // validated virtual backing is accepted; all others invalidate the
        // view instead of silently omitting possible custody.
        if (slot instanceof IVirtualSlot) {
            return backing instanceof UpgradeInventorySlot
                    ? InventorySlotRole.REAL_UPGRADE
                    : InventorySlotRole.UNKNOWN;
        }
        InventorySlotRole knownRole = classifyKnownBackingType(backing.getClass());
        if (knownRole != InventorySlotRole.UNKNOWN) return knownRole;

        ContainerSlotType slotType = slot.getSlotType();
        if (slotType == ContainerSlotType.INPUT) return InventorySlotRole.MACHINE_INPUT;
        if (slotType == ContainerSlotType.OUTPUT) return InventorySlotRole.MACHINE_OUTPUT;
        if (slotType == ContainerSlotType.POWER) return InventorySlotRole.MACHINE_FUEL;
        if (slotType == ContainerSlotType.VALIDITY) return InventorySlotRole.CONFIGURATION;
        if (slotType == ContainerSlotType.IGNORED) return InventorySlotRole.UNKNOWN;

        // Digital Miner uses ordinary BasicInventorySlot cells for its bounded
        // mined-item/replacement buffer. NORMAL/EXTRA remain ambiguous for
        // factories and all other machine families.
        if (digitalMinerMenu
                && backing instanceof BasicInventorySlot
                && slotType == ContainerSlotType.NORMAL) {
            return InventorySlotRole.MACHINE_BUFFER;
        }
        return InventorySlotRole.UNKNOWN;
    }

    static InventorySlotRole classifyKnownBackingType(Class<?> backingType) {
        if (backingType == null) return InventorySlotRole.UNKNOWN;
        if (UpgradeInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.REAL_UPGRADE;
        }
        if (SecurityInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.SECURITY;
        }
        if (FormulaicCraftingSlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.CRAFTING_INPUT;
        }
        if (InternalInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.CONFIGURATION;
        }
        if (EnergyInventorySlot.class.isAssignableFrom(backingType)
                || FuelInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.MACHINE_FUEL;
        }
        if (FluidInventorySlot.class.isAssignableFrom(backingType)
                || ChemicalInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.REAL_FLUID_CONTAINER;
        }
        if (OutputInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.MACHINE_OUTPUT;
        }
        if (FactoryInputInventorySlot.class.isAssignableFrom(backingType)
                || InputInventorySlot.class.isAssignableFrom(backingType)) {
            return InventorySlotRole.MACHINE_INPUT;
        }
        return InventorySlotRole.UNKNOWN;
    }

    static boolean isSupportedMenuFamily(Class<?> menuType, Class<?> tileType) {
        if (menuType == null) return false;
        if (FactoryContainer.class.isAssignableFrom(menuType)) return true;
        if (menuType == MekanismTileContainer.class
                && (tileType == TileEntityEnrichmentChamber.class
                || tileType == TileEntityEnergizedSmelter.class
                || tileType == TileEntityCrusher.class)) return true;
        return MekanismTileContainer.class.isAssignableFrom(menuType)
                && !DigitalMinerConfigContainer.class.isAssignableFrom(menuType)
                && tileType != null
                && TileEntityDigitalMiner.class.isAssignableFrom(tileType);
    }

    static AdapterMatch classifyMenuFamily(Class<?> menuType, Class<?> tileType) {
        if (menuType == null || !MekanismContainer.class.isAssignableFrom(menuType)) {
            return AdapterMatch.NO_MATCH;
        }
        return isSupportedMenuFamily(menuType, tileType)
                ? AdapterMatch.SUPPORTED
                : AdapterMatch.AMBIGUOUS;
    }

    private static boolean isDigitalMinerMenu(MenuAdapterContext context) {
        return context != null
                && context.menu() instanceof MekanismTileContainer<?> tileMenu
                && !(context.menu() instanceof DigitalMinerConfigContainer)
                && tileMenu.getTileEntity() instanceof TileEntityDigitalMiner;
    }
}
