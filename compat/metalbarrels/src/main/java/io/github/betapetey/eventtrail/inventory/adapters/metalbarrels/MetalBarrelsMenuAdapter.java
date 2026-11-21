package io.github.betapetey.eventtrail.inventory.adapters.metalbarrels;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBacking;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBlockTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuSlotAccess;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.SlotItemHandler;
import tfar.metalbarrels.blockentity.MetalBarrelBlockEntity;
import tfar.metalbarrels.blockentity.MetalBarrelBlockEntityForge;
import tfar.metalbarrels.menu.MetalBarrelMenu;
import tfar.metalbarrels.util.BarrelHandlerForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Exact adapter for Metal Barrels' placed barrels (eight blocks, six sizes), Metal Barrels B.
 *
 * <p>Every call re-proves one chain: the open menu is this player's {@code MetalBarrelMenu}, its handler is the barrel
 * handler whose block entity is a live {@code MetalBarrelBlockEntityForge}, and that block entity still holds the same
 * handler (the mod's own {@code isOwnContainer} identity). Any break refuses: no rows. A changed shape refuses quietly
 * here (AMBIGUOUS, empty or UNKNOWN); the open is then blocked, not recorded, and the player can still use the barrel.
 * The tracker counts the refused open and warns once per server start.</p>
 *
 * <p>Built on any installed Metal Barrels build: there is no version, artifact or class-hash check, as for the
 * storage adapters of build B F3-9. There is no catch and no reflection: the loader wraps this adapter in
 * {@code GuardedMenuAdapter}, so a member missing from a later build switches the {@code METAL_BARRELS} key OFF until
 * restart with one warning, and any other failure skips that one call. The adapter keeps no state between calls.</p>
 */
public final class MetalBarrelsMenuAdapter implements MenuTransactionAdapter {
    /** The provider id of every row of this adapter; row data, frozen byte for byte. */
    static final String ADAPTER_ID = "metalbarrels-barrel";
    /** The opener's 27 main and 9 hotbar rows, added after the barrel rows by the mod's menu constructor. */
    static final int PLAYER_SLOTS = 36;

    public MetalBarrelsMenuAdapter() {
    }

    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public boolean exact() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }
    @Override public boolean usesHandlerBackedView() { return true; }

    /**
     * NO_MATCH for any menu that is not a {@code MetalBarrelMenu}; for a barrel menu SUPPORTED only while the whole
     * chain holds, else AMBIGUOUS (blocked, never handed to the generic path). A subclass is AMBIGUOUS.
     */
    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof MetalBarrelMenu<?>)) return AdapterMatch.NO_MATCH;
        return chain(context) != null ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }

    @Override
    public Optional<MenuPhysicalBlockTarget> resolvePhysicalBlockTarget(MenuAdapterContext context) {
        Chain chain = chain(context);
        return chain == null ? Optional.empty() : liveTarget(chain, context.player());
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        Chain chain = chain(context);
        if (chain == null || liveTarget(chain, context.player()).isEmpty()) return Optional.empty();
        return describe(chain, context.player().getInventory());
    }

    @Override
    public InventorySlotRole classifySlot(MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        Chain chain = chain(context);
        return chain == null ? InventorySlotRole.UNKNOWN
                : role(chain, context.player().getInventory(), menuSlotIndex, slot);
    }

    /** The proven chain for one call; built per call and never cached. */
    record Chain(MetalBarrelMenu<?> menu, BarrelHandlerForge handler, MetalBarrelBlockEntity<?> blockEntity, int cells) {
    }

    /** The identity chain of {@code context}, or null when any link of it does not hold. */
    static Chain chain(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.menu() == null
                || context.menu().getClass() != MetalBarrelMenu.class
                || context.player().containerMenu != context.menu()) return null;
        MetalBarrelMenu<?> menu = (MetalBarrelMenu<?>) context.menu();
        if (!(menu.handler instanceof BarrelHandlerForge handler)) return null;
        MetalBarrelBlockEntity<?> blockEntity = handler.getBlockEntity();
        if (blockEntity == null || blockEntity.getClass() != MetalBarrelBlockEntityForge.class
                || blockEntity.barrelHandler != handler) return null;
        int width = menu.width;
        int height = menu.height;
        int limit = MenuInventoryLimits.MAX_DEVICE_CUSTODY_CELLS;
        // Bounds first, so the product cannot overflow.
        if (width < 1 || height < 1 || width > limit || height > limit || width * height > limit) return null;
        int cells = width * height;
        if (handler.getSlots() != cells || menu.slots.size() != cells + PLAYER_SLOTS) return null;
        return new Chain(menu, handler, blockEntity, cells);
    }

    /** Refuses without loading a chunk: removed, other level or unloaded first, then the shared live check. */
    static Optional<MenuPhysicalBlockTarget> liveTarget(Chain chain, ServerPlayer player) {
        Level level = player.level();
        MetalBarrelBlockEntity<?> blockEntity = chain.blockEntity();
        if (level == null || blockEntity.isRemoved() || blockEntity.getLevel() != level
                || !level.hasChunkAt(blockEntity.getBlockPos())) return Optional.empty();
        return MenuPhysicalBlockTarget.fromBlockEntity(blockEntity, level, level.dimension());
    }

    /** One backing (the proven handler, owner the block entity, so WORLD_BLOCK); every menu slot declared. */
    static Optional<MenuPhysicalBacking.Description> describe(Chain chain, Inventory playerInventory) {
        BarrelHandlerForge handler = chain.handler();
        List<Slot> slots = chain.menu().slots;
        List<MenuPhysicalBacking.DeclaredSlot> declared = new ArrayList<>(slots.size());
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            InventorySlotRole role = role(chain, playerInventory, index, slot);
            if (role == InventorySlotRole.UNKNOWN) return Optional.empty();
            Optional<MenuSlotAccess> access = Optional.empty();
            if (role == InventorySlotRole.STORAGE) {
                int cell = index;
                access = Optional.of(new MenuSlotAccess(handler, cell, () -> handler.getStackInSlot(cell)));
            }
            declared.add(new MenuPhysicalBacking.DeclaredSlot(index, slot, role, access));
        }
        return Optional.of(new MenuPhysicalBacking.Description(chain.blockEntity(),
                List.of(new MenuPhysicalBacking.Backing(handler, chain.cells(), handler::getStackInSlot)),
                declared, true));
    }

    /**
     * Barrel rows: an exact {@code SlotItemHandler} over the proven handler whose cell is its menu index (the mod adds
     * them row-major). Then the opener's own rows: exact vanilla {@code Slot}s over the opener's inventory.
     */
    static InventorySlotRole role(Chain chain, Inventory playerInventory, int index, Slot slot) {
        List<Slot> slots = chain.menu().slots;
        if (slot == null || playerInventory == null || index < 0 || index >= slots.size()
                || slots.get(index) != slot) return InventorySlotRole.UNKNOWN;
        if (index < chain.cells()) {
            return slot.getClass() == SlotItemHandler.class
                    && ((SlotItemHandler) slot).getItemHandler() == chain.handler()
                    && slot.getContainerSlot() == index
                    ? InventorySlotRole.STORAGE : InventorySlotRole.UNKNOWN;
        }
        return slot.getClass() == Slot.class && slot.container == playerInventory
                ? InventorySlotRole.PLAYER : InventorySlotRole.UNKNOWN;
    }
}
