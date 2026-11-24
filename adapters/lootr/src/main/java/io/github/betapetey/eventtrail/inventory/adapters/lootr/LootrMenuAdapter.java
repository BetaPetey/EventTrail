package io.github.betapetey.eventtrail.inventory.adapters.lootr;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import noobanidus.mods.lootr.common.api.data.BaseLootrInfo;
import noobanidus.mods.lootr.common.api.data.ILootrInfo;
import noobanidus.mods.lootr.common.api.data.SimpleLootrInstance;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.block.entity.*;
import noobanidus.mods.lootr.common.data.LootrInventory;
import noobanidus.mods.lootr.common.data.LootrSavedData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact Lootr personal block storage; the path (team-mode API or not) is chosen by what is present, never by version. */
public final class LootrMenuAdapter implements MenuTransactionAdapter {
    private static final int CELLS = 27;
    private static final Map<Class<?>, String> PROVIDERS = Map.of(
            LootrChestBlockEntity.class, "lootr:lootr_chest",
            LootrTrappedChestBlockEntity.class, "lootr:lootr_trapped_chest",
            LootrBarrelBlockEntity.class, "lootr:lootr_barrel",
            LootrInventoryBlockEntity.class, "lootr:lootr_inventory",
            LootrShulkerBlockEntity.class, "lootr:lootr_shulker");
    private static final Field INVENTORIES = field(LootrSavedData.class, "inventories");
    private static final Field MENU_BUILDER = field(LootrInventory.class, "menuBuilder");
    private static final Field INFO_UUID = field(SimpleLootrInstance.class, "infoId");
    private static final Field INFO_KEY = field(SimpleLootrInstance.class, "cachedId");
    private static final Field DATA_CACHE = field(DimensionDataStorage.class, "cache");
    private static final Map<Class<?>, Field> INSTANCES = Map.of(
            LootrChestBlockEntity.class, field(LootrChestBlockEntity.class, "simpleLootrInstance"),
            LootrTrappedChestBlockEntity.class, field(LootrChestBlockEntity.class, "simpleLootrInstance"),
            LootrInventoryBlockEntity.class, field(LootrChestBlockEntity.class, "simpleLootrInstance"),
            LootrBarrelBlockEntity.class, field(LootrBarrelBlockEntity.class, "simpleLootrInstance"),
            LootrShulkerBlockEntity.class, field(LootrShulkerBlockEntity.class, "simpleLootrInstance"));

    private final LootrPersonalMode personalMode;

    /**
     * The installed Lootr's mode is probed when the loader builds this adapter (start-up; silent, presence only, no
     * version read) and kept for the JVM. An OFF result is reported at the first Lootr menu ({@link #match}).
     */
    public LootrMenuAdapter() {
        this(LootrPersonalMode.installed());
    }

    LootrMenuAdapter(LootrPersonalMode personalMode) {
        this.personalMode = Objects.requireNonNull(personalMode, "personalMode");
    }

