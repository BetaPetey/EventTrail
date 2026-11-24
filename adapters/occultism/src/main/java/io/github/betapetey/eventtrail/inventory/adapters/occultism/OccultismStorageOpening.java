package io.github.betapetey.eventtrail.inventory.adapters.occultism;

import com.klikli_dev.occultism.common.blockentity.StorageControllerBlockEntity;
import com.klikli_dev.occultism.common.container.storage.*;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.RecordingGaps;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Admission only during the exact native human opener; never from owner or a remembered click. */
public final class OccultismStorageOpening {
    private static final ThreadLocal<Opening> ACTIVE = new ThreadLocal<>();
    private OccultismStorageOpening() {}
    public static <T> T block(Level level, BlockPos pos, Player actor, Supplier<T> original) {
        return open(level, pos, actor, false, original);
    }
    public static <T> T remote(Player actor, Supplier<T> original) {
        return open(actor == null ? null : actor.level(), null, actor, true, original);
    }
    private static <T> T open(Level level, BlockPos pos, Player actor, boolean remote, Supplier<T> original) {
        Opening prior = ACTIVE.get(); Opening opening = null;
        try {
            if (prior != null) prior.valid = false;
            if (prior == null && !disabled() && actor instanceof ServerPlayer player && human(player)
                    && player.level() == level && (remote || pos != null && level.hasChunkAt(pos)))
                opening = new Opening(player, remote ? null : level.getBlockEntity(pos), remote,
                        UniversalContainerTracker.runtimeGeneration());
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:OccultismStorageOpening.open", failure); }
        if (opening == null) ACTIVE.remove(); else ACTIVE.set(opening);
        try { return original.get(); }
        finally { if (prior == null) ACTIVE.remove(); else ACTIVE.set(prior); }
    }
    public static void bind(StorageControllerContainerBase menu) {
        try {
            Opening opening = ACTIVE.get();
            if (opening == null || !opening.valid || opening.used || disabled() || !human(opening.player)
                    || opening.generation != UniversalContainerTracker.runtimeGeneration()) return;
            if (!OccultismStorageBinding.nativeMenu(menu, opening.player)
                    || !(menu instanceof OccultismStorageMenuAccess access)) {
                shapeFailed(menu);
                return;
            }
            StorageControllerBlockEntity controller = null; BlockEntity block = null;
            ItemStack remote = null; int selected = -1;
            if (!opening.remote && menu.getClass() == StorageControllerContainer.class) {
                controller = ((StorageControllerContainer) menu).getStorageController(); block = controller;
            } else if (!opening.remote && menu.getClass() == StableWormholeContainer.class) {
                controller = ((StableWormholeContainer) menu).getStorageController();
                block = ((StableWormholeContainer) menu).getStableWormhole();
            } else if (opening.remote && menu.getClass() == StorageRemoteContainer.class
                    && menu instanceof OccultismStorageRemoteAccess remoteAccess) {
                selected = remoteAccess.eventtrail$occultismSelectedSlot();
                if (selected < 0 || selected >= 41) return; // Curios provider is outside this contract.
                remote = opening.player.getInventory().getItem(selected);
                var link = remote.get(com.klikli_dev.occultism.registry.OccultismDataComponents.LINKED_STORAGE_CONTROLLER);
                if (link == null) return;
                ServerLevel backingLevel = opening.player.getServer().getLevel(link.getDimensionKey());
                if (backingLevel == null || !backingLevel.hasChunkAt(link.getPos())) return;
                if (backingLevel.getBlockEntity(link.getPos()) instanceof StorageControllerBlockEntity found) controller = found;
            } else return;
            if (controller != null && (controller.getClass() != StorageControllerBlockEntity.class
                    || controller.itemStackHandler == null || controller.itemStackHandler.getClass()
                    != com.klikli_dev.occultism.common.misc.StorageControllerMapItemStackHandler.class)) {
                shapeFailed(menu);
                return;
            }
            if (controller == null || !(controller.getLevel() instanceof ServerLevel)
                    || !opening.remote && opening.block != block) return;
            OccultismStorageBinding binding = new OccultismStorageBinding(opening.player, menu, controller, block, remote, selected);
            if (binding.authority(false)) { opening.used = true; access.eventtrail$occultismBinding(binding); }
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:OccultismStorageOpening.bind", failure); }
    }
    /** GF-WARNINGS (B48): only a menu the adapter answers SUPPORTED for (one of its three exact classes) is warned. */
    static void shapeFailed(StorageControllerContainerBase menu) {
        if (menu == null) return;
        Class<?> type = menu.getClass();
        if (type == StorageControllerContainer.class || type == StableWormholeContainer.class
                || type == StorageRemoteContainer.class) {
            RecordingGaps.bindingShapeFailed(AdapterKey.OCCULTISM, "adapter:OccultismStorageOpening.bind");
        }
    }
    public static boolean human(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player) && !player.isSpectator()
                && !player.isCreative() && !player.isRemoved() && player.getServer() != null
                && player.getServer().isSameThread() && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    /** Native remote relinking revokes its original menu even when later linked back. */
    public static void relink(Player player, ItemStack item) {
        if (player != null && player.containerMenu instanceof OccultismStorageMenuAccess access) {
            OccultismStorageBinding binding = access.eventtrail$occultismBinding();
            if (binding != null && binding.remote == item) binding.invalidate();
        }
    }
    public static boolean disabled() { return OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(OccultismStorageMenuAdapter.ID); }
    /** Reports to the fail-safe under OCCULTISM: a missing member switches the key OFF, any other error skips this action. */
    public static void observationFailed(String site, Throwable failure) { AdapterFailSafe.report(AdapterKey.OCCULTISM, site, failure); }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }
    private static final class Opening {
        final ServerPlayer player; final BlockEntity block; final boolean remote; final long generation;
        boolean valid = true, used;
        Opening(ServerPlayer player, BlockEntity block, boolean remote, long generation) {
            this.player = player; this.block = block; this.remote = remote; this.generation = generation;
        }
    }
}
