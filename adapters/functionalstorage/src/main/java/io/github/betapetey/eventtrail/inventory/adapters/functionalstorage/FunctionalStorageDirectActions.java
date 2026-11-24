package io.github.betapetey.eventtrail.inventory.adapters.functionalstorage;

import com.buuz135.functionalstorage.block.tile.DrawerTile;
import com.buuz135.functionalstorage.block.tile.FramedDrawerTile;
import com.buuz135.functionalstorage.block.Drawer;
import com.buuz135.functionalstorage.inventory.BigInventoryHandler;
import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.action.AuthoritativeTransactionCollector;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionCorrelator;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionCorrelator.Snapshot;
import io.github.betapetey.eventtrail.inventory.core.*;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Exact 1.5.8 ordinary world drawers, admitted by the optional mixin plugin. */
public final class FunctionalStorageDirectActions {
    private static final ThreadLocal<Packet> PACKET = new ThreadLocal<>();
    private static final AtomicLong NEXT_PACKET = new AtomicLong();
    private static final String PROVIDER = "functionalstorage-direct-1.5.8";
    private static final RateLimitedDiagnosticGate NOT_RECORDED = new RateLimitedDiagnosticGate(60_000);

    private FunctionalStorageDirectActions() {}

    public static boolean isDrawerDig(ServerboundPlayerActionPacket.Action action) {
        // The mod's LeftClickBlock listener runs for all three dig actions.
        return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
    }

    public static Packet beginPacket(ServerPlayer player, BlockPos pos,
                                     InteractionHand hand, TransactionDirection direction) {
        Packet previous = PACKET.get();
        if (previous != null) {
            previous.valid = false;
            return null;
        }
        try {
            // M-1: the counted not-ready gate runs last, only once an ordinary drawer is known to be the target, so a
            // use or dig packet on anything else is not a skipped capture attempt. The answer is unchanged.
            if (!eligible(player) || pos == null || hand == null || direction == null
                    || !player.serverLevel().hasChunkAt(pos) || !player.canInteractWithBlock(pos, 1.0)
                    || !ordinary(player.serverLevel().getBlockEntity(pos))
                    || !EventTrailManager.getInstance().admitCapture()) return null;
            long id = NEXT_PACKET.incrementAndGet();
            if (id <= 0) return null;
            Packet packet = new Packet(player, pos.immutable(), hand, direction, id);
            PACKET.set(packet);
            return packet;
        } catch (Throwable failure) {
            failed("adapter:FunctionalStorageDirectActions.beginPacket", failure);
            return null;
        }
    }

    public static void finishPacket(Packet packet, boolean normalReturn) {
        if (packet == null) return;
        try {
            if (PACKET.get() != packet || !normalReturn || !packet.valid || packet.active != null
                    || packet.finished == null || !enabled(packet.player)) return;
            Action action = packet.finished;
            if (!action.current() || !action.after.equals(action.snapshot())) return;
            Optional<ContainerTarget> target = action.location.currentTarget();
            if (target.isEmpty()) return;
            // A2-44: the entire bounded set is assembled into one batch and handed to storage once, all or none. A
            // row build that throws discards the whole action before anything is handed over.
            ActionRecordBatch.collect(EventTrailManager.getInstance()::tryLogAll, batch -> {
                for (AuthoritativeTransactionCollector.Transfer transfer : action.transfers) {
                    if (!ContainerTransactionRecordingPolicy.enabled(transfer.direction())) continue;
                    var evidence = new InventoryTransactionEvidence(packet.when, packet.player.getUUID(),
                            packet.player.getGameProfile().getName(), false, packet.id, 1,
                            transfer.direction(), transfer.item(), transfer.quantity(), target.orElseThrow(),
                            PROVIDER, EvidenceKind.AUTHORITATIVE_MOD_OPERATION, InventorySlotRole.STORAGE);
                    batch.addAll(InventoryTransactionNormalizer.toAuditRecords(
                            evidence, EventTrailManager.getInstance().canAcceptAuditWrite()));
                }
            });
        } catch (Throwable failure) {
            // Optional observation must not affect the mod's interaction outcome. Nothing of the action was handed
            // to storage before the failure, so none of its rows was written; the operator is told (rate-limited).
            // R2-inner: any throwable, an Error included, ends here; the failure is reported to the fail-safe too.
            actionNotRecorded(failure);
        } finally {
            packet.valid = false;
            if (PACKET.get() == packet) PACKET.remove();
        }
    }

