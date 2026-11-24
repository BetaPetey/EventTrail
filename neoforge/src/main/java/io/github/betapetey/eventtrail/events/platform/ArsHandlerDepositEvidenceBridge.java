package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.CommittedItemDropEvidence;
import io.github.betapetey.eventtrail.events.evidence.ItemDropAuditSession;
import io.github.betapetey.eventtrail.events.evidence.ItemDropEvidenceNormalizer;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy;
import io.github.betapetey.eventtrail.inventory.core.DirectPhysicalHandlerResolver;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Finality for one externally authorized exact human Touch/Toss operation.
 * The optional producer owns native cast/extraction/lookup/call identity. This
 * bridge proves finite physical custody and never changes or replays gameplay.
 */
public final class ArsHandlerDepositEvidenceBridge {
    private static final ThreadLocal<Boundary> ACTIVE = new ThreadLocal<>();
    private static final Boundary NONE = new Boundary();
    private static final AtomicLong OPERATIONS = new AtomicLong();
    private static final RateLimitedDiagnosticGate FAILURES = new RateLimitedDiagnosticGate(60_000);
    private static final int PLAYER_CELLS = 41;

    private ArsHandlerDepositEvidenceBridge() { }

    public static Boundary begin(ServerPlayer player, ServerLevel level, BlockHitResult hit,
            Direction capabilityFace, IItemHandler exactHandler, BooleanSupplier currentAuthority) {
        Boundary active = ACTIVE.get();
        if (active != null) { active.invalid = true; return NONE; }
        try {
            if (player == null || level == null || currentAuthority == null
                    || NeoForgePlayerProvenance.isSynthetic(player) || player.level() != level
                    || !level.getServer().isSameThread() || !player.isAlive()
                    || level.getEntity(player.getUUID()) != player || player.getInventory().player != player) return NONE;
            ActorProvenance actor = NeoForgePlayerProvenance.capture(player, ActorProvenance.Causality.DIRECT, null).orElse(null);
            if (actor == null) return NONE;
            Snapshot before = playerSnapshot(player.getInventory());
            Boundary boundary = new Boundary();
            boundary.context = new Context(player, player.getInventory(), level, currentAuthority, before);
            // A rejected/null target retains maintenance only. It cannot inherit
            // Self/world-Toss evidence or turn unsupported handlers into DROP.
            boundary.binding = DirectPhysicalHandlerResolver.resolve(level, hit, capabilityFace, exactHandler).orElse(null);
            if (boundary.binding != null && authority(currentAuthority)) {
                long operation = OPERATIONS.incrementAndGet();
                if (operation > 0) boundary.proof = new Proof(System.currentTimeMillis(), operation, actor,
                        level.dimension().location(), before);
            }
            ACTIVE.set(boundary);
            return boundary;
        } catch (RuntimeException | LinkageError | StackOverflowError failure) { failure("capture handler deposit", failure); return NONE; }
    }

    public static void extracted(Boundary boundary, ItemStack actual) {
        observe(boundary, "observe handler extraction", () -> {
            Snapshot after = playerSnapshot(boundary.context.inventory);
            resyncIfChanged(boundary.context, after);
            if (boundary.proof != null && (!boundary.context.current()
                    || !boundary.proof.extracted(actual, after))) boundary.invalid = true;
        });
    }

    public static void inserting(Boundary boundary, IItemHandler handler, ItemStack input) {
        observe(boundary, "capture native handler insertion", () -> {
            if (boundary.proof == null) return;
            var destination = boundary.binding.snapshot().orElse(null);
            if (!boundary.context.current() || !boundary.binding.owns(handler) || destination == null
                    || !boundary.proof.inserting(input, new Snapshot(destination), playerSnapshot(boundary.context.inventory))) {
                boundary.invalid = true;
            }
        });
    }

