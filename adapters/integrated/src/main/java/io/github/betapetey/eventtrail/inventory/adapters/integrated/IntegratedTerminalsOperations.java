package io.github.betapetey.eventtrail.inventory.adapters.integrated;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.action.AuthoritativeTransactionCollector;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.cyclops.commoncapabilities.api.ingredient.IngredientComponent;
import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;
import org.cyclops.commoncapabilities.ingredient.storage.IngredientComponentStorageWrapperHandlerItemStack.ComponentStorageWrapper;
import org.cyclops.integrateddynamics.api.part.PartPos;
import org.cyclops.integrateddynamics.core.network.IngredientChannelAdapter;
import org.cyclops.integrateddynamics.core.network.IngredientChannelIndexed;
import org.cyclops.integratedterminals.api.terminalstorage.TerminalClickType;
import org.cyclops.integratedterminals.capability.ingredient.IngredientComponentTerminalStorageHandlerItemStack;
import org.cyclops.integratedterminals.core.terminalstorage.TerminalStorageTabIngredientComponentServer;
import org.cyclops.integratedterminals.inventory.container.ContainerTerminalStoragePart;
import org.cyclops.integratedterminals.network.packet.TerminalStorageIngredientSlotClickPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Packet -> native channel -> actual finite cell -> player custody. No observation enumerates a network. */
public final class IntegratedTerminalsOperations {
    static final int MAX_OPERATIONS = AuthoritativeTransactionCollector.MAX_TRANSFERS_PER_ACTION;
    static final int FINITE_LEAF_SLOTS = 27;
    static final int MAX_NATIVE_LEAF_QUANTITY = 64;
    static final int MAX_SIMULATED_CELLS_PER_LEAF = FINITE_LEAF_SLOTS;
    // Legacy CC chunks committed extraction by EMPTY.getMaxStackSize() (1); current CC
    // uses the source stack's maximum. Keep the conservative bound for both families:
    // at most 27 simulation reads, 64 delivered units and 27 terminating empty probes.
    static final int MAX_COMMITTED_CELLS_PER_LEAF = FINITE_LEAF_SLOTS * 2 + MAX_NATIVE_LEAF_QUANTITY;
    // 512 positioned invocations, each with at most 118 inline native cell calls.
    // Calls are observed inline; no cell map or history is retained across invocations.
    static final int MAX_OBSERVED_CELLS_PER_ACTION = MAX_OPERATIONS * MAX_COMMITTED_CELLS_PER_LEAF;
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private IntegratedTerminalsOperations() { }

