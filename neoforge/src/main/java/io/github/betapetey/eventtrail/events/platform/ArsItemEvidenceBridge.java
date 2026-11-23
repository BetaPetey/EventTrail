package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.CommittedItemDropEvidence;
import io.github.betapetey.eventtrail.events.evidence.CommittedItemPickupEvidence;
import io.github.betapetey.eventtrail.events.evidence.ItemDropAuditSession;
import io.github.betapetey.eventtrail.events.evidence.ItemDropEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.ItemPickupEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.ItemPickupEvidenceVerifier;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Finality only for externally authorized, exact synchronous Ars item effects.
 * Native cast/manager/member ownership belongs to the caller. This class has no
 * optional-mod linkage and does not observe arbitrary inventory mutations.
 */
public final class ArsItemEvidenceBridge {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final RateLimitedDiagnosticGate FAILURES = new RateLimitedDiagnosticGate(60_000L);
    private static final int INVENTORY_SLOTS = 41;
    private static final ThreadLocal<Boundary> ACTIVE = new ThreadLocal<>();
    private static final PickupBoundary NO_PICKUP = new PickupBoundary();
    private static final TossBoundary NO_TOSS = new TossBoundary();

    private ArsItemEvidenceBridge() { }

    public static PickupBoundary beginPickup(ServerPlayer player, ItemEntity source,
            ItemStack sourceStack, BooleanSupplier currentAuthority) {
        if (rejectNested()) return NO_PICKUP;
        try {
            Context context = capture(player, currentAuthority);
            if (context == null || source == null || sourceStack == null || sourceStack.isEmpty()
                    || source.getItem() != sourceStack || !registered(source, context.level)) return NO_PICKUP;
            PickupBoundary boundary = new PickupBoundary();
            boundary.context = context;
            boundary.source = source;
            boundary.sourceReference = sourceStack;
            boundary.sourceId = source.getUUID();
            boundary.proof = new PickupProof(System.currentTimeMillis(), boundary.sourceId,
                    context.actor, context.level.dimension().location(), sourceStack,
                    context.before.count(sourceStack));
            ACTIVE.set(boundary);
            return boundary;
        } catch (RuntimeException | LinkageError failure) {
            failure("capture pickup", failure);
            return NO_PICKUP;
        }
    }

    /** Called immediately after the one native insertion returns its remainder. */
    public static void pickupInserted(PickupBoundary boundary, ItemStack remainder) {
        if (!owned(boundary)) return;
        try {
            Context context = boundary.context;
            if (!context.samePlayer() || boundary.source.getItem() != boundary.sourceReference
                    || !boundary.sourceId.equals(boundary.source.getUUID())
                    || !registered(boundary.source, context.level)) {
                boundary.invalidate();
                return;
            }
            InventorySnapshot after = InventorySnapshot.capture(context.inventory);
            resyncIfChanged(context, after);
            if (!boundary.proof.inserted(remainder, after.count(boundary.proof.original))) {
                boundary.invalidate();
                return;
            }
            boundary.remainderReference = remainder;
            boundary.remainderSnapshot = remainder.copy();
        } catch (RuntimeException | LinkageError failure) {
            boundary.invalidate();
            failure("observe pickup insertion", failure);
        }
    }

    /** Called after the native setItem(remainder), before mutable Post listeners. */
    public static void pickupSourceUpdated(PickupBoundary boundary) {
        if (!owned(boundary)) return;
        try {
            Context context = boundary.context;
            ItemEntity source = boundary.source;
            boolean exactRemainder = boundary.remainderReference != null
                    && source.getItem() == boundary.remainderReference
                    && sameStack(source.getItem(), boundary.remainderSnapshot);
            Entity registered = context.level.getEntity(boundary.sourceId);
            boolean exhaustedRemoval = boundary.remainderSnapshot != null
                    && boundary.remainderSnapshot.isEmpty()
                    && source.isRemoved() && source.getRemovalReason() == Entity.RemovalReason.DISCARDED
                    && (registered == null || registered == source);
            if (!context.samePlayer() || source.level() != context.level
                    || !boundary.sourceId.equals(source.getUUID()) || !exactRemainder
                    || (!registered(source, context.level) && !exhaustedRemoval)
                    || !boundary.proof.sourceUpdated(source.blockPosition())) {
                boundary.invalidate();
            }
        } catch (RuntimeException | LinkageError failure) {
            boundary.invalidate();
            failure("confirm pickup source remainder", failure);
        }
    }