    public static void inserted(Boundary boundary, IItemHandler handler, ItemStack remainder) {
        observe(boundary, "confirm native handler insertion", () -> {
            if (boundary.proof == null) return;
            var destination = boundary.binding.snapshot().orElse(null);
            // Freeze the touched physical anchor at this exact insertion commit.
            ContainerTarget target = boundary.binding.committedTarget().orElse(null);
            if (!boundary.context.current() || !boundary.binding.owns(handler) || destination == null || target == null
                    || !boundary.proof.inserted(remainder, new Snapshot(destination),
                            playerSnapshot(boundary.context.inventory), target)) boundary.invalid = true;
        });
    }

    public static void returning(Boundary boundary, ItemStack remainder) {
        observe(boundary, "capture native remainder return", () -> {
            if (boundary.proof != null && (!boundary.context.current()
                    || !boundary.proof.returning(remainder, playerSnapshot(boundary.context.inventory)))) boundary.invalid = true;
        });
    }

    /** Result copied at the actual returnStack return; ExtractedStack.stack is not this result. */
    public static void returned(Boundary boundary, ItemStack finalRemainder) {
        observe(boundary, "confirm native remainder return", () -> {
            Snapshot after = playerSnapshot(boundary.context.inventory);
            resyncIfChanged(boundary.context, after);
            if (boundary.proof != null && (!boundary.context.current()
                    || !boundary.proof.returned(finalRemainder, after))) boundary.invalid = true;
        });
    }

    public static void admitting(Boundary boundary, ItemEntity entity) {
        observe(boundary, "capture handler remainder entity", () -> {
            if (boundary.proof == null) return;
            Context context = boundary.context;
            boolean fresh = context.current() && entity != null && entity.level() == context.level
                    && !entity.isAddedToLevel() && !entity.isRemoved() && context.level.getEntity(entity.getUUID()) == null;
            if (!boundary.proof.admitting(fresh, entity == null ? null : entity.getItem(), playerSnapshot(context.inventory))) {
                boundary.invalid = true; return;
            }
            boundary.entity = entity; boundary.entityId = entity.getUUID();
        });
    }

    public static void admitted(Boundary boundary, ItemEntity entity, boolean result) {
        observe(boundary, "confirm handler remainder admission", () -> {
            if (boundary.proof == null) return;
            Context context = boundary.context;
            boolean exact = context.current() && entity != null && entity == boundary.entity
                    && entity.getUUID().equals(boundary.entityId) && entity.level() == context.level;
            boolean registered = exact && result && entity.isAddedToLevel() && !entity.isRemoved()
                    && context.level.getEntity(entity.getUUID()) == entity;
            if (!exact || !boundary.proof.admitted(entity.getUUID(), entity.level().dimension().location(),
                    entity.blockPosition(), entity.getItem(), registered, playerSnapshot(context.inventory))) boundary.invalid = true;
        });
    }

    public static void finish(Boundary boundary) {
        if (!claim(boundary)) return;
        try {
            resync(boundary.context);
            Completion result = boundary.proof == null ? Completion.EMPTY : boundary.proof.complete(
                    !boundary.invalid, boundary.context.current(), playerSnapshot(boundary.context.inventory));
            var manager = EventTrailManager.getInstance();
            // A2-44 (lead decision Q3): the deposit rows and the world-remainder drop row of one handler operation
            // are one action, built first and published once, all or none. The two gates are asked as before.
            Supplier<List<AuditRecord>> depositRows = result.deposit.isPresent()
                    && ContainerTransactionRecordingPolicy.enabled(TransactionDirection.DEPOSIT) && manager.admitCapture()
                    ? () -> InventoryTransactionNormalizer.toAuditRecords(result.deposit.orElseThrow(),
                            manager.canAcceptAuditWrite())
                    : null;
            Supplier<AuditRecord> dropRow = result.drop.isPresent() && EventTrailConfig.TRACK_ITEM_DROPS.get()
                    && manager.admitCapture()
                    ? () -> ItemDropEvidenceNormalizer.toAuditRecord(result.drop.orElseThrow(), manager.canAcceptAuditWrite())
                    : null;
            publishAction(manager::tryLogAll, depositRows, dropRow);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            // Nothing of the action was handed to storage before the failure: none of its rows was written.
            failure("finalize handler deposit; none of its deposit or drop rows was written", failure);
        }
        finally { clear(boundary); }
    }

