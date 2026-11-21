package io.github.betapetey.eventtrail.inventory.adapters.rftools;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import mcjty.lib.container.GenericItemHandler;
import mcjty.rftoolsstorage.modules.scanner.StorageScannerModule;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.Optional;
import java.util.UUID;

/** The current placed scanner is a proxy endpoint; underlying inventories are never snapshotted. */
public final class RftoolsScannerBinding {
    final ServerPlayer player;
    final StorageScannerContainer menu;
    final StorageScannerTileEntity scanner;
    final GenericItemHandler items;
    final Inventory inventory;
    final ServerLevel level;
    final BlockPos nativePosition;
    private final long generation;
    private final long loadEpoch;
    private final UUID runtimeIdentity;
    private final AuditBlockLocations.Frame frame;
    private boolean valid = true;
    private RftoolsScannerBinding(ServerPlayer player, StorageScannerContainer menu, StorageScannerTileEntity scanner,
            AuditBlockLocations.Frame frame) {
        this.player = player; this.menu = menu; this.scanner = scanner; this.items = scanner.getItems(); this.inventory = player.getInventory();
        this.level = player.serverLevel(); this.nativePosition = scanner.getBlockPos().immutable(); this.frame = frame;
        this.generation = UniversalContainerTracker.runtimeGeneration(); var carrier = (RftoolsScannerCarrier)scanner;
        this.loadEpoch = carrier.eventtrail$rftoolsLoadEpoch(); this.runtimeIdentity = carrier.eventtrail$rftoolsRuntimeIdentity();
    }
    static Optional<RftoolsScannerBinding> capture(ServerPlayer player, StorageScannerContainer menu, BlockPos pos, StorageScannerTileEntity scanner) {
        if (!RftoolsScannerOperations.human(player) || menu == null || menu.getClass() != StorageScannerContainer.class
                || scanner == null) return Optional.empty();
        if (scanner.getClass() != StorageScannerTileEntity.class || !(scanner instanceof RftoolsScannerCarrier)) {
            // GF-WARNINGS (B48): the exact scanner menu (match answers SUPPORTED) on a scanner of another shape.
            RecordingGaps.bindingShapeFailed(AdapterKey.RFTOOLS_STORAGE, "adapter:RftoolsScannerBinding.capture");
            return Optional.empty();
        }
        if (scanner.getLevel() != player.serverLevel() || !scanner.getBlockPos().equals(pos)
                || menu.getBe() != scanner || menu.getPlayer() != player || !menu.getPos().equals(pos)) return Optional.empty();
        return AuditBlockLocations.capture(player.serverLevel(), pos).filter(RftoolsScannerBinding::stationaryFrame)
                .map(frame -> new RftoolsScannerBinding(player, menu, scanner, frame)).filter(RftoolsScannerBinding::currentOwner);
    }
    /**
     * The RFTools Storage hooks (reload epoch, menu binding, request and delivery) run only while the RFTOOLS_STORAGE
     * key is active. A binding is therefore current only while that key is active, so a skipped reload can never keep
     * a scanner binding open. OFF lasts until restart, and a restart changes the runtime generation, so the binding
     * stays invalid.
     */
    static boolean scannerHooksRunning() {
        try {
            return AdapterFailSafe.active(AdapterKey.RFTOOLS_STORAGE);
        } catch (Throwable unavailable) {
            return false;
        }
    }
    private boolean currentOwner() {
        if (!valid) return false;
        var carrier = (RftoolsScannerCarrier)scanner;
        valid = scannerHooksRunning() && !RftoolsScannerOperations.disabled() && RftoolsScannerOperations.human(player)
                && player.level() == level && player.getInventory() == inventory && generation == UniversalContainerTracker.runtimeGeneration()
                && loadEpoch != Long.MAX_VALUE && loadEpoch == carrier.eventtrail$rftoolsLoadEpoch()
                && runtimeIdentity != null && runtimeIdentity.equals(carrier.eventtrail$rftoolsRuntimeIdentity())
                && !scanner.isRemoved() && scanner.getLevel() == level && scanner.getBlockPos().equals(nativePosition)
                && level.hasChunkAt(nativePosition) && level.getBlockEntity(nativePosition) == scanner && scanner.getItems() == items
                && scanner.getBlockState().getBlock() == StorageScannerModule.STORAGE_SCANNER.block().get()
                && menu.getBe() == scanner && menu.getPlayer() == player && menu.getPos().equals(nativePosition)
                && menu.getType() == StorageScannerModule.CONTAINER_STORAGE_SCANNER.get()
                && scanner.canPlayerAccess(player) && stationaryFrame(frame);
        return valid;
    }
    public boolean current() {
        try {
            if (!valid) return false;
            if (player.containerMenu != menu || !(menu instanceof RftoolsMenuCarrier carrier)
                    || carrier.eventtrail$rftoolsBinding() != this || !currentOwner()) valid = false;
            else if (!nativeMenu(menu, player, items)) {
                valid = false;
                // GF-WARNINGS (B48): a bound scanner menu (match answered SUPPORTED) whose layout fails the shape check.
                RecordingGaps.bindingShapeFailed(AdapterKey.RFTOOLS_STORAGE, "adapter:RftoolsScannerBinding.current");
            }
        } catch (RuntimeException | LinkageError failure) { valid = false; RftoolsScannerOperations.observationFailed("adapter:RftoolsScannerBinding.current", failure); }
        return valid;
    }
    /** Exact native owner layout; visible ghost/player slots grant no storage custody. */
    static boolean nativeMenu(StorageScannerContainer menu, ServerPlayer player, GenericItemHandler items) {
        if (menu == null || menu.getClass() != StorageScannerContainer.class || player == null || items == null
                || menu.getPlayer() != player || menu.getInventory("container") != items || items.getSlots() != 3 || menu.slots.size() != 48
                || !(menu.getInventory("player") instanceof InvWrapper inventory) || inventory.getClass() != InvWrapper.class
                || inventory.getInv() != player.getInventory()) return false;
        return menu.getSlotByInventoryAndIndex("container", 0) == menu.slots.get(0)
                && menu.getSlotByInventoryAndIndex("container", 1) == menu.slots.get(1);
    }
    public Optional<ContainerTarget> target() {
        if (!current()) return Optional.empty();
        return frame.currentLocation().map(location -> new ContainerTarget(location.dimensionId().toString(),
                location.position().getX(), location.position().getY(), location.position().getZ(), "Storage Scanner",
                AccessKind.WIRED_NETWORK, BackingContextKind.PROXY,
                backingKey(level.dimension().location().toString(), nativePosition, runtimeIdentity, generation, loadEpoch)));
    }
    static String backingKey(String dimension, BlockPos pos, UUID identity, long generation, long epoch) {
        return "rftoolsstorage:1.21-6.0.5:scanner|" + dimension + '|' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ()
                + '|' + identity + '|' + generation + '|' + epoch;
    }
    /** This exact scanner contract admits ordinary placed access only, never moving-plot composition. */
    static boolean stationaryFrame(AuditBlockLocations.Frame frame) {
        return frame != null && !frame.moving() && frame.isCurrent();
    }
    void invalidate() { valid = false; }
}
