package io.github.betapetey.eventtrail.inventory.adapters.refinedstorage;

import com.refinedmods.refinedstorage.common.api.storage.StorageContainerItem;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.WirelessGridItem;
import com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridBlockItem;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuAccessTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Exact committed-operation adapter for Refined Storage 2.0.9 grid menus. */
public final class RefinedStorageMenuAdapter implements MenuTransactionAdapter {
    public static final String GRID_MENU_BASE =
            "com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu";
    static final String GRID_MENU =
            "com.refinedmods.refinedstorage.common.grid.GridContainerMenu";
    static final String CRAFTING_GRID_MENU =
            "com.refinedmods.refinedstorage.common.grid.CraftingGridContainerMenu";
    static final String WIRELESS_GRID_MENU =
            "com.refinedmods.refinedstorage.common.grid.WirelessGridContainerMenu";
    static final String PATTERN_GRID_MENU =
            "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu";
    static final String PORTABLE_BLOCK_MENU =
            "com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridBlockContainerMenu";
    static final String PORTABLE_ITEM_MENU =
            "com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGridItemContainerMenu";

    private static final Set<String> PROVEN_MENU_CLASSES = Set.of(
            GRID_MENU,
            CRAFTING_GRID_MENU,
            WIRELESS_GRID_MENU,
            PATTERN_GRID_MENU,
            PORTABLE_BLOCK_MENU,
            PORTABLE_ITEM_MENU);