    public static Action beginDrawer(Object candidate, Player actor, InteractionHand hand,
                                     int slot, TransactionDirection direction) {
        Packet packet = PACKET.get();
        if (packet == null) return null;
        if (packet.active != null || packet.finished != null) {
            packet.valid = false;
            return null;
        }
        try {
            if (!packet.valid || actor != packet.player || packet.hand != hand
                    || packet.direction != direction || !ordinary(candidate)) {
                packet.valid = false;
                return null;
            }
            DrawerTile tile = (DrawerTile) candidate;
            if (!packet.pos.equals(tile.getBlockPos())) {
                packet.valid = false;
                return null;
            }
            Action action = new Action(packet, tile, slot);
            if (!action.current()) {
                packet.valid = false;
                return null;
            }
            action.before = action.snapshot();
            packet.active = action;
            return action;
        } catch (Throwable failure) {
            packet.valid = false;
            failed("adapter:FunctionalStorageDirectActions.beginDrawer", failure);
            return null;
        }
    }

    public static void finishDrawer(Action action, boolean normalReturn) {
        if (action == null) return;
        Packet packet = action.packet;
        try {
            if (PACKET.get() != packet || packet.active != action || !packet.valid
                    || !normalReturn || !action.current()) {
                packet.valid = false;
                action.collector.abort();
                return;
            }
            action.after = action.snapshot();
            action.transfers = verify(action.before, action.after, action.collector.commit());
            packet.finished = action;
        } catch (Throwable failure) {
            packet.valid = false;
            action.collector.abort();
            failed("adapter:FunctionalStorageDirectActions.finishDrawer", failure);
        } finally {
            if (packet.active == action) packet.active = null;
        }
    }

    /** Wraps only the four insert call sites inside the exact direct interaction. */
    public static ItemStack insert(IItemHandler handler, int slot, ItemStack input, boolean simulate,
                                   Supplier<ItemStack> operation) {
        Action action = currentOperation(handler, slot, TransactionDirection.DEPOSIT);
        ItemStack copy = ItemStack.EMPTY;
        if (action != null && !simulate) {
            try { copy = input.copy(); }
            catch (Throwable failure) {
                action.packet.valid = false;
                failed("adapter:FunctionalStorageDirectActions.insertCopy", failure);
            }
        }
        ItemStack remainder = operation.get(); // Gameplay failure propagates; wrapper finally discards evidence.
        if (action != null && !simulate && action.packet.valid) {
            try {
                long committed = inserted(copy, remainder);
                if (committed > 0 && !action.collector.record(TransactionDirection.DEPOSIT,
                        InventoryItemIdentity.of(copy), committed)) action.packet.valid = false;
            } catch (Throwable failure) {
                action.packet.valid = false;
                failed("adapter:FunctionalStorageDirectActions.insert", failure);
            }
        }
        return remainder;
    }

    /** Wraps only the extract call immediately consumed by giveItemToPlayer. */
    public static ItemStack extract(IItemHandler handler, int slot, int requested, boolean simulate,
                                    Supplier<ItemStack> operation) {
        Action action = currentOperation(handler, slot, TransactionDirection.WITHDRAWAL);
        ItemStack result = operation.get();
        if (action != null && !simulate && action.packet.valid) {
            try {
                if (result == null || result.getCount() < 0 || result.getCount() > requested) {
                    action.packet.valid = false;
                } else if (!result.isEmpty() && !action.collector.record(TransactionDirection.WITHDRAWAL,
                        InventoryItemIdentity.of(result), result.getCount())) action.packet.valid = false;
            } catch (Throwable failure) {
                action.packet.valid = false;
                failed("adapter:FunctionalStorageDirectActions.extract", failure);
            }
        }
        return result;
    }

    private static Action currentOperation(IItemHandler handler, int slot, TransactionDirection direction) {
        Packet packet = PACKET.get();
        if (packet == null || !packet.valid || packet.active == null) return null;
        Action action = packet.active;
        if (action.handler != handler || action.slot != slot || packet.direction != direction) {
            packet.valid = false;
            return null;
        }
        return action;
    }

    static long inserted(ItemStack input, ItemStack remainder) {
        if (input == null || input.isEmpty() || remainder == null || remainder.getCount() < 0
                || (!remainder.isEmpty() && !ItemStack.isSameItemSameComponents(input, remainder))
                || remainder.getCount() > input.getCount()) throw new IllegalArgumentException("Unproven insert result");
        return (long) input.getCount() - remainder.getCount();
    }