    // Family lookup by the "integratedterminals-" prefix: the legacy id finds the same spec key as the current and no-variant ids.
    public static boolean disabled() { return OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(IntegratedTerminalsMenuAdapter.ADAPTER_ID); }
    /** Reports to the fail-safe under INTEGRATED_TERMINALS: a missing member switches the key OFF, any other error skips this action. */
    public static void observationFailed(String site, Throwable failure) { AdapterFailSafe.report(AdapterKey.INTEGRATED_TERMINALS, site, failure); }
    private static void observationFailed(Action action, String site, Throwable failure) { if (action != null) { action.valid = false; action.reported = true; } observationFailed(site, failure); }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }

    static int channel(ContainerTerminalStoragePart menu) {
        Action action = ACTIVE.get();
        return action != null && action.binding.menu == menu ? action.channel : menu.getSelectedChannel();
    }

    public static void packet(TerminalStorageIngredientSlotClickPacket<?> packet,
            TerminalStorageTabIngredientComponentServer<?, ?> tab, AbstractContainerMenu menu,
            ServerPlayer player, TerminalClickType click, int channel, int playerSlot, long moveQuantity,
            Runnable original) {
        Action previous = ACTIVE.get();
        if (previous != null) previous.valid = false;
        Action action = null;
        try {
            if (previous == null && !disabled() && packet.getComponent() == IngredientComponent.ITEMSTACK
                    && tab != null && tab.getClass() == TerminalStorageTabIngredientComponentServer.class
                    && tab instanceof IntegratedTabAccess access && access.eventtrail$integratedComponent() == IngredientComponent.ITEMSTACK
                    && access.eventtrail$integratedPlayer() == player && channel >= -1 && supportedClick(click)
                    && menu instanceof ContainerTerminalStoragePart terminal
                    && terminal.getTabServer("minecraft:itemstack") == tab && playerSlots(terminal, player.getInventory())) {
                var binding = IntegratedTerminalsMenuAdapter.current(new MenuAdapterContext(menu, player, false));
                Object handler = IngredientComponent.ITEMSTACK.getCapability(
                        org.cyclops.integratedterminals.Capabilities.IngredientComponentTerminalStorageHandler.INGREDIENT).orElse(null);
                if (binding != null && handler != null && handler.getClass() == IngredientComponentTerminalStorageHandlerItemStack.class
                        && tab.getNetwork() == binding.network && tab.getIngredientNetwork() == binding.items
                        && (!slotDirected(click) || playerSlot >= 0 && playerSlot < 41)
                        && (click != TerminalClickType.PLAYER_PLACE_STORAGE || moveQuantity > 0 && moveQuantity <= menu.getCarried().getCount())) {
                    action = new Action(binding, tab, (IngredientComponentTerminalStorageHandlerItemStack) handler, click, channel);
                    action.before = custody(player.getInventory(), menu.getCarried());
                    action.target = binding.target(channel).orElse(null);
                    if (action.target == null) action = null;
                }
            }
        } catch (ProofMismatch expected) { action = null; }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.packet", failure); action = null; }
        if (action == null) ACTIVE.remove(); else ACTIVE.set(action);
        boolean normal = false;
        try {
            if (action != null) {
                try { action.token = UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu); }
                catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.packetToken", failure); }
                if (action.token == null) action.valid = false;
            }
            original.run();
            normal = true;
            if (action != null) finish(action);
        } finally {
            if (action != null && !action.finished) {
                UniversalContainerTracker.menuActionFailed(action.token);
                if (!normal) action.binding.invalidate();
            }
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    private static void finish(Action action) {
        try {
            boolean valid = ACTIVE.get() == action && action.valid && action.channelScope == null && action.leaf == null
                    && action.binding.current() && action.tab.getNetwork() == action.binding.network
                    && action.tab.getIngredientNetwork() == action.binding.items
                    && action.binding.menu.getTabServer("minecraft:itemstack") == action.tab
                    && (action.nativeStorage == null || IntegratedStorageAuthority.indexed(action.nativeStorage)
                            == action.nativeIndexed)
                    && action.target.equals(action.binding.target(action.channel).orElse(null));
            if (!valid) countRefusal(action.token != null, reportedSince(action.reported, action.countedMark),
                    ACTIVE.get() == action && action.valid
                    && action.channelScope == null && action.leaf == null);
            if (valid) {
                valid = conserves(action.before, custody(action.binding.player.getInventory(), action.binding.menu.getCarried()),
                        action.item, action.direction, action.quantity);
                // GF-WARNINGS (B45): a failed accounting check is counted and warned under the key (never OFF).
                if (!valid) RecordingGaps.drop(AdapterKey.INTEGRATED_TERMINALS, RecordingGaps.Gap.CONSERVATION_FAILED, null);
            }
            if (valid && action.quantity > 0) UniversalContainerTracker.recordAuthoritativeTransfer(action.binding.player,
                    action.item.stackWithCount(1), action.direction, action.quantity);
            if (valid) UniversalContainerTracker.afterSuccessfulMenuAction(action.binding.player, action.binding.menu, action.token);
            else UniversalContainerTracker.menuActionFailed(action.token);
            action.finished = true;
        } catch (ProofMismatch expected) {
            action.valid = false;
            countRefusal(action.token != null, reportedSince(action.reported, action.countedMark), false);
        }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.finish", failure); }
    }

    /** r2 MINOR-3: the detail of a finished action whose terminal, tab, network, storage or target changed. */
    static final String TERMINAL_CHANGED = "terminal-changed";
    /** r2 MINOR-3: the detail of a finished action whose native calls could not be proven (a proof refusal). */
    static final String OPERATION_UNPROVEN = "operation-unproven";

    /**
     * GF-WARNINGS r2 (MINOR-3): a finished action that failed its validity checks records nothing, as before, and is
     * now counted under the key (never OFF). {@code begun} is false when the tracker gave no token (its own refusal);
     * {@code reported} is true when an observation failure already went to the fail-safe; {@code context} is true when
     * the action's own proof held and only its terminal context changed.
     */
    static void countRefusal(boolean begun, boolean reported, boolean context) {
        if (!begun || reported) return;
        if (context) {
            RecordingGaps.drop(AdapterKey.INTEGRATED_TERMINALS, RecordingGaps.Gap.AUTHORITATIVE_CONTEXT_CHANGED,
                    TERMINAL_CHANGED);
        } else {
            RecordingGaps.drop(AdapterKey.INTEGRATED_TERMINALS, RecordingGaps.Gap.CONSERVATION_FAILED, OPERATION_UNPROVEN);
        }
    }

    /**
     * GF-WARNINGS r4 (review-02 NIT-6): whether this action was already counted under the key: its own observation
     * failure ({@code reported}), or any count under the key on this thread since it began ({@code countedMark}), for
     * example a hook's fail-safe report. Then {@link #countRefusal} adds no second count for the same action.
     */
    static boolean reportedSince(boolean reported, long countedMark) {
        return reported || AdapterFailSafe.countedOnThisThread(AdapterKey.INTEGRATED_TERMINALS) != countedMark;
    }

    /** Exactly the item handler's native calls, including its simulations; direct automation never opens this scope. */
    public static Object channelTransfer(IngredientComponentTerminalStorageHandlerItemStack handler,
            IIngredientComponentStorage<?, ?> storage, AbstractContainerMenu menu, Object supplied,
            Object match, boolean simulate, boolean insert, Supplier<Object> original) {
        Action action = ACTIVE.get();
        if (action == null || !action.valid) return original.get();
        ChannelScope scope = null;
        try {
            // Only a call of this action's own handler and menu looks the channel up: there, a storage the chosen
            // variant does not fit switches the family OFF with one warning while no own call has fitted since server
            // start, and after the first fit is one counted, warned skip with the key ON (first-fit latch, M-1).
            boolean own = ++action.operations <= MAX_OPERATIONS && action.channelScope == null && action.leaf == null
                    && action.handler == handler && action.binding.menu == menu && action.binding.current();
            var indexed = own ? IntegratedStorageAuthority.transferChannel(storage) : null;
            if (!own || indexed == null
                    || action.nativeStorage != null && (action.nativeStorage != storage || action.nativeIndexed != indexed)
                    || indexed.getNetwork() != action.binding.items || indexed.getChannel() != action.channel
                    || indexed.getComponent() != IngredientComponent.ITEMSTACK || !(supplied instanceof ItemStack input)
                    || input.getCount() < 0 || insert != (action.direction == TransactionDirection.DEPOSIT)
                    || !insert && !Integer.valueOf(5).equals(match)
                    || insert && !action.binding.items.getInsertPreConsumers().isEmpty()) action.valid = false;
            else {
                action.nativeStorage = storage; action.nativeIndexed = indexed;
                scope = new ChannelScope(indexed, input.copy(), insert, simulate);
                action.channelScope = scope;
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.channelTransfer", failure); }
        Object result;
        try { result = original.get(); }
        catch (Throwable gameplayFailure) { action.valid = false; action.channelScope = null; throw gameplayFailure; }
        try {
            if (scope != null && action.valid) {
                if (action.channelScope != scope || action.leaf != null || !action.binding.current()
                        || IntegratedStorageAuthority.indexed(storage) != scope.receiver
                        || !(result instanceof ItemStack returned)) action.valid = false;
                else {
                    long moved = returnedQuantity(scope.input, returned, insert);
                    if (!simulate) {
                        if (moved != scope.quantity) action.valid = false;
                        else if (moved > 0) {
                            InventoryItemIdentity identity = InventoryItemIdentity.of(insert ? scope.input : returned);
                            if (action.item != null && !action.item.equals(identity)) action.valid = false;
                            else { action.item = identity; action.quantity = Math.addExact(action.quantity, moved); }
                        }
                    }
                    if (insert && !action.binding.items.getInsertPreConsumers().isEmpty()) action.valid = false;
                }
            }
        } catch (ProofMismatch expected) { action.valid = false; }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.channelTransfer", failure); }
        finally { if (scope != null && action.channelScope == scope) action.channelScope = null; }
        return result;
    }

    /** Receives only the native operation's actual positioned storage and its native PartPos local. */
    public static Object positionedTransfer(IngredientChannelAdapter<?, ?> channel, IIngredientComponentStorage<?, ?> storage,
            PartPos position, Object supplied, Object match, boolean simulate, boolean insert, Supplier<Object> original) {
        Action action = ACTIVE.get();
        if (action == null || !action.valid) return original.get();
        ChannelScope parent = action.channelScope;
        LeafScope leaf = null;
        try {
            if (++action.positions > MAX_OPERATIONS || parent == null || parent.receiver != channel || parent.insert != insert
                    || parent.simulate && !simulate || action.leaf != null || !(supplied instanceof ItemStack input)
                    || input.getCount() < 0 || input.getCount() > MAX_NATIVE_LEAF_QUANTITY
                    || !insert && !Integer.valueOf(5).equals(match)
                    || storage == null || storage.getClass() != ComponentStorageWrapper.class
                    || !(storage instanceof IntegratedComponentStorageAccess access)
                    || !(access.eventtrail$integratedItemHandler() instanceof InvWrapper inv) || inv.getClass() != InvWrapper.class) action.valid = false;
            else {
                leaf = LeafScope.capture(action, storage, position, inv, input.copy(), insert, simulate);
                if (leaf == null) action.valid = false; else action.leaf = leaf;
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.positionedTransfer", failure); }
        Object result;
        try { result = original.get(); }
        catch (Throwable gameplayFailure) { action.valid = false; action.leaf = null; throw gameplayFailure; }
        try {
            if (leaf != null && action.valid) {
                if (action.leaf != leaf || !leaf.current() || !(result instanceof ItemStack returned)) action.valid = false;
                else if (!simulate) {
                    long moved = returnedQuantity(leaf.input, returned, insert);
                    if (moved != leaf.quantity) action.valid = false;
                    else parent.quantity = Math.addExact(parent.quantity, moved);
                }
            }
        } catch (ProofMismatch expected) { action.valid = false; }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.positionedTransfer", failure); }
        finally { if (leaf != null && action.leaf == leaf) action.leaf = null; }
        return result;
    }

    /** No whole leaf snapshot: only the native supplied slot is read before and after its actual invocation. */
    public static ItemStack cell(InvWrapper handler, int slot, ItemStack supplied, int requested,
            boolean simulate, boolean insert, Supplier<ItemStack> original) {
        Action action = ACTIVE.get();
        if (action == null || !action.valid) return original.get();
        LeafScope leaf = action.leaf;
        ItemStack before = null, input = null;
        boolean owns = false;
        try {
            if (leaf == null || leaf.handler != handler || leaf.cellActive
                    || slot < 0 || slot >= FINITE_LEAF_SLOTS || leaf.insert != insert || leaf.simulate && !simulate
                    || !leaf.cells.enter()
                    || !leaf.current() || insert && (supplied == null || supplied.getCount() < 0 || supplied.getCount() > MAX_NATIVE_LEAF_QUANTITY)
                    || !insert && (requested < 0 || requested > MAX_NATIVE_LEAF_QUANTITY)) action.valid = false;
            else {
                leaf.cellActive = true; owns = true;
                before = handler.getInv().getItem(slot).copy(); input = insert ? supplied.copy() : ItemStack.EMPTY;
            }
        } catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.cell", failure); }
        ItemStack result;
        try { result = original.get(); }
        catch (Throwable gameplayFailure) {
            action.valid = false;
            if (owns) leaf.cellActive = false;
            throw gameplayFailure;
        }
        try {
            if (owns && action.valid) {
                if (!leaf.current() || action.leaf != leaf) action.valid = false;
                else {
                    ItemStack after = handler.getInv().getItem(slot).copy();
                    long moved = proveCell(before, after, input, result, requested, simulate, insert);
                    if (!simulate) {
                        ItemStack identity = insert ? input : result;
                        if (moved > 0 && !ItemStack.isSameItemSameComponents(identity, leaf.input)) action.valid = false;
                        else leaf.quantity = Math.addExact(leaf.quantity, moved);
                    }
                }
            }
        } catch (ProofMismatch expected) { action.valid = false; }
        catch (RuntimeException | LinkageError failure) { observationFailed(action, "adapter:IntegratedTerminalsOperations.cell", failure); }
        finally { if (owns) leaf.cellActive = false; }
        return result;
    }

    static long returnedQuantity(ItemStack supplied, ItemStack returned, boolean insert) {
        if (supplied == null || returned == null || supplied.getCount() < 0 || returned.getCount() < 0
                || !returned.isEmpty() && !ItemStack.isSameItemSameComponents(supplied, returned)
                || returned.getCount() > supplied.getCount()) throw new ProofMismatch("Invalid native quantity or identity");
        return insert ? (long) supplied.getCount() - returned.getCount() : returned.getCount();
    }

    static long proveCell(ItemStack before, ItemStack after, ItemStack supplied, ItemStack result,
            int requested, boolean simulate, boolean insert) {
        if (before == null || after == null || result == null || before.getCount() < 0 || after.getCount() < 0 || result.getCount() < 0)
            throw new ProofMismatch("Invalid finite cell");
        if (simulate) {
            if (!ItemStack.matches(before, after)) throw new ProofMismatch("Simulation changed custody");
            return 0;
        }
        if (insert) {
            long moved = returnedQuantity(supplied, result, true);
            if (moved == 0) {
                if (!ItemStack.matches(before, after)) throw new ProofMismatch("Unreported insertion");
            } else if (!emptyOrSame(before, supplied) || !ItemStack.isSameItemSameComponents(after, supplied)
                    || (long) after.getCount() - before.getCount() != moved) throw new ProofMismatch("Nonconserving insertion");
            return moved;
        }
        if (requested < 0 || result.getCount() > requested || result.getCount() > before.getCount())
            throw new ProofMismatch("Invalid extraction quantity");
        if (result.isEmpty()) {
            if (!ItemStack.matches(before, after)) throw new ProofMismatch("Unreported extraction");
            return 0;
        }
        if (!ItemStack.isSameItemSameComponents(before, result) || !emptyOrSame(after, result)
                || (long) before.getCount() - after.getCount() != result.getCount()) throw new ProofMismatch("Nonconserving extraction");
        return result.getCount();
    }

    private static boolean emptyOrSame(ItemStack a, ItemStack b) { return a.isEmpty() || ItemStack.isSameItemSameComponents(a, b); }
    static boolean supportedClick(TerminalClickType click) { return click != null && click != TerminalClickType.STORAGE_PLACE_WORLD; }
    private static boolean slotDirected(TerminalClickType click) {
        return click == TerminalClickType.STORAGE_PLACE_PLAYER || click == TerminalClickType.PLAYER_QUICK_MOVE
                || click == TerminalClickType.PLAYER_QUICK_MOVE_INCREMENTAL;
    }
    static TransactionDirection direction(TerminalClickType click) {
        return switch (click) {
            case PLAYER_PLACE_STORAGE, PLAYER_QUICK_MOVE, PLAYER_QUICK_MOVE_INCREMENTAL -> TransactionDirection.DEPOSIT;
            case STORAGE_PLACE_PLAYER, STORAGE_QUICK_MOVE, STORAGE_QUICK_MOVE_INCREMENTAL -> TransactionDirection.WITHDRAWAL;
            default -> throw new IllegalArgumentException("Excluded native click");
        };
    }

    static boolean playerSlots(ContainerTerminalStoragePart menu, Inventory inventory) {
        if (inventory == null || inventory.getContainerSize() != 41 || menu.slots.size() < 41) return false;
        boolean[] cells = new boolean[41];
        for (int i = 0; i < 41; i++) {
            var slot = menu.slots.get(i); int cell = slot.getContainerSlot();
            if (slot.container != inventory || slot.index != i || cell < 0 || cell >= 41 || cells[cell]) return false;
            cells[cell] = true;
        }
        return true;
    }

    static Map<InventoryItemIdentity, Long> custody(Inventory inventory, ItemStack cursor) {
        if (inventory == null || inventory.getContainerSize() != 41) throw new ProofMismatch("Unbounded player custody");
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        for (int i = 0; i < 41; i++) add(totals, inventory.getItem(i));
        add(totals, cursor); return Map.copyOf(totals);
    }
    private static void add(Map<InventoryItemIdentity, Long> totals, ItemStack stack) {
        if (stack == null || stack.getCount() < 0) throw new ProofMismatch("Invalid player custody");
        if (!stack.isEmpty()) totals.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
    }
    static boolean conserves(Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after,
            InventoryItemIdentity item, TransactionDirection direction, long amount) {
        if (before == null || after == null || amount < 0 || amount > 0 && (item == null || direction == null)) return false;
        Map<InventoryItemIdentity, Long> expected = new HashMap<>(before);
        if (amount > 0) {
            long next = Math.addExact(expected.getOrDefault(item, 0L), direction == TransactionDirection.DEPOSIT ? -amount : amount);
            if (next < 0) return false;
            if (next == 0) expected.remove(item); else expected.put(item, next);
        }
        return expected.equals(after);
    }

    private static final class ProofMismatch extends IllegalArgumentException {
        ProofMismatch(String reason) { super(reason); }
    }
    private static final class Action {
        final IntegratedTerminalsBinding binding;
        final TerminalStorageTabIngredientComponentServer<?, ?> tab;
        final IngredientComponentTerminalStorageHandlerItemStack handler;
        final TransactionDirection direction;
        final int channel;
        boolean valid = true, finished, reported;
        final long countedMark = AdapterFailSafe.countedOnThisThread(AdapterKey.INTEGRATED_TERMINALS);
        int operations, positions;
        long quantity;
        InventoryItemIdentity item;
        ContainerTarget target;
        UniversalContainerTracker.ActionToken token;
        Map<InventoryItemIdentity, Long> before;
        ChannelScope channelScope;
        IIngredientComponentStorage<?, ?> nativeStorage;
        IngredientChannelIndexed<?, ?> nativeIndexed;
        LeafScope leaf;
        Action(IntegratedTerminalsBinding binding, TerminalStorageTabIngredientComponentServer<?, ?> tab,
                IngredientComponentTerminalStorageHandlerItemStack handler, TerminalClickType click, int channel) {
            this.binding = binding; this.tab = tab; this.handler = handler; this.direction = direction(click); this.channel = channel;
        }
    }
    private static final class ChannelScope {
        final IngredientChannelAdapter<?, ?> receiver;
        final ItemStack input;
        final boolean insert, simulate;
        long quantity;
        ChannelScope(IngredientChannelAdapter<?, ?> receiver, ItemStack input, boolean insert, boolean simulate) {
            this.receiver = receiver; this.input = input; this.insert = insert; this.simulate = simulate;
        }
    }
    /** One exact CC invocation, never reset by a repeated slot or an inner cell call. */
    static final class FiniteCellBudget {
        final int limit;
        int calls;
        FiniteCellBudget(boolean simulate) {
            limit = simulate ? MAX_SIMULATED_CELLS_PER_LEAF : MAX_COMMITTED_CELLS_PER_LEAF;
        }
        boolean enter() {
            if (calls >= limit) return false;
            calls++;
            return true;
        }
    }
    private static final class LeafScope {
        final Action action;
        final IIngredientComponentStorage<?, ?> storage;
        final PartPos position;
        final InvWrapper handler;
        final RandomizableContainerBlockEntity block;
        final ServerLevel level;
        final ItemStack input;
        final boolean insert, simulate;
        final FiniteCellBudget cells;
        boolean cellActive;
        long quantity;
        LeafScope(Action action, IIngredientComponentStorage<?, ?> storage, PartPos position, InvWrapper handler,
                RandomizableContainerBlockEntity block, ServerLevel level, ItemStack input, boolean insert, boolean simulate) {
            this.action = action; this.storage = storage; this.position = position; this.handler = handler;
            this.block = block; this.level = level; this.input = input; this.insert = insert; this.simulate = simulate;
            this.cells = new FiniteCellBudget(simulate);
        }
        static LeafScope capture(Action action, IIngredientComponentStorage<?, ?> storage, PartPos position,
                InvWrapper handler, ItemStack input, boolean insert, boolean simulate) {
            if (position == null || !position.getPos().isLoaded()
                    || !(position.getPos().getLevel(true) instanceof ServerLevel level)
                    || !(handler.getInv() instanceof RandomizableContainerBlockEntity block)) return null;
            var leaf = new LeafScope(action, storage, position, handler, block, level, input, insert, simulate);
            return leaf.current() ? leaf : null;
        }
        boolean current() {
            if (!action.binding.current() || level.getServer() != action.binding.player.getServer()
                    || !position.getPos().isLoaded() || block.isRemoved() || block.getLevel() != level
                    || !block.getBlockPos().equals(position.getPos().getBlockPos())
                    || level.getBlockEntity(block.getBlockPos()) != block || handler.getInv() != block
                    || block.getLootTable() != null || block.getContainerSize() != FINITE_LEAF_SLOTS
                    || action.binding.items.getPositionedStorage(position) != storage) return false;
            var state = level.getBlockState(block.getBlockPos());
            boolean finite = block.getClass() == BarrelBlockEntity.class && state.is(Blocks.BARREL)
                    || block.getClass() == ChestBlockEntity.class && state.is(Blocks.CHEST) && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    || block.getClass() == TrappedChestBlockEntity.class && state.is(Blocks.TRAPPED_CHEST) && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
            var frame = finite ? AuditBlockLocations.capture(level, block.getBlockPos()).orElse(null) : null;
            return finite && frame != null && !frame.moving() && frame.isCurrent();
        }
    }
}