    @Override public String adapterId() { return personalMode.adapterId(); }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        AdapterMatch match = ownership(context);
        // An OFF path still owns its menus: blocked, never handed to the generic fallback.
        if (match != AdapterMatch.NO_MATCH && !personalMode.recordingPath()) return AdapterMatch.AMBIGUOUS;
        return match;
    }

    private static AdapterMatch ownership(MenuAdapterContext context) {
        if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
        if (context.menu() instanceof ChestMenu chest && chest.getContainer() instanceof LootrInventory) {
            return chest.getClass() == ChestMenu.class && chest.getContainer().getClass() == LootrInventory.class
                    ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
        }
        // A custom menu must never fall through to generic physical attribution.
        for (Slot slot : context.menu().slots) {
            if (slot != null && slot.container instanceof LootrInventory) return AdapterMatch.AMBIGUOUS;
        }
        return OptionalMenuAdapterLoader.isLootrMenu(context) ? AdapterMatch.AMBIGUOUS : AdapterMatch.NO_MATCH;
    }

    private Optional<Anchor> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.player().containerMenu != context.menu()
                || EventTrailEventHandler.isFakePlayer(context.player()) || context.player().isSpectator()
                || context.player().isRemoved() || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        // A broken team-mode API (team-API path) must reach the caller's fail-safe (a counted skip; LOOTR stays on).
        // A readable team mode simply withholds this personal-storage contract.
        if (!personalMode.isPersonal()) return Optional.empty();
        try {
            ServerPlayer player = context.player();
            ChestMenu menu = (ChestMenu) context.menu();
            LootrInventory inventory = (LootrInventory) menu.getContainer();
            if (menu.getRowCount() != 3 || menu.slots.size() != CELLS + 36
                    || inventory.getInventoryContents().size() != CELLS
                    || MENU_BUILDER.get(inventory) != null || inventory.getInfo() == null
                    || inventory.getInfo().getClass() != LootrSavedData.class) return Optional.empty();
            LootrSavedData saved = (LootrSavedData) inventory.getInfo();
            ILootrInfo info = saved.getRedirect();
            if (info == null || info.getClass() != BaseLootrInfo.class && !PROVIDERS.containsKey(info.getClass())) return Optional.empty();
            // Provider UUID/key getters create missing identities. Read only the existing pinned fields.
            Object infoInstance = info.getClass() == BaseLootrInfo.class ? null : INSTANCES.get(info.getClass()).get(info);
            UUID providerId = info instanceof BaseLootrInfo base ? base.uuid()
                    : infoInstance == null ? null : (UUID) INFO_UUID.get(infoInstance);
            String key = info instanceof BaseLootrInfo base ? base.cachedKey()
                    : infoInstance == null ? null : (String) INFO_KEY.get(infoInstance);
            if (providerId == null || key == null || info.getInfoContainerSize() != CELLS
                    || !player.level().dimension().equals(info.getInfoDimension())) {
                return Optional.empty();
            }
            BlockPos position = info.getInfoPos();
            if (position == null || !player.level().hasChunkAt(position)) return Optional.empty();
            BlockEntity block = player.level().getBlockEntity(position);
            if (block == null || !PROVIDERS.containsKey(block.getClass())
                    || info.getClass() != BaseLootrInfo.class && info != block
                    || MenuPhysicalBlockTarget.fromBlockEntity(block, player.level(), info.getInfoDimension()).isEmpty()
                    || !Container.stillValidBlockEntity(block, player)) return Optional.empty();
            ILootrBlockEntity provider = (ILootrBlockEntity) block;
            Object instance = INSTANCES.get(block.getClass()).get(block);
            if (instance == null || instance.getClass() != SimpleLootrInstance.class
                    || !providerId.equals(INFO_UUID.get(instance))
                    || !key.equals(INFO_KEY.get(instance))
                    || !key.equals(ILootrInfo.generateInfoKey(providerId))
                    || info.getInfoNewType() == null || info.getInfoNewType() != provider.getInfoNewType()) return Optional.empty();
            Object map = INVENTORIES.get(saved);
            if (!(map instanceof Map<?, ?> inventories) || inventories.get(player.getUUID()) != inventory
                    || player.server == null || player.server.overworld() == null) return Optional.empty();
            DimensionDataStorage storage = player.server.overworld().getDataStorage();
            Object cache = DATA_CACHE.get(storage);
            if (!(cache instanceof Map<?, ?> cached) || cached.get(key) != saved) return Optional.empty();
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                boolean device = index < CELLS;
                int cell = device ? index : index < CELLS + 27 ? index - CELLS + 9 : index - CELLS - 27;
                if (slot == null || slot.getClass() != Slot.class
                        || slot.container != (device ? inventory : player.getInventory())
                        || slot.getContainerSlot() != cell) return Optional.empty();
            }
            return Optional.of(new Anchor(inventory, saved, map, storage, cache, block,
                    providerId, player.getUUID(), position.immutable(), PROVIDERS.get(block.getClass())));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            AdapterFailSafe.report(AdapterKey.LOOTR, "adapter:LootrMenuAdapter.current", invalid);
            return Optional.empty();
        }
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return current(context).flatMap(anchor -> {
            List<MenuPhysicalBacking.DeclaredSlot> slots = new ArrayList<>();
            for (int index = 0; index < context.menu().slots.size(); index++) {
                Slot slot = context.menu().slots.get(index);
                if (slot == null || slot.getClass() != Slot.class) return Optional.empty();
                boolean device = index < CELLS;
                int expectedCell = device ? index : index < CELLS + 27 ? index - CELLS + 9 : index - CELLS - 27;
                if (slot.container != (device ? anchor.inventory() : context.player().getInventory())
                        || slot.getContainerSlot() != expectedCell) return Optional.empty();
                Optional<MenuSlotAccess> access = device
                        ? Optional.of(new MenuSlotAccess(anchor.inventory(), expectedCell, () -> anchor.inventory().getItem(expectedCell)))
                        : Optional.empty();
                slots.add(new MenuPhysicalBacking.DeclaredSlot(index, slot,
                        device ? InventorySlotRole.STORAGE : InventorySlotRole.PLAYER, access));
            }
            return Optional.of(new MenuPhysicalBacking.Description(anchor.inventory(),
                    List.of(new MenuPhysicalBacking.Backing(anchor.inventory(), CELLS, anchor.inventory()::getItem)),
                    slots, true, List.of(anchor.provider(), anchor.saved(), anchor.map(), anchor.storage(), anchor.cache(),
                            anchor.inventory().getInventoryContents())));
        });
    }

    @Override
    public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return current(context).map(anchor -> target(context.player(), anchor));
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).map(anchor -> new MenuAccessTarget(target(context.player(), anchor), anchor.targetId()));
    }

    private static ContainerTarget target(ServerPlayer player, Anchor anchor) {
        String label = DisplayNameResolver.blockDisplayName(anchor.provider().getBlockState().getBlock());
        return ContainerTarget.block(player.level().dimension().location().toString(), anchor.position(), label,
                BackingContextKind.PLAYER_BOUND, "lootr:provider:" + anchor.providerId() + ":player:" + anchor.playerId());
    }

    @Override
    public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        if (context == null || context.player() == null || slot == null || slot.getClass() != Slot.class) return InventorySlotRole.UNKNOWN;
        if (slot.container == context.player().getInventory()) return InventorySlotRole.PLAYER;
        return context.menu() instanceof ChestMenu chest && chest.getContainer().getClass() == LootrInventory.class
                && slot.container == chest.getContainer() ? InventorySlotRole.STORAGE : InventorySlotRole.UNKNOWN;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Pinned Lootr ownership contract unavailable", failure);
        }
    }

    private record Anchor(LootrInventory inventory, LootrSavedData saved, Object map,
            DimensionDataStorage storage, Object cache, BlockEntity provider, UUID providerId,
            UUID playerId, BlockPos position, String targetId) {}
}