    /**
     * Reuses the shared custody model and requires exact equality with native commits. GF-IDENTITYNORM: this caller
     * stays component-exact (no volatile-component fallback).
     */
    static List<AuthoritativeTransactionCollector.Transfer> verify(Snapshot before, Snapshot after,
                            List<AuthoritativeTransactionCollector.Transfer> committed) {
        var result = PhysicalInventoryActionCorrelator.correlateExact(before, after, Optional.empty());
        if (!result.accepted() || result.nextEscrow().isPresent() || committed.isEmpty()) return List.of();
        Map<TransferKey, Long> exact = new HashMap<>();
        Map<TransferKey, Long> custody = new HashMap<>();
        for (var transfer : committed) exact.merge(new TransferKey(transfer.direction(), transfer.item()),
                transfer.quantity(), Math::addExact);
        for (var transfer : result.transfers()) custody.merge(new TransferKey(transfer.direction(), transfer.item()),
                transfer.quantity(), Math::addExact);
        if (!exact.equals(custody)) return List.of();
        // A bulk deposit is one action, preserving each exact item identity once.
        return result.transfers().stream().map(t -> new AuthoritativeTransactionCollector.Transfer(
                t.direction(), t.item(), t.quantity())).toList();
    }

    static boolean ordinary(Object tile) {
        return tile != null && (tile.getClass() == DrawerTile.class || tile.getClass() == FramedDrawerTile.class);
    }

    /** A2-44: a drawer action that failed before its one publish is not recorded at all; warn, rate-limited. */
    private static void actionNotRecorded(Throwable failure) {
        failed("adapter:FunctionalStorageDirectActions.finishPacket", failure);
        try {
            NOT_RECORDED.acquire().ifPresent(emission -> LogManager.getLogger("eventtrail").warn(
                    "Could not record a Functional Storage drawer action ({}); none of its rows was written; "
                            + "suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // A warning must never affect the mod's interaction outcome.
        }
    }

    /** R2-inner: one report to the fail-safe under {@code FUNCTIONAL_STORAGE}; every site keeps its fail-closed result. */
    private static void failed(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.FUNCTIONAL_STORAGE, site, failure);
    }

    private static boolean enabled(ServerPlayer player) {
        return eligible(player) && EventTrailManager.getInstance().admitCapture();
    }

    /** {@link #enabled} without the readiness gate (M-1: beginPacket asks the drawer check before counting). */
    private static boolean eligible(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player)
                && player.server != null && player.server.isSameThread() && !player.isRemoved()
                && !player.isSpectator() && !player.isCreative()
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }

    static Map<InventoryItemIdentity, Long> storageTotals(BigInventoryHandler handler, int slots) {
        if (slots != 1 && slots != 2 && slots != 4 || handler.isVoid() || handler.isCreative()
                || handler.getSlots() != slots || handler.getStoredStacks().size() != slots) {
            throw new IllegalArgumentException("Unsupported drawer backing");
        }
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        for (int i = 0; i < slots; i++) {
            var cell = handler.getStoredStacks().get(i);
            if (cell == null || cell.getClass() != BigInventoryHandler.BigStack.class) {
                throw new IllegalArgumentException("Custom drawer cell");
            }
            int amount = cell.getAmount();
            if (amount < 0 || amount > handler.getSlotLimit(i)
                    || amount > 0 && cell.getStack().isEmpty()) throw new IllegalArgumentException("Invalid quantity");
            if (amount > 0) totals.merge(InventoryItemIdentity.of(cell.getStack()), (long) amount, Math::addExact);
        }
        return Map.copyOf(totals);
    }