    /**
     * A2-44 (lead decision Q3): one handler operation as one action, all or none: every deposit row, in order, and
     * then the drop row. A null supplier means the operation has no such row (or its gate refused it); with neither
     * nothing is published. Every row is built inside the batch before anything is handed to {@code sink}, so a row
     * whose build throws discards the whole action and the failure propagates to the caller's guard.
     * Package-private for ArsHandlerDepositEvidenceBridgeTest.
     *
     * @return whether storage took the whole action (true when there was no row to publish)
     */
    static boolean publishAction(Predicate<List<AuditRecord>> sink, Supplier<List<AuditRecord>> depositRows,
            Supplier<AuditRecord> dropRow) {
        return ActionRecordBatch.collect(sink, batch -> {
            if (depositRows != null) batch.addAll(depositRows.get());
            if (dropRow != null) batch.add(dropRow.get());
        });
    }

    public static void abort(Boundary boundary) {
        if (!claim(boundary)) return;
        try { resync(boundary.context); } finally { clear(boundary); }
    }

    public static int pendingOperations() { return ACTIVE.get() == null ? 0 : 1; }
    /** Stop/restart cleanup. af-3 (re-check m4): an in-flight operation dropped here is counted once as skipped. */
    public static void clearPendingOperation() {
        Boundary active = ACTIVE.get();
        if (active == null) return;
        EventTrailManager.getInstance().countDroppedCaptures(1);
        abort(active);
    }
    public static void onPlayerLogout(UUID playerId) {
        Boundary active = ACTIVE.get();
        if (active != null && active.context != null && active.context.player.getUUID().equals(playerId)) abort(active);
    }

    private static void observe(Boundary boundary, String phase, Runnable observation) {
        if (boundary == null || boundary.context == null || boundary.closed || boundary.invalid) return;
        if (ACTIVE.get() != boundary) { boundary.invalid = true; return; }
        try {
            if (!boundary.context.samePlayer()) { boundary.invalid = true; return; }
            observation.run();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) { boundary.invalid = true; failure(phase, failure); }
        catch (Throwable failure) {
            // R2-inner: any other throwable (an Error) also abandons the operation before it goes on to its caller
            // (ArsItemCastAuditBridge.observe, which reports it), so a half-observed step cannot leave a boundary
            // that still looks valid.
            boundary.invalid = true;
            throw failure;
        }
    }

    private static boolean claim(Boundary boundary) {
        if (boundary == null || boundary.context == null || boundary.closed) return false;
        boundary.closed = true;
        if (ACTIVE.get() != boundary) boundary.invalid = true;
        return true;
    }

    private static void clear(Boundary boundary) {
        if (ACTIVE.get() == boundary) ACTIVE.remove();
        if (boundary.proof != null) boundary.proof.clear();
        boundary.invalid = true; boundary.context = null; boundary.proof = null;
        boundary.binding = null; boundary.entity = null; boundary.entityId = null;
    }

    private static void resync(Context context) {
        try { if (context.samePlayer()) resyncIfChanged(context, playerSnapshot(context.inventory)); }
        catch (RuntimeException | LinkageError | StackOverflowError failure) { failure("resynchronize completed handler operation", failure); }
    }

    private static void resyncIfChanged(Context context, Snapshot after) {
        if (!context.observation.changed(after)) return;
        try {
            UniversalContainerTracker.onCommittedPlayerInventoryMutation(context.player);
            context.observation.observed(after);
        }
        catch (RuntimeException | LinkageError | StackOverflowError failure) { failure("resynchronize handler possession", failure); }
    }

    static boolean authority(BooleanSupplier authority) {
        try { return authority.getAsBoolean(); }
        catch (RuntimeException | LinkageError | StackOverflowError failure) { failure("verify handler authority", failure); return false; }
    }

    private static Snapshot playerSnapshot(Inventory inventory) {
        if (inventory.getContainerSize() != PLAYER_CELLS) throw new IllegalArgumentException("Expected 41 player cells");
        List<ItemStack> stacks = new ArrayList<>(PLAYER_CELLS);
        for (int slot = 0; slot < PLAYER_CELLS; slot++) stacks.add(inventory.getItem(slot));
        return new Snapshot(stacks);
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return first != null && second != null && ItemStack.matches(first, second);
    }

