package io.github.betapetey.eventtrail.inventory.adapters.occultism;

import com.klikli_dev.occultism.common.blockentity.StorageControllerBlockEntity;
import com.klikli_dev.occultism.common.blockentity.StableWormholeBlockEntity;
import com.klikli_dev.occultism.common.container.storage.*;
import com.klikli_dev.occultism.common.item.storage.StorageRemoteItem;
import com.klikli_dev.occultism.common.misc.StorageControllerMapItemStackHandler;
import com.klikli_dev.occultism.common.misc.StorageControllerSlot;
import com.klikli_dev.occultism.registry.OccultismContainers;
import com.klikli_dev.occultism.registry.OccultismDataComponents;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.*;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Menu-local native authority. Never scans or retains a dimensional store. */
public final class OccultismStorageBinding {
    final ServerPlayer player;
    final StorageControllerContainerBase menu;
    final StorageControllerBlockEntity controller;
    final StorageControllerMapItemStackHandler handler;
    final Object map;
    final ServerLevel playerLevel;
    final ServerLevel controllerLevel;
    final BlockPos controllerPos;
    final BlockEntity accessBlock;
    final BlockPos accessPos;
    final ItemStack remote;
    final int remoteSlot;
    final long generation;
    final long linkEpoch;
    private boolean valid = true;
    OccultismStorageBinding(ServerPlayer player, StorageControllerContainerBase menu,
                            StorageControllerBlockEntity controller, BlockEntity accessBlock,
                            ItemStack remote, int remoteSlot) {
        this.player = player; this.menu = menu; this.controller = controller; this.handler = controller.itemStackHandler;
        this.map = handler.keyToCountMap(); this.playerLevel = player.serverLevel();
        this.controllerLevel = (ServerLevel) controller.getLevel(); this.controllerPos = controller.getBlockPos().immutable();
        this.accessBlock = accessBlock; this.remote = remote; this.remoteSlot = remoteSlot;
        this.accessPos = accessBlock == null ? null : accessBlock.getBlockPos().immutable();
        this.generation = UniversalContainerTracker.runtimeGeneration();
        this.linkEpoch = accessBlock instanceof OccultismStorageLinkAccess access ? access.eventtrail$occultismLinkEpoch() : -1;
    }
    static boolean nativeMenu(StorageControllerContainerBase menu, ServerPlayer player) {
        if (menu == null || player == null || menu.player != player || menu.playerInventory != player.getInventory()
                || menu.slots.size() != 47 || menu.getCraftMatrix().getContainerSize() != 9
                || menu.getOrderSlot().getContainerSize() != 1) return false;
        if (menu.getClass() == StorageControllerContainer.class) {
            if (menu.getType() != OccultismContainers.STORAGE_CONTROLLER.get()) return false;
        } else if (menu.getClass() == StableWormholeContainer.class) {
            if (menu.getType() != OccultismContainers.STABLE_WORMHOLE.get()) return false;
        } else if (menu.getClass() == StorageRemoteContainer.class) {
            if (menu.getType() != OccultismContainers.STORAGE_REMOTE.get()) return false;
        } else return false;
        if (menu.getSlot(0).getClass() != StorageControllerSlot.class) return false;
        for (int i = 1; i <= 10; i++) {
            Slot slot = menu.getSlot(i);
            if (slot.getClass() != Slot.class || slot.index != i
                    || slot.container != (i == 10 ? menu.getOrderSlot() : menu.getCraftMatrix())
                    || slot.getContainerSlot() != (i == 10 ? 0 : i - 1)) return false;
        }
        for (int i = 11; i < 47; i++) {
            Slot slot = menu.getSlot(i);
            if (slot == null || slot.index != i || slot.container != player.getInventory()
                    || slot.getContainerSlot() != playerIndex(i)) return false;
            if (slot.getClass() != Slot.class) {
                if (!(menu instanceof StorageRemoteContainer) || !(menu instanceof OccultismStorageRemoteAccess access)
                        || playerIndex(i) != access.eventtrail$occultismSelectedSlot()
                        || !(slot.getClass().getName().equals(StorageRemoteContainer.class.getName() + "$1")
                        || slot.getClass().getName().equals(StorageRemoteContainer.class.getName() + "$2"))) return false;
            }
        }
        return true;
    }
    static int playerIndex(int menuSlot) { return menuSlot < 11 || menuSlot >= 47 ? -1 : (menuSlot < 38 ? menuSlot - 2 : menuSlot - 38); }
    static boolean live(BlockEntity block, ServerLevel level, BlockPos pos) {
        return block != null && level != null && !block.isRemoved() && block.getLevel() == level
                && pos.equals(block.getBlockPos()) && level.hasChunkAt(pos) && level.getBlockEntity(pos) == block;
    }
    static boolean stationary(ServerLevel level, BlockPos pos) {
        return AuditBlockLocations.capture(level, pos).filter(frame -> !frame.moving() && frame.isCurrent()
                && frame.currentLocation().filter(location -> location.position().equals(pos)).isPresent()).isPresent();
    }
    /**
     * The Occultism hooks (wormhole link epoch, remote relink, map mutation witness, menu binding, operations) run only
     * while the OCCULTISM key is active. A binding is therefore current only while that key is active, so a skipped
     * link bump or relink can never keep a binding open. OFF lasts until restart, and a restart changes the runtime
     * generation, so the binding stays invalid.
     */
    static boolean occultismHooksRunning() {
        try {
            return AdapterFailSafe.active(AdapterKey.OCCULTISM);
        } catch (Throwable unavailable) {
            return false;
        }
    }
    boolean authority(boolean activeMenu) {
        if (!valid) return false;
        try {
            valid = occultismHooksRunning() && !OccultismStorageOpening.disabled() && OccultismStorageOpening.human(player)
                    && player.serverLevel() == playerLevel && (!activeMenu || player.containerMenu == menu)
                    && generation == UniversalContainerTracker.runtimeGeneration()
                    && controller.getClass() == StorageControllerBlockEntity.class
                    && handler.getClass() == StorageControllerMapItemStackHandler.class
                    && handler instanceof OccultismStorageMapAccess
                    && controller.itemStackHandler == handler && handler.keyToCountMap() == map
                    && live(controller, controllerLevel, controllerPos) && stationary(controllerLevel, controllerPos)
                    && nativeMenu(menu, player);
            if (!valid) return false;
            if (remote != null) {
                var link = remote.get(OccultismDataComponents.LINKED_STORAGE_CONTROLLER);
                valid = menu.getClass() == StorageRemoteContainer.class && remoteSlot >= 0 && remoteSlot < 41
                        && player.getInventory().getItem(remoteSlot) == remote && remote.getCount() == 1
                        && remote.getItem().getClass() == StorageRemoteItem.class
                        && "occultism:storage_remote".equals(BuiltInRegistries.ITEM.getKey(remote.getItem()).toString())
                        && link != null && controllerLevel.dimension().equals(link.getDimensionKey()) && controllerPos.equals(link.getPos())
                        && ((StorageRemoteContainer) menu).getStorageRemote() == remote;
            } else {
                valid = accessBlock != null && live(accessBlock, playerLevel, accessPos)
                        && stationary(playerLevel, accessPos)
                        && player.distanceToSqr(accessPos.getX() + .5, accessPos.getY() + .5, accessPos.getZ() + .5) <= 64;
                if (valid && menu.getClass() == StorageControllerContainer.class)
                    valid = accessBlock == controller && ((StorageControllerContainer) menu).getStorageController() == controller;
                else if (valid && menu.getClass() == StableWormholeContainer.class) {
                    var wormhole = ((StableWormholeContainer) menu).getStableWormhole();
                    var link = wormhole.getLinkedStorageControllerPosition();
                    valid = wormhole == accessBlock && wormhole.getClass() == StableWormholeBlockEntity.class
                            && wormhole instanceof OccultismStorageLinkAccess epoch && epoch.eventtrail$occultismLinkEpoch() == linkEpoch
                            && ((StableWormholeContainer) menu).getStorageController() == controller && link != null
                            && controllerLevel.dimension().equals(link.getDimensionKey()) && controllerPos.equals(link.getPos());
                } else valid = false;
            }
        } catch (RuntimeException | LinkageError failure) { valid = false; OccultismStorageOpening.observationFailed("adapter:OccultismStorageBinding.authority", failure); }
        return valid;
    }
    public boolean current() { return authority(true); }
    public Optional<ContainerTarget> target() {
        if (!current()) return Optional.empty();
        BlockPos pos = remote == null ? accessPos : player.blockPosition();
        BackingContextKind kind = remote != null ? BackingContextKind.NETWORK
                : accessBlock == controller ? BackingContextKind.WORLD_BLOCK : BackingContextKind.PROXY;
        return Optional.of(new ContainerTarget(playerLevel.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                remote != null ? "Occultism Storage Remote" : accessBlock == controller ? "Occultism Storage Controller" : "Occultism Stable Wormhole",
                remote != null ? AccessKind.PORTABLE_MENU : AccessKind.PHYSICAL, kind,
                backingKey(controllerLevel.dimension().location().toString(), controllerPos)));
    }
    static String backingKey(String dimension, BlockPos pos) {
        return OccultismStorageMenuAdapter.BACKING_NAMESPACE + dimension + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }
    public String targetId() { return remote != null ? "occultism:storage_remote" : BuiltInRegistries.BLOCK.getKey(accessBlock.getBlockState().getBlock()).toString(); }
    public void invalidate() { valid = false; }
}
