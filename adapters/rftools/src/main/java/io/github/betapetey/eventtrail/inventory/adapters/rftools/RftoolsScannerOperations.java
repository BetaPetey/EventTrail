package io.github.betapetey.eventtrail.inventory.adapters.rftools;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import mcjty.rftoolsstorage.modules.scanner.StorageScannerConfiguration;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerTileEntity;
import mcjty.rftoolsstorage.modules.scanner.network.PacketRequestItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** One native queued request, one output-buffer delivery, one actual accepted player quantity. */
public final class RftoolsScannerOperations {
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private RftoolsScannerOperations() {}

    public static void bind(StorageScannerContainer menu, BlockPos pos, StorageScannerTileEntity scanner, Player actor) {
        try {
            if (!disabled() && actor instanceof ServerPlayer player && menu instanceof RftoolsMenuCarrier carrier)
                RftoolsScannerBinding.capture(player, menu, pos, scanner).ifPresent(carrier::eventtrail$rftoolsBinding);
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:RftoolsScannerOperations.bind", failure); }
    }
    /** Enqueueing alone is not execution authority; this runs within the native queued Runnable. */
    public static void packet(PacketRequestItem packet, IPayloadContext context, Runnable original) {
        Action previous = ACTIVE.get(); Action action = null;
        try {
            if (previous != null) previous.valid = false;
            if (previous == null && !disabled() && packet != null && context != null && context.flow() == PacketFlow.SERVERBOUND
                    && context.player() instanceof ServerPlayer player && human(player)
                    && player.containerMenu instanceof StorageScannerContainer menu) {
                var binding = RftoolsStorageMenuAdapter.current(new MenuAdapterContext(menu, player, false)).orElse(null);
                if (binding != null && requestMatches(packet, binding) && Boolean.TRUE.equals(StorageScannerConfiguration.requestStraightToInventory.get())) {
                    action = new Action(binding, packet);
                    action.before = custody(binding.inventory, menu.getCarried());
                    action.token = UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu);
                    if (action.token == null) action = null;
                }
            }
        } catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.packet", failure); abort(action); action = null; }
        if (action == null) ACTIVE.remove(); else ACTIVE.set(action);
        boolean normal = false;
        try { original.run(); normal = true; finish(action); }
        finally {
            if (action != null && !action.finished) abort(action);
            if (action != null && !normal) action.binding.invalidate();
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
    static boolean admissibleRequest(PacketRequestItem packet) {
        return packet != null && packet.dimensionId() != null && packet.pos() != null && packet.inventoryPos() != null
                && Boolean.FALSE.equals(packet.craftable()) && packet.item() != null && !packet.item().isEmpty()
                && packet.amount() != null && (packet.amount() == -1 || packet.amount() > 0 && packet.amount() <= packet.item().getMaxStackSize());
    }
    static boolean requestMatches(PacketRequestItem packet, RftoolsScannerBinding binding) {
        return admissibleRequest(packet) && packet.dimensionId().equals(binding.level.dimension()) && packet.pos().equals(binding.nativePosition);
    }

    /** Direct native/API/crafting calls have no packet scope and never manufacture a human actor. */
    public static void request(StorageScannerTileEntity scanner, BlockPos source, ItemStack item, int amount, Player player, Runnable original) {
        Action action = ACTIVE.get();
        try {
            if (action != null) {
                if (++action.requests != 1 || action.depth != 0 || action.binding.scanner != scanner || action.binding.player != player
                        || !action.source.equals(source) || action.amount != amount || item == null || item.isEmpty()
                        || !ItemStack.isSameItemSameComponents(action.requested, item) || !action.binding.current()) action.valid = false;
                action.depth++;
            }
        } catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.request", failure); }
        boolean normal = false;
        try { original.run(); normal = true; }
        finally { if (action != null) { action.depth--; if (!normal) action.valid = false; } }
    }

    /** This hook is the exact Inventory.add invocation inside the admitted requestStack method only. */
    public static boolean deliver(StorageScannerTileEntity scanner, Inventory inventory, ItemStack stack, Supplier<Boolean> original) {
        Action action = ACTIVE.get(); ItemStack supplied = ItemStack.EMPTY; boolean observed = false;
        try {
            if (action != null) {
                if (++action.deliveries != 1 || !action.valid || action.depth != 1 || action.requests != 1
                        || scanner != action.binding.scanner || inventory != action.binding.inventory || stack == null || stack.isEmpty()
                        || stack != action.binding.items.getStackInSlot(1) || stack.getCount() > stack.getMaxStackSize()
                        || !ItemStack.isSameItemSameComponents(stack, action.requested) || !action.binding.current()) action.valid = false;
                if (action.valid) { supplied = stack.copy(); observed = true; }
            }
        } catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.deliver", failure); }
        // Native add runs once even when observation was refused or failed.
        boolean result = original.get();
        if (action != null) {
            try {
                if (observed && action.valid) {
                    action.transfer = prove(supplied, stack, result);
                    action.remainder = stack.copy(); action.addResult = result;
                    if (action.binding.items.getStackInSlot(1) != stack) action.valid = false;
                }
            } catch (IllegalArgumentException invalidResult) { action.valid = false; }
            catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.deliver", failure); }
        }
        return result;
    }

    private static void finish(Action action) {
        if (action == null) return;
        try {
            boolean valid = ACTIVE.get() == action && action.valid && action.requests == 1 && action.depth == 0 && action.deliveries <= 1
                    && action.binding.current() && action.anchor.equals(action.binding.target().orElse(null));
            if (valid && action.deliveries == 1) {
                ItemStack output = action.binding.items.getStackInSlot(1);
                valid = action.remainder != null && ItemStack.matches(action.addResult ? ItemStack.EMPTY : action.remainder, output);
            }
            if (valid) {
                valid = conserves(action.before, custody(action.binding.inventory, action.binding.menu.getCarried()), action.transfer);
                // GF-WARNINGS (B45): a failed accounting check is counted and warned under the key (never OFF).
                if (!valid) RecordingGaps.drop(AdapterKey.RFTOOLS_STORAGE, RecordingGaps.Gap.CONSERVATION_FAILED, null);
            }
            if (valid && action.transfer != null) UniversalContainerTracker.recordAuthoritativeTransfer(action.binding.player,
                    action.transfer.item.stackWithCount(1), TransactionDirection.WITHDRAWAL, action.transfer.quantity);
            if (valid) UniversalContainerTracker.afterSuccessfulMenuAction(action.binding.player, action.binding.menu, action.token);
            else UniversalContainerTracker.menuActionFailed(action.token);
            action.finished = true;
        } catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.finish", failure); }
    }
    /** The accepted amount includes any old matching output-buffer residue actually delivered now. */
    static Transfer prove(ItemStack supplied, ItemStack remainder, boolean result) {
        if (supplied == null || supplied.isEmpty() || supplied.getCount() < 1 || supplied.getCount() > supplied.getMaxStackSize()
                || remainder == null || remainder.getCount() < 0 || remainder.getCount() > supplied.getCount()
                || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(supplied, remainder)
                || result && !remainder.isEmpty()) throw new IllegalArgumentException("Unproven native player delivery");
        int accepted = supplied.getCount() - remainder.getCount();
        return accepted == 0 ? null : new Transfer(InventoryItemIdentity.of(supplied), accepted);
    }
    static Map<InventoryItemIdentity,Long> custody(Inventory inventory, ItemStack cursor) {
        if (inventory == null || inventory.getContainerSize() != 41) throw new IllegalArgumentException("Unbounded player custody");
        Map<InventoryItemIdentity,Long> totals = new HashMap<>();
        for (int i = 0; i < 41; i++) add(totals, inventory.getItem(i)); add(totals, cursor); return Map.copyOf(totals);
    }
    private static void add(Map<InventoryItemIdentity,Long> totals, ItemStack stack) {
        if (stack == null || stack.getCount() < 0) throw new IllegalArgumentException("Invalid native custody");
        if (!stack.isEmpty()) totals.merge(InventoryItemIdentity.of(stack), (long)stack.getCount(), Math::addExact);
    }
    static boolean conserves(Map<InventoryItemIdentity,Long> before, Map<InventoryItemIdentity,Long> after, Transfer transfer) {
        var expected = new HashMap<>(before);
        if (transfer != null) expected.merge(transfer.item, transfer.quantity, Math::addExact);
        return expected.equals(after);
    }
    static boolean human(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player) && !player.isSpectator() && !player.isCreative()
                && !player.isRemoved() && player.getServer() != null && player.getServer().isSameThread()
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    static boolean disabled() { return OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(RftoolsStorageMenuAdapter.ID); }
    /** Reports to the fail-safe under RFTOOLS_STORAGE: a missing member switches the key OFF, any other error skips this action. */
    public static void observationFailed(String site, Throwable failure) { AdapterFailSafe.report(AdapterKey.RFTOOLS_STORAGE, site, failure); }
    private static void fail(Action action, String site, Throwable failure) { if (action != null) action.valid = false; observationFailed(site, failure); }
    private static void abort(Action action) {
        if (action == null || action.token == null) return;
        try { UniversalContainerTracker.menuActionFailed(action.token); }
        catch (RuntimeException | LinkageError failure) { fail(action, "adapter:RftoolsScannerOperations.abort", failure); }
    }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }
    record Transfer(InventoryItemIdentity item, long quantity) {}
    private static final class Action {
        final RftoolsScannerBinding binding; final ContainerTarget anchor; final BlockPos source; final ItemStack requested; final int amount;
        Map<InventoryItemIdentity,Long> before; UniversalContainerTracker.ActionToken token; Transfer transfer; ItemStack remainder;
        boolean valid = true, finished, addResult; int requests, deliveries, depth;
        Action(RftoolsScannerBinding binding, PacketRequestItem packet) {
            this.binding = binding; this.anchor = binding.target().orElseThrow(); this.source = packet.inventoryPos().immutable();
            this.requested = packet.item().copyWithCount(1); this.amount = packet.amount();
        }
    }
}