    /**
     * R2-inner: every catch site of this bridge ends here, keeps its own fail-closed answer, and is reported to
     * {@code ARS_ITEM} (a missing member switches the key OFF; anything else skips just that action) under the
     * {@code bridge:<Class>.<method>} site of the method that caught it ({@link #site}). The site is a constant, so
     * nothing is allocated at the edge of the stack. The phase stays the text of the rate-limited log line.
     * Reporting never drops or splits a batch: the action's rows were not handed to storage when a failure gets here.
     */
    private static void failure(String phase, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.ARS_ITEM, site(phase), failure);
        FAILURES.acquire().ifPresent(emission -> LogManager.getLogger("eventtrail").error(
                "Could not {} ({}); suppressed {} handler failure(s)", phase,
                EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
    }

    /**
     * The fail-safe site of each catch phase: {@code bridge:ArsHandlerDepositEvidenceBridge.<method that caught it>}
     * (for an {@code observe} phase, the public method that passed it); the bare bridge name only for a phase not
     * listed. Package-private for ArsEvidenceBridgeFailSafeTest.
     */
    static String site(String phase) {
        if (phase == null) return "bridge:ArsHandlerDepositEvidenceBridge";
        return switch (phase) {
            case "capture handler deposit" -> "bridge:ArsHandlerDepositEvidenceBridge.begin";
            case "observe handler extraction" -> "bridge:ArsHandlerDepositEvidenceBridge.extracted";
            case "capture native handler insertion" -> "bridge:ArsHandlerDepositEvidenceBridge.inserting";
            case "confirm native handler insertion" -> "bridge:ArsHandlerDepositEvidenceBridge.inserted";
            case "capture native remainder return" -> "bridge:ArsHandlerDepositEvidenceBridge.returning";
            case "confirm native remainder return" -> "bridge:ArsHandlerDepositEvidenceBridge.returned";
            case "capture handler remainder entity" -> "bridge:ArsHandlerDepositEvidenceBridge.admitting";
            case "confirm handler remainder admission" -> "bridge:ArsHandlerDepositEvidenceBridge.admitted";
            case "finalize handler deposit; none of its deposit or drop rows was written" ->
                    "bridge:ArsHandlerDepositEvidenceBridge.finish";
            case "resynchronize completed handler operation" -> "bridge:ArsHandlerDepositEvidenceBridge.resync";
            case "resynchronize handler possession" -> "bridge:ArsHandlerDepositEvidenceBridge.resyncIfChanged";
            case "verify handler authority" -> "bridge:ArsHandlerDepositEvidenceBridge.authority";
            default -> "bridge:ArsHandlerDepositEvidenceBridge";
        };
    }

    public static final class Boundary {
        private Context context;
        private DirectPhysicalHandlerResolver.Binding binding;
        private Proof proof;
        private ItemEntity entity;
        private UUID entityId;
        private boolean invalid, closed;
        private Boundary() { }
    }

    private static final class Context {
        final ServerPlayer player;
        final Inventory inventory;
        final ServerLevel level;
        final BooleanSupplier authority;
        final PossessionObservation observation;

        Context(ServerPlayer player, Inventory inventory, ServerLevel level, BooleanSupplier authority, Snapshot before) {
            this.player = player; this.inventory = inventory; this.level = level; this.authority = authority;
            this.observation = new PossessionObservation(before);
        }
        boolean samePlayer() {
            return level.getServer().isSameThread() && player.level() == level && player.isAlive()
                    && player.getInventory() == inventory && inventory.player == player
                    && level.getEntity(player.getUUID()) == player;
        }
        boolean current() { return samePlayer() && ArsHandlerDepositEvidenceBridge.authority(authority); }
    }

    /** Restoration to the entry contents is still a mutation after extraction. */
    static final class PossessionObservation {
        private Snapshot latest;
        PossessionObservation(Snapshot initial) { latest = initial; }
        boolean changed(Snapshot next) { return !latest.same(next); }
        void observed(Snapshot next) { latest = next; }
    }

    /** Fixed finite cells; every unrelated component-exact identity must remain unchanged. */
    static final class Snapshot {
        private final List<ItemStack> stacks;
        Snapshot(List<ItemStack> stacks) {
            if (stacks == null || stacks.isEmpty() || stacks.size() > 54) throw new IllegalArgumentException("Finite custody cells");
            this.stacks = stacks.stream().map(stack -> Objects.requireNonNull(stack).copy()).toList();
        }
        boolean same(Snapshot after) {
            if (after == null || stacks.size() != after.stacks.size()) return false;
            for (int slot = 0; slot < stacks.size(); slot++) if (!sameStack(stacks.get(slot), after.stacks.get(slot))) return false;
            return true;
        }
        boolean change(Snapshot after, ItemStack identity, int expected) {
            if (after == null || identity == null || identity.isEmpty() || stacks.size() != after.stacks.size()) return false;
            long change = 0;
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack beforeStack = stacks.get(slot), afterStack = after.stacks.get(slot);
                if (sameStack(beforeStack, afterStack)) continue;
                if (!beforeStack.isEmpty() && !ItemStack.isSameItemSameComponents(identity, beforeStack)
                        || !afterStack.isEmpty() && !ItemStack.isSameItemSameComponents(identity, afterStack)) return false;
                long delta = (afterStack.isEmpty() ? 0 : afterStack.getCount()) - (long) (beforeStack.isEmpty() ? 0 : beforeStack.getCount());
                if (expected >= 0 && delta < 0 || expected <= 0 && delta > 0) return false;
                change += delta;
            }
            return change == expected;
        }
        boolean sameNonMain(Snapshot after) {
            if (stacks.size() != PLAYER_CELLS || after == null || after.stacks.size() != PLAYER_CELLS) return false;
            for (int slot = 36; slot < PLAYER_CELLS; slot++) if (!sameStack(stacks.get(slot), after.stacks.get(slot))) return false;
            return true;
        }
    }