    @Override
    public String adapterId() {
        return "refinedstorage-2.0.9-exact-operation";
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof AbstractGridContainerMenu)) {
            return AdapterMatch.NO_MATCH;
        }
        return isProvenMenuClass(context.menu().getClass())
                ? AdapterMatch.SUPPORTED
                : AdapterMatch.AMBIGUOUS;
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.AUTHORITATIVE_OPERATION;
    }

    @Override
    public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        if (context == null
                || !(context.menu() instanceof AbstractGridContainerMenu menu)
                || !(context.player() instanceof ServerPlayer player)
                || match(context) != AdapterMatch.SUPPORTED) {
            return Optional.empty();
        }

        String menuClass = menu.getClass().getName();
        if (WIRELESS_GRID_MENU.equals(menuClass)) {
            return Optional.of(mobileTarget(
                    player,
                    "Wireless Grid",
                    AccessKind.WIRELESS_NETWORK,
                    BackingContextKind.NETWORK,
                    "wireless"));
        }
        if (PORTABLE_ITEM_MENU.equals(menuClass)) {
            return Optional.of(mobileTarget(
                    player,
                    "Portable Grid",
                    AccessKind.PORTABLE_MENU,
                    BackingContextKind.ITEM_BOUND,
                    "portable-item"));
        }
        if (!(menu instanceof RefinedStoragePhysicalMenuAccess physicalAccess)) {
            return Optional.empty();
        }
        boolean portableBlock = PORTABLE_BLOCK_MENU.equals(menuClass);
        return blockTarget(
                physicalAccess.eventtrail$getAccessBlockEntity(),
                portableBlock ? AccessKind.PHYSICAL : AccessKind.WIRED_NETWORK,
                portableBlock ? BackingContextKind.PLACED_BLOCK : BackingContextKind.NETWORK,
                portableBlock ? "portable-block" : "wired");
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(
            MenuAdapterContext context) {
        if (context == null
                || !(context.menu() instanceof AbstractGridContainerMenu menu)
                || !(context.player() instanceof ServerPlayer player)
                || match(context) != AdapterMatch.SUPPORTED) {
            return Optional.empty();
        }

        String menuClass = menu.getClass().getName();
        if (WIRELESS_GRID_MENU.equals(menuClass)) {
            return resolveWirelessAccess(player, menu);
        }
        if (PORTABLE_ITEM_MENU.equals(menuClass)) {
            return resolvePortableItemAccess(player, menu);
        }
        if (PORTABLE_BLOCK_MENU.equals(menuClass)
                && menu instanceof RefinedStoragePhysicalMenuAccess physicalAccess) {
            return placedPortableAccess(physicalAccess.eventtrail$getAccessBlockEntity());
        }

        // RS 2.0.9 exposes the wired Grid block but no durable network ID.
        // Never promote Network.hashCode()/debugNetworkId or controller guesses
        // into public access history. Committed transaction attribution remains
        // on resolveAuthoritativeTarget above.
        return Optional.empty();
    }

    private static Optional<MenuAccessTarget> resolveWirelessAccess(
            ServerPlayer player, AbstractGridContainerMenu menu) {
        if (!(menu instanceof RefinedStorageMobileMenuAccess mobile)) {
            return Optional.empty();
        }
        ItemStack accessItem = mobile.eventtrail$resolveAccessItem(player);
        if (accessItem == null
                || accessItem.isEmpty()
                || !(accessItem.getItem() instanceof WirelessGridItem)) {
            return Optional.empty();
        }
        GlobalPos networkLocation = accessItem.get(
                com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE
                        .getNetworkLocation());
        String technicalId = canonicalItemId(accessItem);
        if (networkLocation == null || technicalId == null) return Optional.empty();

        String label = accessItem.getHoverName().getString();
        if (label == null || label.isBlank()) label = "Wireless Grid";
        return Optional.of(wirelessAccessTarget(
                player.level().dimension().location().toString(),
                player.blockPosition().immutable(),
                label,
                technicalId,
                networkLocation));
    }

    private static Optional<MenuAccessTarget> resolvePortableItemAccess(
            ServerPlayer player, AbstractGridContainerMenu menu) {
        if (!(menu instanceof RefinedStorageMobileMenuAccess mobile)) {
            return Optional.empty();
        }
        ItemStack accessItem = mobile.eventtrail$resolveAccessItem(player);
        ItemStack disk = mobile.eventtrail$getBackingStorageItem();
        if (accessItem == null
                || accessItem.isEmpty()
                || !(accessItem.getItem() instanceof PortableGridBlockItem)
                || disk == null
                || disk.isEmpty()
                || !(disk.getItem() instanceof StorageContainerItem)) {
            return Optional.empty();
        }
        UUID storageReference = disk.get(
                com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE
                        .getStorageReference());
        String technicalId = canonicalItemId(accessItem);
        if (storageReference == null || technicalId == null) return Optional.empty();

        String label = accessItem.getHoverName().getString();
        if (label == null || label.isBlank()) label = "Portable Grid";
        return Optional.of(portableItemAccessTarget(
                player.level().dimension().location().toString(),
                player.blockPosition().immutable(),
                label,
                technicalId,
                storageReference));
    }

    private static Optional<MenuAccessTarget> placedPortableAccess(BlockEntity blockEntity) {
        Optional<ContainerTarget> target = blockTarget(
                blockEntity,
                AccessKind.PHYSICAL,
                BackingContextKind.PLACED_BLOCK,
                "portable-block");
        if (target.isEmpty() || blockEntity == null || blockEntity.getLevel() == null) {
            return Optional.empty();
        }
        String technicalId = BuiltInRegistries.BLOCK
                .getResourceKey(blockEntity.getBlockState().getBlock())
                .map(key -> key.location().toString())
                .orElse(null);
        return technicalId == null
                ? Optional.empty()
                : Optional.of(new MenuAccessTarget(target.orElseThrow(), technicalId));
    }

    static boolean isProvenMenuClass(Class<?> menuClass) {
        return menuClass != null && PROVEN_MENU_CLASSES.contains(menuClass.getName());
    }

    static ContainerTarget mobileTarget(
            String dimension,
            BlockPos position,
            String playerId,
            int containerId,
            String label,
            AccessKind accessKind,
            BackingContextKind backingKind,
            String kind) {
        return new ContainerTarget(
                dimension,
                position.getX(),
                position.getY(),
                position.getZ(),
                label,
                accessKind,
                backingKind,
                "refinedstorage:" + kind + ':' + playerId + ':' + containerId);
    }

    static MenuAccessTarget wirelessAccessTarget(
            String accessDimension,
            BlockPos accessPosition,
            String label,
            String technicalTargetId,
            GlobalPos networkLocation) {
        if (networkLocation == null) {
            throw new IllegalArgumentException("networkLocation must not be null");
        }
        BlockPos linkedBlock = networkLocation.pos();
        String backingKey = "refinedstorage:network-location:"
                + networkLocation.dimension().location() + '@'
                + linkedBlock.getX() + ',' + linkedBlock.getY() + ',' + linkedBlock.getZ();
        return new MenuAccessTarget(
                new ContainerTarget(
                        accessDimension,
                        accessPosition.getX(),
                        accessPosition.getY(),
                        accessPosition.getZ(),
                        label,
                        AccessKind.WIRELESS_NETWORK,
                        BackingContextKind.NETWORK,
                        backingKey),
                technicalTargetId);
    }

    static MenuAccessTarget portableItemAccessTarget(
            String accessDimension,
            BlockPos accessPosition,
            String label,
            String technicalTargetId,
            UUID storageReference) {
        if (storageReference == null) {
            throw new IllegalArgumentException("storageReference must not be null");
        }
        return new MenuAccessTarget(
                new ContainerTarget(
                        accessDimension,
                        accessPosition.getX(),
                        accessPosition.getY(),
                        accessPosition.getZ(),
                        label,
                        AccessKind.PORTABLE_MENU,
                        BackingContextKind.ITEM_BOUND,
                        "refinedstorage:storage-reference:" + storageReference),
                technicalTargetId);
    }

    static boolean supportsDurableAccessMenuClass(Class<?> menuClass) {
        if (menuClass == null) return false;
        String name = menuClass.getName();
        return WIRELESS_GRID_MENU.equals(name)
                || PORTABLE_ITEM_MENU.equals(name)
                || PORTABLE_BLOCK_MENU.equals(name);
    }

    private static String canonicalItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                .map(key -> key.location().toString())
                .orElse(null);
    }

    private static ContainerTarget mobileTarget(
            ServerPlayer player,
            String label,
            AccessKind accessKind,
            BackingContextKind backingKind,
            String kind) {
        BlockPos position = player.blockPosition().immutable();
        return mobileTarget(
                player.level().dimension().location().toString(),
                position,
                player.getUUID().toString(),
                player.containerMenu.containerId,
                label,
                accessKind,
                backingKind,
                kind);
    }

    private static Optional<ContainerTarget> blockTarget(
            BlockEntity blockEntity,
            AccessKind accessKind,
            BackingContextKind backingKind,
            String kind) {
        if (blockEntity == null || blockEntity.isRemoved() || blockEntity.getLevel() == null) {
            return Optional.empty();
        }
        Level level = blockEntity.getLevel();
        BlockPos position = blockEntity.getBlockPos().immutable();
        String dimension = level.dimension().location().toString();
        String label = DisplayNameResolver.blockDisplayName(
                level.getBlockState(position).getBlock());
        if (label == null || label.isBlank()) label = "Refined Storage Grid";
        return Optional.of(new ContainerTarget(
                dimension,
                position.getX(),
                position.getY(),
                position.getZ(),
                label,
                accessKind,
                backingKind,
                "refinedstorage:" + kind + ':' + dimension + '@'
                        + position.getX() + ',' + position.getY() + ',' + position.getZ()));
    }
}