    public static void finishPickup(PickupBoundary boundary) {
        if (!claim(boundary)) return;
        try {
            Context context = boundary.context;
            resyncAfterOperation(context);
            Optional<CommittedItemPickupEvidence> evidence = boundary.proof.complete(
                    !boundary.invalid, context.current());
            if (evidence.isPresent() && EventTrailConfig.TRACK_ITEM_PICKUPS.get()
                    && EventTrailManager.getInstance().admitCapture()) {
                EventTrailManager.getInstance().log(ItemPickupEvidenceNormalizer.toAuditRecord(
                        evidence.orElseThrow(), EventTrailManager.getInstance().canAcceptAuditWrite()));
            }
        } catch (RuntimeException | LinkageError failure) {
            failure("finalize pickup", failure);
        } finally {
            clear(boundary);
        }
    }

    public static void abortPickup(PickupBoundary boundary) { abort(boundary); }

    public static TossBoundary beginToss(ServerPlayer player, BooleanSupplier currentAuthority) {
        if (rejectNested()) return NO_TOSS;
        try {
            Context context = capture(player, currentAuthority);
            if (context == null) return NO_TOSS;
            TossBoundary boundary = new TossBoundary();
            boundary.context = context;
            boundary.proof = new TossProof(System.currentTimeMillis(), context.actor,
                    context.level.dimension().location(), context.before);
            ACTIVE.set(boundary);
            return boundary;
        } catch (RuntimeException | LinkageError failure) {
            failure("capture toss", failure);
            return NO_TOSS;
        }
    }

    /** The actual result of the exact native extraction, before world spawning. */
    public static void extracted(TossBoundary boundary, ItemStack actual) {
        if (!owned(boundary)) return;
        try {
            Context context = boundary.context;
            if (!context.samePlayer()) {
                boundary.invalidate();
                return;
            }
            InventorySnapshot after = InventorySnapshot.capture(context.inventory);
            resyncIfChanged(context, after);
            if (!boundary.proof.extracted(actual, after)) boundary.invalidate();
        } catch (RuntimeException | LinkageError failure) {
            boundary.invalidate();
            failure("observe toss extraction", failure);
        }
    }

    /** Must bracket addFreshEntity: post-state alone cannot prove a new entity. */
    public static void admitting(TossBoundary boundary, ItemEntity entity) {
        if (!owned(boundary)) return;
        try {
            Context context = boundary.context;
            boolean newEntity = context.samePlayer() && entity != null
                    && entity.level() == context.level && !entity.isAddedToLevel() && !entity.isRemoved()
                    && context.level.getEntity(entity.getUUID()) == null;
            if (!boundary.proof.admitting(newEntity, entity == null ? null : entity.getItem())) {
                boundary.invalidate();
                return;
            }
            boundary.entity = entity;
            boundary.entityId = entity.getUUID();
        } catch (RuntimeException | LinkageError failure) {
            boundary.invalidate();
            failure("capture new toss entity", failure);
        }
    }

    /** Called only after that same native addFreshEntity returns normally. */
    public static void admitted(TossBoundary boundary, ItemEntity entity, boolean result) {
        if (!owned(boundary)) return;
        try {
            Context context = boundary.context;
            boolean admitted = result && context.samePlayer() && entity != null
                    && entity == boundary.entity && entity.getUUID().equals(boundary.entityId)
                    && registered(entity, context.level);
            if (!boundary.proof.admitted(entity == null ? null : entity.getUUID(),
                    entity == null ? null : entity.level().dimension().location(),
                    entity == null ? null : entity.blockPosition(),
                    entity == null ? null : entity.getItem(), admitted,
                    InventorySnapshot.capture(context.inventory))) boundary.invalidate();
        } catch (RuntimeException | LinkageError failure) {
            boundary.invalidate();
            failure("observe toss admission", failure);
        }
    }

    public static void finishToss(TossBoundary boundary) {
        if (!claim(boundary)) return;
        try {
            Context context = boundary.context;
            resyncAfterOperation(context);
            Optional<CommittedItemDropEvidence> evidence = boundary.proof.complete(
                    !boundary.invalid, context.current());
            if (evidence.isPresent() && EventTrailConfig.TRACK_ITEM_DROPS.get()
                    && EventTrailManager.getInstance().admitCapture()) {
                EventTrailManager.getInstance().log(ItemDropEvidenceNormalizer.toAuditRecord(
                        evidence.orElseThrow(), EventTrailManager.getInstance().canAcceptAuditWrite()));
            }
        } catch (RuntimeException | LinkageError failure) {
            failure("finalize toss", failure);
        } finally {
            clear(boundary);
        }
    }