    private static List<ItemStack> copyStacks(IItemHandler handler, int maximum) {
        if (handler == null || handler.getSlots() < 0 || handler.getSlots() > maximum) {
            throw new IllegalArgumentException("Unbounded upgrades");
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) stacks.add(handler.getStackInSlot(i).copy());
        return stacks;
    }

    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!ItemStack.matches(left.get(i), right.get(i))) return false;
        return true;
    }

    public static final class Packet {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos pos;
        final InteractionHand hand;
        final TransactionDirection direction;
        final long id;
        final long when = System.currentTimeMillis();
        boolean valid = true;
        Action active;
        Action finished;

        private Packet(ServerPlayer player, BlockPos pos, InteractionHand hand, TransactionDirection direction, long id) {
            this.player = player;
            this.level = player.serverLevel();
            this.pos = pos;
            this.hand = hand;
            this.direction = direction;
            this.id = id;
        }
    }

    public static final class Action {
        final Packet packet;
        final DrawerTile tile;
        final BigInventoryHandler handler;
        final BlockState state;
        final Inventory inventory;
        final AbstractContainerMenu menu;
        final ItemStack cursor;
        final int slot;
        final int slots;
        final List<BigInventoryHandler.BigStack> cells;
        final List<ItemStack> storageUpgrades;
        final List<ItemStack> utilityUpgrades;
        final IItemHandler storageUpgradeHandler;
        final IItemHandler utilityUpgradeHandler;
        final float multiplier;
        final boolean locked;
        final BlockPos controller;
        final PhysicalContainerLocationBinding location;
        final AuthoritativeTransactionCollector collector = new AuthoritativeTransactionCollector();
        Snapshot before;
        Snapshot after;
        List<AuthoritativeTransactionCollector.Transfer> transfers = List.of();

        private Action(Packet packet, DrawerTile tile, int slot) throws ReflectiveOperationException {
            this.packet = packet;
            this.tile = tile;
            this.handler = tile.getHandler();
            if (handler == null || !handler.getClass().getName().equals(
                    "com.buuz135.functionalstorage.block.tile.DrawerTile$1")) throw new IllegalArgumentException("Custom handler");
            Field owner = handler.getClass().getDeclaredField("this$0");
            if (!owner.trySetAccessible() || owner.get(handler) != tile) throw new IllegalArgumentException("Foreign handler");
            this.state = tile.getBlockState();
            this.inventory = packet.player.getInventory();
            this.menu = packet.player.containerMenu;
            this.cursor = menu.getCarried().copy();
            this.slot = slot;
            this.slots = tile.getDrawerType().getSlots();
            if (slot < 0 || slot >= slots) throw new IllegalArgumentException("Not a storage face");
            this.cells = List.copyOf(handler.getStoredStacks());
            this.storageUpgradeHandler = tile.getStorageUpgrades();
            this.utilityUpgradeHandler = tile.getUtilityUpgrades();
            this.storageUpgrades = copyStacks(storageUpgradeHandler, 4);
            this.utilityUpgrades = copyStacks(utilityUpgradeHandler, 3);
            this.multiplier = tile.getStorageMultiplier();
            this.locked = tile.isLocked();
            this.controller = tile.getControllerPos();
            ContainerTarget nativeTarget = ContainerTarget.block(packet.level.dimension().location().toString(), packet.pos,
                    state.getBlock().getName().getString(), BackingContextKind.WORLD_BLOCK,
                    packet.level.dimension().location() + "|" + packet.pos.getX() + ","
                            + packet.pos.getY() + "," + packet.pos.getZ());
            this.location = PhysicalContainerLocationBinding.capture(packet.level, nativeTarget,
                    List.of(tile), () -> true).orElseThrow();
        }

        boolean current() {
            if (!packet.valid || !enabled(packet.player) || location.currentTarget().isEmpty() || packet.player.level() != packet.level
                    || packet.player.getInventory() != inventory || packet.player.containerMenu != menu
                    || !ItemStack.matches(cursor, menu.getCarried()) || tile.isRemoved()
                    || !packet.pos.equals(tile.getBlockPos()) || !packet.player.canInteractWithBlock(packet.pos, 1.0)
                    || tile.getLevel() != packet.level || !packet.level.hasChunkAt(packet.pos)
                    || packet.level.getBlockEntity(packet.pos) != tile || tile.getBlockState() != state
                    || packet.level.getBlockState(packet.pos) != state || !(state.getBlock() instanceof Drawer<?>)
                    || tile.getHandler() != handler
                    || tile.getStorage() != handler || tile.getDrawerType().getSlots() != slots
                    || tile.isVoid() || tile.isCreative() || !Float.isFinite(multiplier) || multiplier <= 0
                    || tile.getStorageMultiplier() != multiplier || tile.isLocked() != locked
                    || !java.util.Objects.equals(controller, tile.getControllerPos())
                    || tile.getStorageUpgrades() != storageUpgradeHandler || tile.getUtilityUpgrades() != utilityUpgradeHandler
                    || !sameStacks(storageUpgrades, copyStacks(storageUpgradeHandler, 4))
                    || !sameStacks(utilityUpgrades, copyStacks(utilityUpgradeHandler, 3))) return false;
            if (handler.getStoredStacks().size() != cells.size()) return false;
            for (int i = 0; i < cells.size(); i++) if (handler.getStoredStacks().get(i) != cells.get(i)) return false;
            return true;
        }

        Snapshot snapshot() {
            if (inventory.getClass() != Inventory.class || inventory.getContainerSize() != 41) {
                throw new IllegalArgumentException("Custom inventory");
            }
            Map<InventoryItemIdentity, Long> player = new HashMap<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack == null || stack.getCount() < 0) throw new IllegalArgumentException("Invalid player stack");
                if (!stack.isEmpty()) player.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
            }
            return Snapshot.withoutCursor(storageTotals(handler, slots), player);
        }
    }

    private record TransferKey(TransactionDirection direction, InventoryItemIdentity item) {}
}
