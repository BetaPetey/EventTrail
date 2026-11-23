package io.github.betapetey.eventtrail.inventory.adapters.ae2;

import appeng.helpers.WirelessTerminalMenuHost;
import appeng.items.contents.PortableCellMenuHost;
import appeng.menu.me.common.MEStorageMenu;
import appeng.parts.AEBasePart;
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

/** Exact committed-operation adapter for AE2 19.2.17 item storage menus. */
public final class Ae2MenuAdapter implements MenuTransactionAdapter {
    public static final String STORAGE_MENU_BASE = "appeng.menu.me.common.MEStorageMenu";
    static final String AE2WTLIB_WCT_MENU = "de.mari_023.ae2wtlib.wct.WCTMenu";
    private static final Set<String> PROVEN_MENU_CLASSES = Set.of(
            STORAGE_MENU_BASE,
            "appeng.menu.me.items.BasicCellChestMenu",
            "appeng.menu.me.items.CraftingTermMenu",
            "appeng.menu.me.items.PatternEncodingTermMenu",
            "appeng.menu.me.items.WirelessCraftingTermMenu");

    @Override
    public String adapterId() {
        return "ae2-19-exact-operation";
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof MEStorageMenu)) {
            return AdapterMatch.NO_MATCH;
        }
        return classifyMenuClassName(context.menu().getClass().getName());
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.AUTHORITATIVE_OPERATION;
    }

    @Override
    public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        if (match(context) != AdapterMatch.SUPPORTED) {
            return Optional.empty();
        }
        return resolveProvenTarget(context, null);
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(
            MenuAdapterContext context) {
        if (match(context) != AdapterMatch.SUPPORTED) {
            return Optional.empty();
        }
        return resolveProvenAccessTarget(context, null);
    }

    /**
     * Resolves only identities that remain authoritative beyond one menu ID.
     * Wired grids expose no durable network identifier in AE2 19.2.17, and
     * portable cells expose no immutable instance identifier, so both fail
     * closed for public access history while their transaction paths remain
     * unchanged.
     */
    static Optional<MenuAccessTarget> resolveProvenAccessTarget(
            MenuAdapterContext context, String exactLabel) {
        if (context == null
                || !(context.menu() instanceof MEStorageMenu menu)
                || !(context.player() instanceof ServerPlayer player)
                || !(menu.getTarget() instanceof WirelessTerminalMenuHost<?> host)) {
            return Optional.empty();
        }

        ItemStack stack = host.getItemStack();
        if (stack == null || stack.isEmpty() || stack.getItem() != host.getItem()) {
            return Optional.empty();
        }
        GlobalPos linkedPosition = host.getItem().getLinkedPosition(stack);
        if (linkedPosition == null) return Optional.empty();

        String technicalId = BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                .map(key -> key.location().toString())
                .orElse(null);
        if (technicalId == null) return Optional.empty();

        String label = accessDisplayLabel(
                stack.getHoverName().getString(), exactLabel, menuLabel(menu));
        return Optional.of(wirelessAccessTarget(
                player.level().dimension().location().toString(),
                player.blockPosition().immutable(),
                label,
                technicalId,
                linkedPosition));
    }

    /**
     * The exact AE2WTLib adapter owns its menu separately, but both integrations
     * intentionally share AE2's authoritative target rules. The caller's match
     * must be SUPPORTED; the host type is decided here.
     */
    static Optional<ContainerTarget> resolveProvenTarget(
            MenuAdapterContext context, String exactLabel) {
        if (context == null
                || !(context.menu() instanceof MEStorageMenu menu)
                || !(context.player() instanceof ServerPlayer player)) {
            return Optional.empty();
        }

        Object target = menu.getTarget();
        if (target instanceof WirelessTerminalMenuHost<?>) {
            return Optional.of(mobileTarget(
                    player,
                    labelOrDefault(exactLabel, menuLabel(menu)),
                    AccessKind.WIRELESS_NETWORK,
                    BackingContextKind.NETWORK,
                    "wireless"));
        }
        if (target instanceof PortableCellMenuHost<?>) {
            return Optional.of(mobileTarget(
                    player,
                    labelOrDefault(exactLabel, menuLabel(menu)),
                    AccessKind.PORTABLE_MENU,
                    BackingContextKind.ITEM_BOUND,
                    "portable"));
        }

        BlockEntity access = null;
        boolean partHost = false;
        if (target instanceof BlockEntity blockEntity) {
            access = blockEntity;
        } else if (target instanceof AEBasePart part) {
            access = part.getBlockEntity();
            partHost = true;
        }
        if (access == null || access.isRemoved() || access.getLevel() == null) {
            return Optional.empty();
        }

        Level level = access.getLevel();
        BlockPos pos = access.getBlockPos().immutable();
        String label = exactLabel != null && !exactLabel.isBlank()
                ? exactLabel
                : partHost
                ? menuLabel(menu)
                : DisplayNameResolver.blockDisplayName(level.getBlockState(pos).getBlock());
        if (label == null || label.isBlank()) label = menuLabel(menu);
        String dimension = level.dimension().location().toString();
        return Optional.of(new ContainerTarget(
                dimension,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                label,
                AccessKind.WIRED_NETWORK,
                BackingContextKind.NETWORK,
                "ae2:wired:" + dimension + '@' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()));
    }

    /** Keeps the base AE2 adapter and the exact addon adapter single-owned. */
    static AdapterMatch classifyMenuClassName(String menuClassName) {
        if (AE2WTLIB_WCT_MENU.equals(menuClassName)) {
            return AdapterMatch.NO_MATCH;
        }
        return PROVEN_MENU_CLASSES.contains(menuClassName)
                ? AdapterMatch.SUPPORTED
                : AdapterMatch.UNSUPPORTED_VERSION;
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
                "ae2:" + kind + ':' + playerId + ':' + containerId);
    }

    static MenuAccessTarget wirelessAccessTarget(
            String accessDimension,
            BlockPos accessPosition,
            String label,
            String technicalTargetId,
            GlobalPos linkedPosition) {
        if (linkedPosition == null) {
            throw new IllegalArgumentException("linkedPosition must not be null");
        }
        BlockPos linkedBlock = linkedPosition.pos();
        String backingKey = "ae2:wireless-link:"
                + linkedPosition.dimension().location() + '@'
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

    static String accessDisplayLabel(
            String itemDisplayName, String integrationFallback, String menuFallback) {
        if (itemDisplayName != null && !itemDisplayName.isBlank()) return itemDisplayName;
        if (integrationFallback != null && !integrationFallback.isBlank()) {
            return integrationFallback;
        }
        return menuFallback;
    }

    private static ContainerTarget mobileTarget(
            ServerPlayer player,
            String label,
            AccessKind accessKind,
            BackingContextKind backingKind,
            String kind) {
        BlockPos pos = player.blockPosition().immutable();
        return mobileTarget(
                player.level().dimension().location().toString(),
                pos,
                player.getUUID().toString(),
                player.containerMenu.containerId,
                label,
                accessKind,
                backingKind,
                kind);
    }

    private static String labelOrDefault(String exactLabel, String fallback) {
        return exactLabel == null || exactLabel.isBlank() ? fallback : exactLabel;
    }

    private static String menuLabel(MEStorageMenu menu) {
        return switch (menu.getClass().getSimpleName()) {
            case "BasicCellChestMenu" -> "ME Chest";
            case "CraftingTermMenu" -> "ME Crafting Terminal";
            case "PatternEncodingTermMenu" -> "ME Pattern Encoding Terminal";
            case "WirelessCraftingTermMenu" -> "Wireless ME Crafting Terminal";
            default -> menu.getTarget() instanceof WirelessTerminalMenuHost<?>
                    ? "Wireless ME Terminal"
                    : menu.getTarget() instanceof PortableCellMenuHost<?>
                            ? "Portable ME Cell"
                            : "ME Terminal";
        };
    }
}
