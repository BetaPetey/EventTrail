package io.github.betapetey.eventtrail.inventory.adapters.pocketstorage;

import com.flanks255.pocketstorage.gui.PSUContainer;
import com.flanks255.pocketstorage.inventory.PSUItemHandler;
import com.flanks255.pocketstorage.network.SlotClickPacket;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** One real packet/click, one exact native operation, one actual cell and bounded recipient custody. */
public final class PocketStorageOperations {
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    public enum NativeMethod { INSERT, EXTRACT, SLOTLESS, OTHER }
    private PocketStorageOperations() {}

    /** Called inside the native enqueued work, never before it reaches the server thread. */
    public static void packet(SlotClickPacket packet, IPayloadContext context, Runnable original) {
        Action action = null;
        Action previous = ACTIVE.get();
        try {
            if (previous != null) previous.valid = false;
            if (previous == null && packet != null && context != null && context.flow() == PacketFlow.SERVERBOUND
                    && context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof PSUContainer menu && PocketStorageOpening.human(player)) {
                var binding = PocketStorageMenuAdapter.current(new MenuAdapterContext(menu, player, false)).orElse(null);
                if (binding != null && validSlot(binding.handler, packet.slotID())) {
                    action = new Action(binding, true, packet.slotID());
                    action.before = custody(player.getInventory(), menu.getCarried());
                    action.token = UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu);
                    if (action.token == null) action = null;
                }
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.packet", failure); abort(action); action = null; }
        run(action, previous, original);
    }

    /** The standard Minecraft packet owns its collector; only QUICK_MOVE reaches virtual storage here. */
    public static void clicked(PSUContainer menu, int slot, ClickType click, Player actor, Runnable original) {
        Action action = null;
        Action previous = ACTIVE.get();
        try {
            if (previous != null) previous.valid = false;
            if (previous == null && click == ClickType.QUICK_MOVE && slot >= 0 && slot < 36
                    && actor instanceof ServerPlayer player && PocketStorageOpening.human(player)) {
                var binding = PocketStorageMenuAdapter.current(new MenuAdapterContext(menu, player, false)).orElse(null);
                if (binding != null) {
                    action = new Action(binding, false, -1);
                    action.before = custody(player.getInventory(), menu.getCarried());
                }
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.clicked", failure); action = null; }
        run(action, previous, original);
    }

    private static void run(Action action, Action previous, Runnable original) {
        // Nested native gameplay still executes once, with observation invalidated for both scopes.
        if (action == null) ACTIVE.remove(); else ACTIVE.set(action);
        boolean normal = false;
        try {
            original.run();
            normal = true;
            finish(action);
        } finally {
            if (action != null && !action.finished) abort(action);
            if (action != null && !normal) action.binding.invalidate();
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    private static void finish(Action action) {
        if (action == null) return;
        try {
            boolean valid = ACTIVE.get() == action && action.valid && action.expected == null
                    && action.nativeDepth == 0 && action.binding.current()
                    && action.anchor.equals(action.binding.target().orElse(null));
            if (valid) {
                var after = custody(action.binding.player.getInventory(), action.binding.menu.getCarried());
                valid = conservesOrCounted(action.before, after, action.transfer);
            }
            if (valid && action.transfer != null) {
                Transfer transfer = action.transfer;
                UniversalContainerTracker.recordAuthoritativeTransfer(action.binding.player,
                        transfer.item.stackWithCount(1), transfer.direction, transfer.quantity);
            }
            if (action.token != null) {
                if (valid) UniversalContainerTracker.afterSuccessfulMenuAction(
                        action.binding.player, action.binding.menu, action.token);
                else UniversalContainerTracker.menuActionFailed(action.token);
            } else {
                unrecordedQuickMoveCounted(valid, action.countedMark);
            }
            action.finished = true;
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.finish", failure); }
    }

    /** Only exact calls inside the published menu methods enter this operation boundary. */
    public static ItemStack transfer(PSUContainer menu, PSUItemHandler handler, int slot, ItemStack input,
                                     int requested, boolean simulate, NativeMethod method,
                                     Supplier<ItemStack> original) {
        Action action = ACTIVE.get();
        boolean observed = false;
        ItemStack before = ItemStack.EMPTY;
        ItemStack supplied = ItemStack.EMPTY;
        Expected expected = null;
        try {
            if (action != null) {
                action.operations++;
                if (!action.valid || action.operations != 1 || action.binding.menu != menu
                        || action.binding.handler != handler || simulate || !action.binding.current()
                        || action.expected != null || method == NativeMethod.OTHER
                        || action.custom != (method != NativeMethod.SLOTLESS)) action.valid = false;
                if (action.valid) {
                    int actualSlot = method == NativeMethod.SLOTLESS ? slotlessDestination(handler, input) : slot;
                    if (action.custom && actualSlot != action.requestedSlot) action.valid = false;
                    // A full/no-compatible slotless insertion has no changed cell and cannot commit a transfer.
                    if (actualSlot < 0 && method == NativeMethod.SLOTLESS) {
                        expected = new Expected(method, -1);
                        action.expected = expected;
                    } else if (!validSlot(handler, actualSlot)) action.valid = false;
                    else {
                        before = handler.getStackInSlot(actualSlot).copy();
                        supplied = input == null ? ItemStack.EMPTY : input.copy();
                        expected = new Expected(method, method == NativeMethod.SLOTLESS ? -1 : slot);
                        expected.cell = actualSlot;
                        action.expected = expected;
                        observed = true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.transfer", failure); }

        ItemStack result;
        try { result = original.get(); }
        catch (Throwable gameplayFailure) {
            if (action != null) { action.valid = false; action.expected = null; }
            throw gameplayFailure;
        }
        if (action != null) {
            try {
                if (expected == null || action.expected != expected || expected.calls != 1 || action.nativeDepth != 0)
                    action.valid = false;
                if (action.valid && observed) {
                    ItemStack after = handler.getStackInSlot(expected.cell).copy();
                    action.transfer = prove(before, after, supplied, result, requested, method);
                } else if (action.valid && expected != null && expected.cell < 0
                        && (result == null || input == null || !ItemStack.matches(input, result))) action.valid = false;
            } catch (IllegalArgumentException nonConservingNativeOperation) { action.valid = false; }
            catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.transfer", failure); }
            finally { action.expected = null; }
        }
        return result;
    }

    /** Native API monitor catches unexpected or nested handler mutations inside this human action. */
    public static <T> T nativeMutation(PSUItemHandler handler, NativeMethod method, int slot,
                                        boolean simulate, Supplier<T> original) {
        Action action = ACTIVE.get();
        if (action != null) {
            Expected expected = action.expected;
            if (action.nativeDepth != 0 || expected == null || action.binding.handler != handler || simulate
                    || expected.method != method || expected.slot != slot || ++expected.calls != 1) action.valid = false;
            action.nativeDepth++;
        }
        try { return original.get(); }
        finally { if (action != null) action.nativeDepth--; }
    }

    static boolean validSlot(PSUItemHandler handler, int slot) {
        return handler != null && handler.getClass() == PSUItemHandler.class
                && handler.getSlots() > 0 && handler.getSlots() <= 64 && slot >= 0 && slot < handler.getSlots();
    }

    /** Mirrors only the exact published first-match destination rule, retaining one actual cell. */
    static int slotlessDestination(PSUItemHandler handler, ItemStack incoming) {
        if (handler == null || handler.getClass() != PSUItemHandler.class || handler.getSlots() < 1
                || handler.getSlots() > 64 || incoming == null || incoming.isEmpty()
                || !handler.isItemValid(0, incoming)) return -1;
        int empty = -1;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack cell = handler.getStackInSlot(i);
            if (cell.isEmpty()) { if (empty < 0) empty = i; }
            else if (ItemStack.isSameItemSameComponents(cell, incoming)) return i;
        }
        return empty;
    }

    /** Empty remainder is insufficient: native insert can discard overflow. */
    static Transfer prove(ItemStack before, ItemStack after, ItemStack supplied, ItemStack result,
                          int requested, NativeMethod method) {
        if (before == null || after == null || result == null || before.getCount() < 0
                || after.getCount() < 0 || result.getCount() < 0) throw new IllegalArgumentException("Invalid native result");
        if (method == NativeMethod.EXTRACT) {
            if (result.isEmpty()) {
                if (!ItemStack.matches(before, after)) throw new IllegalArgumentException("Unreported extraction");
                return null;
            }
            if (requested <= 0 || result.getCount() > requested || before.isEmpty()
                    || !same(before, result) || !emptyOrSame(after, result)
                    || (long) before.getCount() - after.getCount() != result.getCount())
                throw new IllegalArgumentException("Extraction did not conserve its actual cell");
            return new Transfer(TransactionDirection.WITHDRAWAL, InventoryItemIdentity.of(result), result.getCount());
        }
        if (method != NativeMethod.INSERT && method != NativeMethod.SLOTLESS)
            throw new IllegalArgumentException("Unsupported native operation");
        if (supplied == null || supplied.isEmpty()) {
            if (!ItemStack.matches(before, after) || !result.isEmpty()) throw new IllegalArgumentException("Invalid empty insert");
            return null;
        }
        if (!emptyOrSame(result, supplied) || result.getCount() > supplied.getCount())
            throw new IllegalArgumentException("Invalid insertion remainder");
        long amount = (long) supplied.getCount() - result.getCount();
        if (amount == 0) {
            if (!ItemStack.matches(before, after)) throw new IllegalArgumentException("Unreported insertion");
            return null;
        }
        if (!emptyOrSame(before, supplied) || after.isEmpty() || !same(after, supplied)
                || (long) after.getCount() - before.getCount() != amount)
            throw new IllegalArgumentException("Void or non-conserving insertion");
        return new Transfer(TransactionDirection.DEPOSIT, InventoryItemIdentity.of(supplied), amount);
    }
    private static boolean same(ItemStack a, ItemStack b) { return ItemStack.isSameItemSameComponents(a, b); }
    private static boolean emptyOrSame(ItemStack a, ItemStack b) { return a.isEmpty() || same(a, b); }

    static Map<InventoryItemIdentity, Long> custody(Inventory inventory, ItemStack cursor) {
        if (inventory == null || inventory.getContainerSize() != 41) throw new IllegalArgumentException("Unbounded player inventory");
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        for (int i = 0; i < 41; i++) add(totals, inventory.getItem(i));
        add(totals, cursor);
        return Map.copyOf(totals);
    }
    private static void add(Map<InventoryItemIdentity, Long> totals, ItemStack stack) {
        if (stack == null || stack.getCount() < 0) throw new IllegalArgumentException("Invalid player custody");
        if (!stack.isEmpty()) totals.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
    }
    /**
     * GF-WARNINGS (B45; r2 MINOR-2 seam): the accounting check of {@link #finish}. A failure is counted and warned
     * under the key (never OFF) and the action records nothing.
     */
    static boolean conservesOrCounted(Map<InventoryItemIdentity, Long> before,
                                      Map<InventoryItemIdentity, Long> after, Transfer transfer) {
        boolean valid = conserves(before, after, transfer);
        if (!valid) RecordingGaps.drop(AdapterKey.POCKET_STORAGE, RecordingGaps.Gap.CONSERVATION_FAILED, null);
        return valid;
    }
    /**
     * GF-WARNINGS r4 (review-02 MINOR-B): a quick-move (no tracker token) that {@link #finish} did not record
     * ({@code recorded} false: one of its checks failed) records nothing, as before, and is now counted once as
     * menu-action-failed under the key (never OFF), unless this action was already counted under the key on this
     * thread since it began ({@code countedMark}): a failed accounting check ({@link #conservesOrCounted}) or an
     * observation failure counts itself there. Returns whether it counted. Writes no row.
     */
    static boolean unrecordedQuickMoveCounted(boolean recorded, long countedMark) {
        if (recorded || AdapterFailSafe.countedOnThisThread(AdapterKey.POCKET_STORAGE) != countedMark) return false;
        RecordingGaps.drop(AdapterKey.POCKET_STORAGE, RecordingGaps.Gap.MENU_ACTION_FAILED, null);
        return true;
    }
    static boolean conserves(Map<InventoryItemIdentity, Long> before,
                             Map<InventoryItemIdentity, Long> after, Transfer transfer) {
        var expected = new HashMap<>(before);
        if (transfer != null) {
            long sign = transfer.direction == TransactionDirection.WITHDRAWAL ? 1 : -1;
            long quantity = Math.addExact(expected.getOrDefault(transfer.item, 0L), sign * transfer.quantity);
            if (quantity < 0) return false;
            if (quantity == 0) expected.remove(transfer.item); else expected.put(transfer.item, quantity);
        }
        return expected.equals(after);
    }
    private static void observationFailed(Action action, String site, Throwable failure) {
        if (action != null) action.valid = false;
        PocketStorageOpening.observationFailed(site, failure);
    }
    private static void abort(Action action) {
        if (action == null || action.token == null) return;
        try { UniversalContainerTracker.menuActionFailed(action.token); }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:PocketStorageOperations.abort", failure); }
    }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }
    record Transfer(TransactionDirection direction, InventoryItemIdentity item, long quantity) {}
    private static final class Expected {
        final NativeMethod method; final int slot;
        int cell = -1; int calls;
        Expected(NativeMethod method, int slot) { this.method = method; this.slot = slot; }
    }
    private static final class Action {
        final PocketStorageBinding binding; final boolean custom; final int requestedSlot;
        final ContainerTarget anchor;
        UniversalContainerTracker.ActionToken token;
        Map<InventoryItemIdentity, Long> before;
        Expected expected; Transfer transfer;
        boolean valid = true; boolean finished; int nativeDepth; int operations;
        /** r4 (MINOR-B): what this thread had counted under the key when the action began. */
        final long countedMark = AdapterFailSafe.countedOnThisThread(AdapterKey.POCKET_STORAGE);
        Action(PocketStorageBinding binding, boolean custom, int slot) {
            this.binding = binding; this.custom = custom; this.requestedSlot = slot;
            this.anchor = binding.target().orElseThrow();
        }
    }
}