    record Completion(Optional<InventoryTransactionEvidence> deposit, Optional<CommittedItemDropEvidence> drop) {
        static final Completion EMPTY = new Completion(Optional.empty(), Optional.empty());
    }

    /** No native references: bounded component-exact conservation and independent drop finality. */
    static final class Proof {
        private final long timestamp, operation;
        private final ActorProvenance actor;
        private final ResourceLocation dimension;
        private Snapshot before, extractedPlayer, destinationBefore, returnedPlayer;
        private ItemStack extracted, remainder, dropStack;
        private ContainerTarget target;
        private ItemDropAuditSession drop;
        private int accepted;
        private boolean insertionStarted, insertionSeen, returnStarted, returnSeen, admissionStarted, admissionSeen;
        private boolean invalid, closed;

        Proof(long timestamp, long operation, ActorProvenance actor, ResourceLocation dimension, Snapshot before) {
            this.timestamp = timestamp; this.operation = operation; this.actor = actor; this.dimension = dimension; this.before = before;
        }
        boolean extracted(ItemStack actual, Snapshot after) {
            if (closed || invalid || extracted != null || actual == null || actual.isEmpty() || actual.getCount() > 64
                    || !before.sameNonMain(after) || !before.change(after, actual, -actual.getCount())) return reject();
            extracted = actual.copy(); extractedPlayer = after; return true;
        }
        boolean inserting(ItemStack input, Snapshot destination, Snapshot player) {
            if (closed || invalid || insertionStarted || extracted == null || !sameStack(input, extracted)
                    || destination == null || !extractedPlayer.same(player)) return reject();
            insertionStarted = true; destinationBefore = destination; return true;
        }
        boolean inserted(ItemStack result, Snapshot destination, Snapshot player, ContainerTarget committedTarget) {
            if (closed || invalid || !insertionStarted || insertionSeen || result == null || committedTarget == null
                    || !remainderOf(result, extracted) || !extractedPlayer.same(player)) return reject();
            int quantity = extracted.getCount() - (result.isEmpty() ? 0 : result.getCount());
            if (!destinationBefore.change(destination, extracted, quantity)) return reject();
            insertionSeen = true; accepted = quantity; remainder = result.copy(); target = committedTarget;
            destinationBefore = null; return true;
        }
        boolean returning(ItemStack actual, Snapshot player) {
            if (closed || invalid || !insertionSeen || returnStarted || !sameStack(actual, remainder)
                    || !extractedPlayer.same(player)) return reject();
            returnStarted = true; return true;
        }
        boolean admitting(boolean fresh, ItemStack stack, Snapshot player) {
            if (closed || invalid || !returnStarted || admissionStarted || !fresh || stack == null || stack.isEmpty()
                    || !remainderOf(stack, remainder) || !restoration(stack, player)) return reject();
            admissionStarted = true; dropStack = stack.copy();
            drop = new ItemDropAuditSession(timestamp, actor, dimension, dropStack);
            return drop.acceptCandidate(actor, stack) || reject();
        }
        boolean admitted(UUID entity, ResourceLocation world, BlockPos position, ItemStack stack,
                boolean registered, Snapshot player) {
            if (closed || invalid || !admissionStarted || admissionSeen || !restoration(dropStack, player)) return reject();
            admissionSeen = true;
            // Failed/replaced/mutated entity finality does not erase separately
            // proven storage acceptance. It only withholds the independent DROP.
            if (!registered || !sameStack(dropStack, stack) || !dimension.equals(world)) { drop.abort(); return true; }
            drop.observe(actor, entity, world, position, stack, true); return true;
        }
        boolean returned(ItemStack finalRemainder, Snapshot player) {
            if (closed || invalid || !returnStarted || returnSeen || finalRemainder == null
                    || !remainderOf(finalRemainder, remainder) || !restoration(finalRemainder, player)) return reject();
            if (finalRemainder.isEmpty() ? admissionStarted : !admissionSeen || !sameStack(finalRemainder, dropStack)) return reject();
            returnSeen = true; returnedPlayer = player; return true;
        }
        Completion complete(boolean normal, boolean currentAuthority, Snapshot finalPlayer) {
            if (closed) return Completion.EMPTY;
            closed = true;
            Completion result = Completion.EMPTY;
            if (!invalid && normal && currentAuthority && returnSeen && returnedPlayer.same(finalPlayer)) {
                Optional<InventoryTransactionEvidence> deposit = accepted > 0 ? Optional.of(new InventoryTransactionEvidence(
                        timestamp, actor.playerId(), actor.displayName(), false, operation, 1L,
                        TransactionDirection.DEPOSIT, InventoryItemIdentity.of(extracted), accepted, target,
                        "ars-touch-toss", EvidenceKind.AUTHORITATIVE_MOD_OPERATION, InventorySlotRole.STORAGE)) : Optional.empty();
                Optional<CommittedItemDropEvidence> discarded = drop == null ? Optional.empty()
                        : drop.complete(true, actor, dimension).evidence();
                result = new Completion(deposit, discarded);
            }
            clear(); return result;
        }
        private boolean restoration(ItemStack worldRemainder, Snapshot player) {
            int world = worldRemainder.isEmpty() ? 0 : worldRemainder.getCount();
            int restored = (remainder.isEmpty() ? 0 : remainder.getCount()) - world;
            return restored >= 0 && extractedPlayer.change(player, extracted, restored)
                    && before.change(player, extracted, -(accepted + world));
        }
        private static boolean remainderOf(ItemStack candidate, ItemStack original) {
            return candidate != null && original != null && (candidate.isEmpty()
                    || !original.isEmpty() && candidate.getCount() <= original.getCount()
                        && ItemStack.isSameItemSameComponents(candidate, original));
        }
        private boolean reject() { invalid = true; clear(); return false; }
        void clear() {
            if (drop != null) drop.abort();
            before = null; extractedPlayer = null; destinationBefore = null; returnedPlayer = null;
            extracted = null; remainder = null; dropStack = null; target = null; drop = null;
        }
        boolean retainsEvidence() { return extracted != null || target != null || drop != null; }
    }
}
