package io.github.betapetey.eventtrail.inventory.adapters.occultism;

import com.klikli_dev.occultism.common.blockentity.StorageControllerBlockEntity;
import com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase;
import com.klikli_dev.occultism.common.misc.MapItemStackHandler;
import com.klikli_dev.occultism.common.misc.ItemStackKey;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.*;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Native return + one component-exact map key + bounded eventual player/cursor custody. */
public final class OccultismStorageOperations {
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private OccultismStorageOperations() {}
    public static void packet(ServerPlayer player, boolean insert, Runnable original) {
        action(player, null, -1, insert, true, () -> { original.run(); return null; });
    }
    public static ItemStack quickMove(StorageControllerContainerBase menu, Player player, int slot, Supplier<ItemStack> original) {
        return action(player, menu, slot, true, false, original);
    }
    private static <T> T action(Player actor, StorageControllerContainerBase expectedMenu, int slot,
                                boolean insert, boolean custom, Supplier<T> original) {
        Action previous = ACTIVE.get(); Action action = null;
        try {
            if (previous != null) previous.valid = false;
            if (previous == null && actor instanceof ServerPlayer player && OccultismStorageOpening.human(player)
                    && player.containerMenu instanceof StorageControllerContainerBase menu
                    && (custom || menu == expectedMenu && slot >= 11 && slot < 47
                    && menu.getSlot(slot).container == player.getInventory()
                    && menu.getSlot(slot).getContainerSlot() == OccultismStorageBinding.playerIndex(slot))) {
                var binding = OccultismStorageMenuAdapter.current(new MenuAdapterContext(menu, player, false)).orElse(null);
                if (binding != null) {
                    action = new Action(binding, insert);
                    action.before = custody(player.getInventory(), menu.getCarried());
                    if (custom) {
                        action.token = UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu);
                        if (action.token == null) action = null;
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.action", failure); abort(action); action = null; }
        if (action == null) ACTIVE.remove(); else ACTIVE.set(action);
        boolean normal = false;
        try { T result = original.get(); normal = true; finish(action); return result; }
        finally {
            if (action != null && !action.finished) abort(action);
            if (!normal && action != null) action.binding.invalidate();
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
    private static void finish(Action action) {
        if (action == null) return;
        try {
            boolean valid = ACTIVE.get() == action && action.valid && action.expected == null && action.leafDepth == 0
                    && ((OccultismStorageMapAccess) action.binding.handler).eventtrail$occultismMutations().quiescentRevision() == action.mutationEpoch
                    && action.binding.current() && action.anchor.equals(action.binding.target().orElse(null));
            if (valid) {
                valid = conservesOrCounted(action.before, custody(action.binding.player.getInventory(), action.binding.menu.getCarried()), action.transfer);
            }
            valid = valid && ((OccultismStorageMapAccess) action.binding.handler).eventtrail$occultismMutations().quiescentRevision() == action.mutationEpoch;
            if (valid && action.transfer != null) UniversalContainerTracker.recordAuthoritativeTransfer(action.binding.player,
                    action.transfer.item.stackWithCount(1), action.transfer.direction, action.transfer.quantity);
            if (action.token != null) {
                if (valid) UniversalContainerTracker.afterSuccessfulMenuAction(action.binding.player, action.binding.menu, action.token);
                else UniversalContainerTracker.menuActionFailed(action.token);
            } else {
                unrecordedQuickMoveCounted(valid, action.countedMark);
            }
            action.finished = true;
        } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.finish", failure); }
    }
    /** Only an admitted packet or actual player quick-move may own a controller operation. */
    public static int controllerInsert(StorageControllerBlockEntity controller, ItemStack input, boolean simulate, Supplier<Integer> original) {
        Action action = ACTIVE.get(); Expected expected = beginController(action, controller, true, simulate);
        ItemStack supplied = ItemStack.EMPTY;
        try { if (expected != null) supplied = input.copy(); }
        catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.controllerInsert", failure); }
        boolean normal = false;
        try {
            int result = original.get(); normal = true;
            if (expected != null) {
                try {
                    long amount = (long) supplied.getCount() - result;
                    if (result < 0 || amount < 0 || !matches(expected.transfer, supplied, amount, TransactionDirection.DEPOSIT)) action.valid = false;
                    completeController(action, expected);
                } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.controllerInsert", failure); }
            }
            return result;
        } finally { endController(action, expected, normal); }
    }
    public static ItemStack controllerExtract(StorageControllerBlockEntity controller, int requested, boolean simulate, Supplier<ItemStack> original) {
        Action action = ACTIVE.get(); Expected expected = beginController(action, controller, false, simulate);
        boolean normal = false;
        try {
            ItemStack result = original.get(); normal = true;
            if (expected != null) {
                try {
                    if (result == null || result.getCount() < 0 || result.getCount() > Math.max(0, requested)
                            || !matches(expected.transfer, result, result.getCount(), TransactionDirection.WITHDRAWAL)) action.valid = false;
                    completeController(action, expected);
                } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.controllerExtract", failure); }
            }
            return result;
        } finally { endController(action, expected, normal); }
    }
    private static Expected beginController(Action action, StorageControllerBlockEntity controller, boolean insert, boolean simulate) {
        if (action == null) return null;
        try {
            if (!action.valid || action.expected != null || action.leafDepth != 0 || simulate || action.insert != insert
                    || action.binding.controller != controller || !action.binding.current()
                    || ++action.controllerCalls > (insert ? 1 : 2)) { action.valid = false; return null; }
            Expected expected = new Expected(insert); action.expected = expected; return expected;
        } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.beginController", failure); return null; }
    }
    private static void completeController(Action action, Expected expected) {
        if (action.expected != expected || action.leafDepth != 0) action.valid = false;
        if (expected.transfer != null) {
            if (action.transfer != null) action.valid = false;
            else action.transfer = expected.transfer;
        }
    }
    private static void endController(Action action, Expected expected, boolean normal) {
        if (action == null || expected == null) return;
        if (!normal) action.valid = false;
        if (action.expected == expected) action.expected = null; else action.valid = false;
    }
    public static ItemStack insert(MapItemStackHandler handler, ItemStack input, boolean simulate, Supplier<ItemStack> original) {
        return leaf(handler, input, 0, true, simulate, original);
    }
    public static ItemStack extract(MapItemStackHandler handler, ItemStackKey key, int amount, boolean simulate, Supplier<ItemStack> original) {
        return leaf(handler, key == null ? ItemStack.EMPTY : key.stack(), amount, false, simulate, original);
    }
    private static ItemStack leaf(MapItemStackHandler handler, ItemStack stack, int requested, boolean insert,
                                   boolean simulate, Supplier<ItemStack> original) {
        // The retained implementation's simulation branches do not mutate. No scan or simulation snapshots are added.
        if (simulate) return original.get();
        Action action = ACTIVE.get(); Expected expected = action == null ? null : action.expected;
        ItemStack supplied = ItemStack.EMPTY; int before = 0; boolean observe = false;
        long mutation = -1;
        boolean entered = false;
        OccultismStorageMutationWitness witness = null;
        boolean mutationEntered = false;
        try {
            if (handler instanceof OccultismStorageMapAccess access) {
                witness = access.eventtrail$occultismMutations(); mutation = witness.begin(); mutationEntered = true;
            }
            if (action != null) {
                if (!action.valid || expected == null || expected.insert != insert || action.leafDepth != 0
                        || action.binding.handler != handler || handler.keyToCountMap() != action.binding.map
                        || witness == null || witness.writers() != 1 || mutation != action.mutationEpoch + 1) action.valid = false;
                else { supplied = stack.copy(); before = handler.get(supplied); observe = true; }
                action.leafDepth++; entered = true;
            }
        } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.leaf", failure); }
        boolean normal = false;
        try {
            ItemStack result = original.get(); normal = true;
            if (observe) {
                try {
                    Transfer transfer = prove(before, handler.get(supplied), supplied, result, requested, insert);
                    if (transfer != null) {
                        if (expected.transfer != null) action.valid = false;
                        else expected.transfer = transfer;
                    }
                    if (handler.keyToCountMap() != action.binding.map) action.valid = false;
                    if (witness.writers() != 1 || witness.revision() != mutation) action.valid = false;
                } catch (IllegalArgumentException contradictory) { action.valid = false; }
                catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.leaf", failure); }
            }
            return result;
        } finally {
            if (mutationEntered) {
                try {
                    long completed = witness.end();
                    if (observe) {
                        if (completed != mutation + 1 || witness.quiescentRevision() != completed) action.valid = false;
                        action.mutationEpoch = completed;
                    }
                } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.leaf", failure); }
            }
            if (action != null) { if (entered) action.leafDepth--; if (!normal) action.valid = false; }
        }
    }
    /** Actorless writes, including spirit capability calls, never gain authority from an enclosing human call. */
    public static void unexpectedMutation(MapItemStackHandler handler, Runnable original) {
        Action action = ACTIVE.get();
        if (action != null) action.valid = false;
        OccultismStorageMutationWitness witness = null; boolean entered = false;
        try {
            if (handler instanceof OccultismStorageMapAccess access) { witness = access.eventtrail$occultismMutations(); witness.begin(); entered = true; }
        }
        catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.unexpectedMutation", failure); }
        try { original.run(); }
        finally {
            if (entered) {
                try { witness.end(); } catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.unexpectedMutation", failure); }
            }
        }
    }
    static Transfer prove(int before, int after, ItemStack supplied, ItemStack result, int requested, boolean insert) {
        if (before < 0 || after < 0 || supplied == null || result == null || result.getCount() < 0)
            throw new IllegalArgumentException("Invalid native map quantity");
        long quantity;
        if (insert) {
            if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, supplied))
                throw new IllegalArgumentException("Foreign remainder");
            quantity = (long) supplied.getCount() - result.getCount();
            if (quantity < 0 || (long) after - before != quantity) throw new IllegalArgumentException("Uncommitted insertion");
        } else {
            quantity = result.getCount();
            if (quantity > Math.max(0, requested) || !result.isEmpty() && !ItemStack.isSameItemSameComponents(result, supplied)
                    || (long) before - after != quantity) throw new IllegalArgumentException("Uncommitted extraction");
        }
        return quantity == 0 ? null : new Transfer(insert ? TransactionDirection.DEPOSIT : TransactionDirection.WITHDRAWAL,
                InventoryItemIdentity.of(insert ? supplied : result), quantity);
    }
    private static boolean matches(Transfer transfer, ItemStack stack, long quantity, TransactionDirection direction) {
        return quantity == 0 ? transfer == null : transfer != null && quantity > 0 && !stack.isEmpty()
                && transfer.quantity == quantity && transfer.direction == direction && transfer.item.equals(InventoryItemIdentity.of(stack));
    }
    static Map<InventoryItemIdentity, Long> custody(Inventory inventory, ItemStack cursor) {
        if (inventory == null || inventory.getContainerSize() != 41) throw new IllegalArgumentException("Unbounded player custody");
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        for (int i = 0; i < 41; i++) add(totals, inventory.getItem(i));
        add(totals, cursor); return Map.copyOf(totals);
    }
    private static void add(Map<InventoryItemIdentity, Long> totals, ItemStack stack) {
        if (stack == null || stack.getCount() < 0) throw new IllegalArgumentException("Invalid player custody");
        if (!stack.isEmpty()) totals.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
    }
    /**
     * GF-WARNINGS (B45; r2 MINOR-2 seam): the accounting check of {@link #finish}. A failure is counted and warned
     * under the key (never OFF) and the action records nothing.
     */
    static boolean conservesOrCounted(Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after,
                                      Transfer transfer) {
        boolean valid = conserves(before, after, transfer);
        if (!valid) RecordingGaps.drop(AdapterKey.OCCULTISM, RecordingGaps.Gap.CONSERVATION_FAILED, null);
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
        if (recorded || AdapterFailSafe.countedOnThisThread(AdapterKey.OCCULTISM) != countedMark) return false;
        RecordingGaps.drop(AdapterKey.OCCULTISM, RecordingGaps.Gap.MENU_ACTION_FAILED, null);
        return true;
    }
    static boolean conserves(Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after, Transfer transfer) {
        var expected = new HashMap<>(before);
        if (transfer != null) {
            long quantity = Math.addExact(expected.getOrDefault(transfer.item, 0L),
                    transfer.direction == TransactionDirection.WITHDRAWAL ? transfer.quantity : -transfer.quantity);
            if (quantity < 0) return false;
            if (quantity == 0) expected.remove(transfer.item); else expected.put(transfer.item, quantity);
        }
        return expected.equals(after);
    }
    private static void failed(Action action, String site, Throwable failure) { if (action != null) action.valid = false; OccultismStorageOpening.observationFailed(site, failure); }
    private static void abort(Action action) {
        if (action != null && action.token != null) {
            try { UniversalContainerTracker.menuActionFailed(action.token); }
            catch (RuntimeException | LinkageError failure) { failed(action, "adapter:OccultismStorageOperations.abort", failure); }
        }
    }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }
    record Transfer(TransactionDirection direction, InventoryItemIdentity item, long quantity) {}
    private static final class Expected { final boolean insert; Transfer transfer; Expected(boolean insert) { this.insert = insert; } }
    private static final class Action {
        final OccultismStorageBinding binding; final boolean insert; final ContainerTarget anchor;
        Map<InventoryItemIdentity, Long> before; Transfer transfer; Expected expected;
        UniversalContainerTracker.ActionToken token;
        int controllerCalls, leafDepth; boolean valid = true, finished;
        long mutationEpoch;
        final long countedMark = AdapterFailSafe.countedOnThisThread(AdapterKey.OCCULTISM);
        Action(OccultismStorageBinding binding, boolean insert) {
            this.binding = binding; this.insert = insert; this.anchor = binding.target().orElseThrow();
            this.mutationEpoch = ((OccultismStorageMapAccess) binding.handler).eventtrail$occultismMutations().quiescentRevision();
            if (mutationEpoch < 0) valid = false;
        }
    }
}