    public static void abortToss(TossBoundary boundary) { abort(boundary); }

    private static Context capture(ServerPlayer player, BooleanSupplier authority) {
        if (player == null || authority == null || NeoForgePlayerProvenance.isSynthetic(player)
                || !(player.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || !player.isAlive()
                || level.getEntity(player.getUUID()) != player
                || player.getInventory().player != player) return null;
        ActorProvenance actor = NeoForgePlayerProvenance.capture(
                player, ActorProvenance.Causality.DIRECT, null).orElse(null);
        return actor == null ? null : new Context(player, player.getInventory(), level, actor,
                authority, authorityCurrent(authority), InventorySnapshot.capture(player.getInventory()));
    }

    static boolean authorityCurrent(BooleanSupplier authority) {
        try {
            return authority != null && authority.getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            failure("verify current item authority", failure);
            return false;
        }
    }

    private static boolean registered(ItemEntity entity, ServerLevel level) {
        return entity.level() == level && entity.isAddedToLevel() && !entity.isRemoved()
                && level.getEntity(entity.getUUID()) == entity;
    }

    private static boolean rejectNested() {
        Boundary active = ACTIVE.get();
        if (active == null) return false;
        active.invalidate();
        return true;
    }

    private static boolean owned(Boundary boundary) {
        if (boundary == null || boundary.context == null || boundary.closed) return false;
        if (ACTIVE.get() != boundary) {
            boundary.invalidate();
            return false;
        }
        return !boundary.invalid;
    }

    private static boolean claim(Boundary boundary) {
        if (boundary == null || boundary.context == null || boundary.closed) return false;
        boundary.closed = true;
        if (ACTIVE.get() != boundary) boundary.invalidate();
        return true;
    }

    private static void abort(Boundary boundary) {
        if (!claim(boundary)) return;
        try {
            resyncAfterOperation(boundary.context);
        } finally {
            clear(boundary);
        }
    }

    private static void clear(Boundary boundary) {
        boundary.invalidate();
        if (ACTIVE.get() == boundary) ACTIVE.remove();
        boundary.context = null;
        if (boundary instanceof PickupBoundary pickup) {
            pickup.source = null;
            pickup.sourceReference = null;
            pickup.remainderReference = null;
            pickup.remainderSnapshot = null;
            pickup.sourceId = null;
            pickup.proof = null;
        } else if (boundary instanceof TossBoundary toss) {
            toss.entity = null;
            toss.entityId = null;
            toss.proof = null;
        }
    }

    private static void resyncAfterOperation(Context context) {
        try {
            if (context.samePlayer()) resyncIfChanged(context, InventorySnapshot.capture(context.inventory));
        } catch (RuntimeException | LinkageError failure) {
            failure("resynchronize completed item operation", failure);
        }
    }

    private static void resyncIfChanged(Context context, InventorySnapshot after) {
        if (!context.before.sameContents(after)) {
            try {
                UniversalContainerTracker.onCommittedPlayerInventoryMutation(context.player);
            } catch (RuntimeException | LinkageError failure) {
                failure("resynchronize item possession", failure);
            }
        }
    }

    static int retainedOperationCount() { return ACTIVE.get() == null ? 0 : 1; }
    public static int pendingOperations() { return retainedOperationCount(); }
    /** Stop/restart cleanup. af-3 (re-check m4): an in-flight operation dropped here is counted once as skipped. */
    public static void clearPendingOperation() {
        Boundary boundary = ACTIVE.get();
        if (boundary == null) return;
        EventTrailManager.getInstance().countDroppedCaptures(1);
        abort(boundary);
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return first != null && second != null && ((first.isEmpty() && second.isEmpty())
                || (!first.isEmpty() && !second.isEmpty() && first.getCount() == second.getCount()
                && ItemStack.isSameItemSameComponents(first, second)));
    }

    /**
     * R2-inner: every catch site of this bridge ends here, keeps its own fail-closed answer, and is reported to
     * {@code ARS_ITEM} (a missing member switches the key OFF; anything else skips just that action) under the
     * {@code bridge:<Class>.<method>} site of the method that caught it ({@link #site}). The site is a constant, so
     * nothing is allocated at the edge of the stack. The phase stays the text of the rate-limited log line.
     */
    private static void failure(String phase, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.ARS_ITEM, site(phase), failure);
        FAILURES.acquire().ifPresent(emission -> LOGGER.error("Could not {} ({}); suppressed {} item failure(s)",
                phase, EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
    }

    /**
     * The fail-safe site of each catch phase: {@code bridge:ArsItemEvidenceBridge.<method that caught it>}; the bare
     * bridge name only for a phase not listed. Package-private for ArsEvidenceBridgeFailSafeTest.
     */
    static String site(String phase) {
        if (phase == null) return "bridge:ArsItemEvidenceBridge";
        return switch (phase) {
            case "capture pickup" -> "bridge:ArsItemEvidenceBridge.beginPickup";
            case "observe pickup insertion" -> "bridge:ArsItemEvidenceBridge.pickupInserted";
            case "confirm pickup source remainder" -> "bridge:ArsItemEvidenceBridge.pickupSourceUpdated";
            case "finalize pickup" -> "bridge:ArsItemEvidenceBridge.finishPickup";
            case "capture toss" -> "bridge:ArsItemEvidenceBridge.beginToss";
            case "observe toss extraction" -> "bridge:ArsItemEvidenceBridge.extracted";
            case "capture new toss entity" -> "bridge:ArsItemEvidenceBridge.admitting";
            case "observe toss admission" -> "bridge:ArsItemEvidenceBridge.admitted";
            case "finalize toss" -> "bridge:ArsItemEvidenceBridge.finishToss";
            case "verify current item authority" -> "bridge:ArsItemEvidenceBridge.authorityCurrent";
            case "resynchronize completed item operation" -> "bridge:ArsItemEvidenceBridge.resyncAfterOperation";
            case "resynchronize item possession" -> "bridge:ArsItemEvidenceBridge.resyncIfChanged";
            default -> "bridge:ArsItemEvidenceBridge";
        };
    }

    private abstract static class Boundary {
        Context context;
        boolean closed;
        boolean invalid;
        void invalidate() { invalid = true; }
    }

    public static final class PickupBoundary extends Boundary {
        private ItemEntity source;
        private UUID sourceId;
        private ItemStack sourceReference;
        private ItemStack remainderReference;
        private ItemStack remainderSnapshot;
        private PickupProof proof;
        private PickupBoundary() { }
    }

    public static final class TossBoundary extends Boundary {
        private ItemEntity entity;
        private UUID entityId;
        private TossProof proof;
        private TossBoundary() { }
    }

    private record Context(ServerPlayer player, Inventory inventory, ServerLevel level,
            ActorProvenance actor, BooleanSupplier authority, boolean authoritativeAtEntry,
            InventorySnapshot before) {
        boolean samePlayer() {
            return level.getServer().isSameThread() && player.level() == level && player.isAlive()
                    && player.getInventory() == inventory && inventory.player == player
                    && level.getEntity(player.getUUID()) == player;
        }

        boolean current() { return authoritativeAtEntry && samePlayer() && authorityCurrent(authority); }
    }

    /** Fixed vanilla accountable inventory, copied once before the exact effect. */
    static final class InventorySnapshot {
        private final List<ItemStack> slots;

        InventorySnapshot(List<ItemStack> slots) {
            if (slots == null || slots.size() != INVENTORY_SLOTS) {
                throw new IllegalArgumentException("Expected the complete 41-slot player inventory");
            }
            this.slots = slots.stream().map(stack -> Objects.requireNonNull(stack, "stack").copy()).toList();
        }

        static InventorySnapshot capture(Inventory inventory) {
            if (inventory.getContainerSize() != INVENTORY_SLOTS) {
                throw new IllegalArgumentException("Unsupported accountable inventory shape");
            }
            List<ItemStack> stacks = new ArrayList<>(INVENTORY_SLOTS);
            for (int slot = 0; slot < INVENTORY_SLOTS; slot++) stacks.add(inventory.getItem(slot));
            return new InventorySnapshot(stacks);
        }

        long count(ItemStack identity) {
            if (identity == null || identity.isEmpty()) return 0;
            long count = 0;
            for (ItemStack stack : slots) {
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, identity)) {
                    count = Math.addExact(count, stack.getCount());
                }
            }
            return count;
        }

        boolean sameContents(InventorySnapshot other) {
            for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
                if (!sameStack(slots.get(slot), other.slots.get(slot))) return false;
            }
            return true;
        }
    }

    /** Loader-neutral, consumptive finality state; callers supply exact native facts. */
    static final class PickupProof {
        private final long timestamp;
        private final UUID sourceId;
        private final ActorProvenance actor;
        private final ResourceLocation dimension;
        private final ItemStack original;
        private final long before;
        private CommittedItemPickupEvidence pending;
        private boolean insertionSeen;
        private boolean sourceUpdated;
        private boolean invalid;
        private boolean closed;

        PickupProof(long timestamp, UUID sourceId, ActorProvenance actor, ResourceLocation dimension,
                ItemStack original, long before) {
            this.timestamp = timestamp;
            this.sourceId = sourceId;
            this.actor = actor;
            this.dimension = dimension;
            this.original = original.copy();
            this.before = before;
        }

        boolean inserted(ItemStack remainder, long after) {
            if (closed || invalid || insertionSeen) return reject();
            insertionSeen = true;
            pending = ItemPickupEvidenceVerifier.verifyPlayerInventoryGain(timestamp, sourceId,
                    actor, dimension, BlockPos.ZERO, original, remainder, before, after).orElse(null);
            return pending != null || reject();
        }

        boolean sourceUpdated(BlockPos position) {
            if (closed || invalid || sourceUpdated || pending == null || position == null) return reject();
            sourceUpdated = true;
            pending = new CommittedItemPickupEvidence(pending.timestamp(), pending.itemEntityId(),
                    pending.actor(), pending.dimensionId(), position, pending.stack(), pending.proofKind());
            return true;
        }

        Optional<CommittedItemPickupEvidence> complete(boolean normal, boolean authorityCurrent) {
            if (closed) return Optional.empty();
            closed = true;
            CommittedItemPickupEvidence result = !invalid && normal && authorityCurrent && sourceUpdated
                    ? pending : null;
            pending = null;
            return Optional.ofNullable(result);
        }

        private boolean reject() { invalid = true; pending = null; return false; }
        boolean retainsEvidence() { return pending != null; }
    }

    /** Adds exact extraction/admission conservation without changing ordinary toss semantics. */
    static final class TossProof {
        private final long timestamp;
        private final ActorProvenance actor;
        private final ResourceLocation dimension;
        private final InventorySnapshot before;
        private ItemStack extracted;
        private ItemDropAuditSession session;
        private boolean extractionSeen;
        private boolean admissionStarted;
        private boolean admissionSeen;
        private boolean invalid;
        private boolean closed;

        TossProof(long timestamp, ActorProvenance actor, ResourceLocation dimension, InventorySnapshot before) {
            this.timestamp = timestamp;
            this.actor = actor;
            this.dimension = dimension;
            this.before = before;
        }

        boolean extracted(ItemStack actual, InventorySnapshot after) {
            if (closed || invalid || extractionSeen) return reject();
            extractionSeen = true;
            if (actual == null || actual.isEmpty() || !conservedLoss(actual, after)) return reject();
            extracted = actual.copy();
            session = new ItemDropAuditSession(timestamp, actor, dimension, extracted);
            return session.acceptCandidate(actor, actual) || reject();
        }

        boolean admitting(boolean newEntity, ItemStack stack) {
            if (closed || invalid || admissionStarted || session == null
                    || !newEntity || !sameStack(extracted, stack)) return reject();
            admissionStarted = true;
            return true;
        }

        boolean admitted(UUID entityId, ResourceLocation admittedDimension, BlockPos position,
                ItemStack stack, boolean admitted, InventorySnapshot after) {
            if (closed || invalid || admissionSeen || !admissionStarted || session == null) return reject();
            admissionSeen = true;
            if (!admitted || !sameStack(extracted, stack) || !conservedLoss(extracted, after)) return reject();
            return session.observe(actor, entityId, admittedDimension, position, stack, true) || reject();
        }

        Optional<CommittedItemDropEvidence> complete(boolean normal, boolean authorityCurrent) {
            if (closed) return Optional.empty();
            closed = true;
            Optional<CommittedItemDropEvidence> result = Optional.empty();
            if (!invalid && normal && authorityCurrent && admissionSeen && session != null) {
                result = session.complete(true, actor, dimension).evidence();
            }
            if (session != null) session.abort();
            session = null;
            extracted = null;
            return result;
        }

        private boolean conservedLoss(ItemStack stack, InventorySnapshot after) {
            return after != null && before.count(stack) - after.count(stack) == stack.getCount();
        }

        private boolean reject() {
            invalid = true;
            if (session != null) session.abort();
            session = null;
            extracted = null;
            return false;
        }

        boolean retainsEvidence() { return session != null || extracted != null; }
    }
}
