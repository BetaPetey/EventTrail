package io.github.betapetey.eventtrail.events.parts;

import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.BlockEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.CommittedBlockEvidence;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Records single-part removals from multi-part blocks (Parts A shared piece, design r2 fc23dbf1): one piece taken
 * from an AE2 cable bus, an Integrated Dynamics cable or an Ender IO conduit bundle, and a whole AE2 machine taken
 * down by wrench, so a part removal is never silent.
 *
 * <p>Rule (the Owner's depot rule): every stack the removal hands over (into the inventory, or onto the floor in the
 * claimed block's cell or at the player's drop point) gets one "Removed Item" row at the block; if the block is
 * gone, one break row as well. Changes made in place write nothing. A break the existing break path records stays
 * exactly as today: the bridge never writes a second row for it. Anything unrecognised is refused with a warning or
 * counted, never a wrong row.</p>
 *
 * <p>Two entry points: {@link #beginUse} brackets every right-click on a block ({@code handleUseItemOn}), and
 * {@link #beginRemoval} brackets the mod's {@code onDestroyedByPlayer} call on the break path, only after the
 * existing break checks passed. Every entry point catches every {@link Throwable} itself and runs only on the server
 * thread; the state lives in one per-server {@link ServerScope}, built at server start and dropped at stop.</p>
 *
 * <p>Per-mod readers ({@link PartReader}) are loaded by class name from {@link #READERS} only when their mod is
 * loaded. A missing reader class or member switches that reader's {@link AdapterKey} OFF until restart (build B
 * rule 2); any other reader failure skips the action with a de-duplicated warning.</p>
 */
public final class PartRemovalAuditBridge {
    /** Most pieces a reader may report for one block (more: an integrity failure, nothing recorded). */
    public static final int MAX_PIECES = 32;
    /** Most rows one action may write (more: nothing written, one warning). */
    static final int MAX_ROWS = 512;

    static final String CONSTRUCT_SITE = "bridge:PartRemovalAuditBridge.construct";
    static final String CLAIM_SITE = "bridge:PartRemovalAuditBridge.claims";
    static final String PIECES_SITE = "bridge:PartRemovalAuditBridge.pieces";
    static final String RECORD_SITE = "bridge:PartRemovalAuditBridge.record";
    static final String COLLECT_SITE = "bridge:PartRemovalAuditBridge.collect";
    static final String PART_LIST_BOUND = "part-list-bound";
    static final String PART_SLOT_KEY = "part-slot-key";
    /** An opened action was voided (a nested action, a joined call that threw): nothing written (review-01 M3). */
    static final String ACTION_VOIDED = "action-voided";
    /** An opened action's call threw: nothing written (review-01 M3). */
    static final String ACTION_ABANDONED = "action-abandoned";
    /** An opened action that removed something failed the finish checks (frame, live player, level, location). */
    static final String FINISH_CHECK = "finish-check";
    /** A removal was seen on a moving Sable frame: no row there, one warning and one count (r3 review M-1). */
    static final String MOVING_FRAME = "moving-frame";
    /** The claimed block entity was replaced, is gone or is no longer claimed while the block stands (r3 M-2). */
    static final String BLOCK_REPLACED = "block-replaced";
    /** A reader returned null instead of a piece list (r3 review m-1). */
    static final String PART_LIST_NULL = "part-list-null";
    /**
     * Parts readers design-03 5.1 (N-3 and the finish branch): an AE2 in-place family report met a removal in the
     * same action, or the existing break path owned the action. No family row is written; one count.
     */
    static final String IN_PLACE_MIXED = "in-place-mixed";
    /**
     * Design-03 N-2: a memory-card paste moved the player's items and the family's reports do not show all of it
     * (no hook ran, or a change no hook saw beside a report: review-02 MAJOR-2). No family row; one count on the
     * reader key and one dropped capture.
     */
    static final String IN_PLACE_UNSEEN = "in-place-unseen";
    /**
     * Review-02 NIT-4: the reader failed at finish on an action with an AE2 in-place report or handled change. The
     * reader failure is reported on its own; the family's report is dropped with one count.
     */
    static final String IN_PLACE_READ_FAILED = "in-place-read-failed";
    static final long WARNING_INTERVAL_MILLIS = 60_000L;

    private static final String READER_PACKAGE = "io.github.betapetey.eventtrail.events.parts.";
    /** The AE2 side of the in-place family's catch-all (N-2), loaded by class name only when AE2 is loaded. */
    static final String IN_PLACE_SIDE_CLASS = READER_PACKAGE + "Ae2InPlaceMoves";

    /** One reader table row: its key, the reader class (loaded only when the key's mod is loaded), its provider id. */
    record ReaderRow(AdapterKey key, String className, String providerId) {
        ReaderRow {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    /**
     * The reader table. Each per-mod lane adds only its reader class under exactly this name; the provider ids are
     * row data, frozen once shipped.
     */
    static final List<ReaderRow> READERS = List.of(
            new ReaderRow(AdapterKey.PARTS_AE2, READER_PACKAGE + "Ae2PartReader", "ae2-part-removal"),
            new ReaderRow(AdapterKey.PARTS_INTEGRATED_DYNAMICS, READER_PACKAGE + "IntegratedDynamicsPartReader",
                    "integrateddynamics-part-removal"),
            new ReaderRow(AdapterKey.PARTS_ENDERIO, READER_PACKAGE + "EnderIoPartReader", "enderio-part-removal"));

    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    /**
     * The fail-safe's fallback for {@code pieces}: its own object, never a list a reader can return (not the shared
     * {@code List.of()}), so a returned null is told apart from a reported failure (r3 review m-1).
     */
    private static final List<PartReader.Piece> NOT_LISTED = Collections.unmodifiableList(new ArrayList<>());
    /** Monotonic, never reset and holding no world object (as the drawers' counter): ids are unique per JVM run. */
    private static final AtomicLong NEXT_SESSION = new AtomicLong();
    /** Null while no server runs; set once per server start after the scope is fully built. */
    private static volatile ServerScope scope;

    private PartRemovalAuditBridge() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle (ProductionRuntimeServices)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Builds this server's scope (after the fail-safe's server-start reset, so a key OFF in an earlier run is tried
     * again). Never throws: on any failure no scope is set (nothing is recorded by this bridge on this server) and
     * one warning is written.
     */
    public static void startServer() {
        scope = null;
        try {
            scope = ServerScope.build(
                    PartRemovalAuditBridge::modLoaded,
                    PartRemovalAuditBridge::instantiate,
                    records -> EventTrailManager.getInstance().tryLogAll(records),
                    LOGGER::warn,
                    System::currentTimeMillis,
                    loadInPlaceSide(PartRemovalAuditBridge::modLoaded));
        } catch (Throwable failure) {
            scope = null;
            try {
                LOGGER.warn("{} Part removals: the part-removal bridge did not start ({}); part removals are not"
                                + " recorded on this server",
                        OptionalIntegrationCompatibility.LOG_MARKER, EventTrailDiagnostics.safeFailureSummary(failure));
            } catch (Throwable ignored) {
                // Optional compatibility must never prevent EventTrail startup.
            }
        }
    }

    /** A scope is set (a server runs and its scope was built); for the lifecycle unit test. */
    static boolean started() {
        return scope != null;
    }

    /** Drops this server's scope. Never throws. */
    public static void stopServer() {
        try {
            ServerScope ending = scope;
            scope = null;
            // An action is open only inside one call on the server thread, so this is normally false.
            if (ending != null && !ending.slot.isEmpty()) EventTrailManager.getInstance().countDroppedCaptures(1);
        } catch (Throwable ignored) {
            // A stop must never be skipped or failed by optional compatibility.
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Use path (ServerGamePacketListenerImplMixin, handleUseItemOn)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Opens a use action for a right-click on {@code position}, or returns a no-op boundary. Never throws. The wrap's
     * first pass on the network thread is a no-op.
     */
    public static UseBoundary beginUse(ServerPlayer player, InteractionHand hand, BlockPos position) {
        ServerScope current = scope;
        if (current == null || current.readers.isEmpty()) return UseBoundary.NOOP;
        try {
            return current.beginUse(player, hand, position);
        } catch (Throwable failure) {
            current.failed(null, RECORD_SITE, failure);
            return UseBoundary.NOOP;
        }
    }

    /** The use path's boundary: {@link #success()} after the packet returned normally, then {@link #close()}. */
    public static final class UseBoundary implements AutoCloseable {
        static final UseBoundary NOOP = new UseBoundary(null, null);

        private final ServerScope owner;
        private final OpenAction action;
        private boolean succeeded;
        private boolean closed;

        private UseBoundary(ServerScope owner, OpenAction action) {
            this.owner = owner;
            this.action = action;
        }

        /** The packet returned normally. */
        public void success() {
            if (action != null) succeeded = true;
        }

        /** GF-SCOPE (p2gf-4 #7): a part-removal action is open, so the clicked position is this bridge's. */
        public boolean opened() {
            return action != null;
        }

        /** Finishes the action if {@link #success()} ran, else abandons it; always empties the slot. Never throws. */
        @Override
        public void close() {
            if (action == null || closed) return;
            closed = true;
            try {
                if (succeeded) owner.finish(action);
                else owner.unrecorded(action, ACTION_ABANDONED);
            } catch (Throwable failure) {
                owner.failed(action.row.row().key(), RECORD_SITE, failure);
            } finally {
                owner.release(action, !succeeded);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Break path (ServerPlayerGameModeMixin, onDestroyedByPlayer)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Opens a break-path action around the mod's {@code onDestroyedByPlayer}, called only when the existing break
     * checks passed and its preimage exists. Returns a join handle when a use action is open at the same position,
     * and a no-op otherwise when anything else is open (the open action is voided). Never throws.
     */
    public static Removal beginRemoval(
            Level level,
            BlockPos position,
            Player player,
            BlockAuditPreimage preimage,
            AuditBlockLocations.Frame frame) {
        ServerScope current = scope;
        if (current == null || current.readers.isEmpty()) return Removal.NOOP;
        try {
            return current.beginRemoval(level, position, player, preimage, frame);
        } catch (Throwable failure) {
            // Without its handle a joined removal could not mark the outer use action as owned by the existing
            // break path, so whatever is open must not decide: fewer rows, never a second break row.
            current.slot.voidOpen();
            current.failed(null, RECORD_SITE, failure);
            return Removal.NOOP;
        }
    }

    /** The break path's handle: {@link #finish(boolean)} after the removal result is known, {@link #abandon()} if it threw. */
    public static final class Removal {
        public static final Removal NOOP = new Removal(null, null, null);

        private final ServerScope owner;
        private final OpenAction action;
        private final PartActionSlot.Handle join;
        private boolean done;

        private Removal(ServerScope owner, OpenAction action, PartActionSlot.Handle join) {
            this.owner = owner;
            this.action = action;
            this.join = join;
        }

        /**
         * The mod's call returned {@code removed}. True: the existing break path owns the removal and the bridge
         * writes nothing (a joined use action is marked so). False: the bridge decides. Never throws.
         */
        public void finish(boolean removed) {
            if (owner == null || done) return;
            done = true;
            try {
                if (join != null) {
                    if (removed) owner.slot.ownedByExistingPath(join);
                } else if (!removed) {
                    owner.finish(action);
                }
            } catch (Throwable failure) {
                owner.failed(key(), RECORD_SITE, failure);
            } finally {
                if (join != null) owner.slot.close(join);
                else owner.release(action, false);
            }
        }

        /** The mod's call threw: the action is void (nothing written) and the slot is emptied. Never throws. */
        public void abandon() {
            if (owner == null || done) return;
            done = true;
            try {
                // A thrown joined call voids the outer use action, which warns once when it ends.
                if (join == null) owner.unrecorded(action, ACTION_ABANDONED);
            } catch (Throwable failure) {
                owner.failed(key(), RECORD_SITE, failure);
            } finally {
                if (join != null) owner.slot.abandon(join);
                else owner.release(action, true);
            }
        }

        private AdapterKey key() {
            return action == null ? null : action.row.row().key();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Collector glue (PartRemovalItemCollector.onEntityJoin)
    // ---------------------------------------------------------------------------------------------------------

    static void offerItemEntity(ServerLevel level, ItemEntity entity, boolean loadedFromDisk) {
        ServerScope current = scope;
        if (current != null) current.offer(level, entity, loadedFromDisk);
    }

    static void collectorFailed(Throwable failure) {
        ServerScope current = scope;
        if (current != null) current.failed(null, COLLECT_SITE, failure);
    }

    // ---------------------------------------------------------------------------------------------------------
    // AE2 in-place family glue (parts readers design-03 5.1)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The family's view of the open use action, or null. Never throws. Non-null only on the player's server thread
     * when the open action is a use action (not a join) of this same player, at {@code position}, in the player's
     * level, opened this tick, current in the slot and not voided ({@link #admitsInPlace}).
     */
    public static InPlaceFrame inPlaceFrame(ServerPlayer player, BlockPos position) {
        try {
            ServerScope current = scope;
            if (current == null || player == null || position == null) return null;
            return current.inPlaceFrame(player, position);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * Review-02 NIT-3: a family hook failed in its own reads before it could find its target, so it has no
     * position. The player's open use action of this tick (the scope rule of {@link #inPlaceFrame} at the action's
     * own position) writes no family row and gets no second line: the guard's report of the failure covers it.
     * Never throws.
     */
    public static void inPlaceLookupFailed(ServerPlayer player) {
        try {
            ServerScope current = scope;
            if (current == null || player == null) return;
            OpenAction action = current.open;
            if (action == null || action.position == null) return;
            InPlaceFrame frame = current.inPlaceFrame(player, action.position);
            if (frame != null) frame.markReported();
        } catch (Throwable ignored) {
            // The guard reports the hook's own failure; this must never affect the game's action.
        }
    }

    /**
     * The scope rule of {@link #inPlaceFrame} on plain values: the open handle is the slot's current one, a use
     * action and not a join, not voided, of the same player (identity), in the same level (identity), at the same
     * position, and opened in {@code tick}.
     */
    static boolean admitsInPlace(
            PartActionSlot.Handle handle,
            PartActionSlot.Handle current,
            boolean voided,
            Object actionPlayer,
            Object player,
            Object actionLevel,
            Object playerLevel,
            BlockPos actionPosition,
            BlockPos position,
            long tick) {
        return handle != null && handle == current && handle.path == PartActionSlot.Path.USE && !handle.isJoin()
                && !voided && actionPlayer != null && actionPlayer == player && actionLevel == playerLevel
                && actionPosition != null && actionPosition.equals(position) && handle.tick == tick;
    }

    /**
     * One open use action as the AE2 in-place family sees it (design-03 5.1). Touched only on the server thread,
     * inside the hook that asked for it; it holds no state of its own.
     */
    public static final class InPlaceFrame {
        private final OpenAction action;

        InPlaceFrame(OpenAction action) {
            this.action = Objects.requireNonNull(action, "action");
        }

        /** The item in the used hand when the action opened (air when the hand was empty). */
        public Item hand() {
            return action.handItem;
        }

        /** The number of item entities the action has admitted so far. */
        public int collectedMark() {
            return action.collected.size();
        }

        /** Copies of the stacks of the item entities admitted since {@code mark} that are still in the world. */
        public List<ItemStack> collectedSince(int mark) {
            return action.collectedSince(mark);
        }

        /**
         * Opens one family window. False when another window of this frame is open: the frame is then nested and
         * writes no family row.
         */
        public boolean openWindow() {
            if (action.windowOpen) {
                action.inPlaceNested = true;
                return false;
            }
            action.windowOpen = true;
            return true;
        }

        public void closeWindow() {
            action.windowOpen = false;
        }

        /** A window started inside another window of this frame. */
        public boolean nested() {
            return action.inPlaceNested;
        }

        /** A counted line already covers this action: the frame writes no family row. */
        public boolean dropped() {
            return action.inPlaceDropped || action.inPlaceNested;
        }

        /** Adds a window's rows to the frame's one report; the bridge writes them as one group at finish. */
        public void report(List<InPlaceMoveAudit.Row> rows) {
            if (rows == null || rows.isEmpty()) return;
            action.inPlaceRows.addAll(rows);
            action.inPlaceReport = true;
        }

        /** The block changed through a family hook (5.4): a REPLACED ending adds no block-replaced count. */
        public void markHandled() {
            action.handledInPlace = true;
        }

        /**
         * A family line covers this action: no family row is written and the bridge adds no second line. True
         * when no line covered the action before (the caller then counts its line), false when one already did.
         */
        public boolean markReported() {
            boolean first = !action.reported;
            action.reported = true;
            action.inPlaceDropped = true;
            return first;
        }
    }

    /**
     * The AE2 side the bridge's catch-all needs (N-2) without naming AE2: built by class name only when AE2 is
     * loaded ({@link #IN_PLACE_SIDE_CLASS}).
     */
    interface InPlaceSide {
        /** The item is an AE2 memory card. */
        boolean memoryCard(Item item);

        /**
         * The player's identity totals: every inventory slot except memory cards and Network Tools, plus the
         * Network Tool's inner inventory.
         */
        Map<InventoryItemIdentity, Long> playerTotals(ServerPlayer player);
    }

    /** Builds the AE2 side when AE2 is loaded; null otherwise or on any failure (reported). Never throws. */
    static InPlaceSide loadInPlaceSide(Predicate<String> modLoaded) {
        try {
            if (!modLoaded.test(AdapterKey.PARTS_AE2_INPLACE.modId())) return null;
            Class<?> type = Class.forName(IN_PLACE_SIDE_CLASS, true, PartRemovalAuditBridge.class.getClassLoader());
            return (InPlaceSide) type.getConstructor().newInstance();
        } catch (Throwable failure) {
            try {
                AdapterFailSafe.report(AdapterKey.PARTS_AE2_INPLACE, RECORD_SITE, failure);
            } catch (Throwable ignored) {
                // Optional compatibility must never prevent EventTrail startup.
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Plain-value decision, label and row assembly (unit-tested without a level)
    // ---------------------------------------------------------------------------------------------------------

    /** One "Removed Item" row to write: the item, the count, and whether it comes from the piece line. */
    record ItemRow(InventoryItemIdentity item, long count, boolean pieceLine) {
        ItemRow {
            Objects.requireNonNull(item, "item");
            if (count <= 0L) throw new IllegalArgumentException("count must be positive");
        }
    }

    /** What one finished action writes. */
    record Outcome(Kind kind, List<ItemRow> itemRows, int unnamedPieces) {
        static final Outcome NOTHING = new Outcome(Kind.NOTHING, List.of(), 0);

        enum Kind {
            NOTHING,
            /** The block is gone: one break row, then the item rows. */
            BLOCK_GONE,
            /** The block is still the claimed kind and pieces left it: the item rows only. */
            PIECES_LEFT
        }

        Outcome {
            Objects.requireNonNull(kind, "kind");
            itemRows = List.copyOf(itemRows);
        }
    }

    /**
     * The plain inputs of the outcome decision.
     *
     * @param ownedByExistingPath the existing break path committed this removal (break path {@code removed == true},
     *                            or a joined removal inside this use action did)
     * @param checksPassed        frame still current with a location and not moving, same live player, not voided
     * @param beforeFluidBlock    the block the old state's fluid leaves, or null
     * @param beforePieces        the reader's pieces before the action
     * @param after               the after values, or null when the checks did not pass
     * @param handedOver          everything handed over, per exact item identity
     */
    record FinishFacts(
            boolean ownedByExistingPath,
            boolean checksPassed,
            BlockState beforeFluidBlock,
            List<PartReader.Piece> beforePieces,
            PartSnapshot.After after,
            Map<InventoryItemIdentity, Long> handedOver) {
    }

    /**
     * The outcome order of design r2 2.2: the existing path owns it -> nothing; checks fail -> nothing; block gone
     * (air, or exactly the old state's fluid block) -> break row and item rows; block still the claimed kind (same
     * block entity object, still claimed) and at least one slot that held a piece is now empty -> item rows; anything
     * else (another block, a placeholder, a new block entity) -> nothing. A slot that holds a piece before and after
     * is an in-place change, never a removal.
     */
    static Outcome decide(FinishFacts facts) {
        if (facts.ownedByExistingPath() || !facts.checksPassed() || facts.after() == null) return Outcome.NOTHING;
        PartSnapshot.After after = facts.after();
        boolean gone = blockGone(facts.beforeFluidBlock(), after.state());
        List<PartReader.Piece> considered;
        if (gone) {
            considered = facts.beforePieces();
        } else if (after.sameClaimedBlockEntity()) {
            considered = piecesLeft(facts.beforePieces(), after.pieces());
            if (considered.isEmpty()) return Outcome.NOTHING;
        } else {
            return Outcome.NOTHING;
        }
        List<ItemRow> rows = new ArrayList<>();
        facts.handedOver().forEach((item, count) -> {
            if (count != null && count > 0L) rows.add(new ItemRow(item, count, false));
        });
        int unnamed = pieceLine(considered, facts.handedOver(), rows);
        return new Outcome(gone ? Outcome.Kind.BLOCK_GONE : Outcome.Kind.PIECES_LEFT, rows, unnamed);
    }

    /**
     * Whether the action removed anything at all: the block is gone, or the block is still the claimed kind and at
     * least one slot that held a piece is now empty. {@link ServerScope#finish} asks this before it copies the
     * inventory and builds item identities, so a right-click that removes nothing pays for none of it (review-01
     * M6). {@link #decide} applies the same two rules.
     */
    static boolean removalSeen(
            BlockState beforeFluidBlock, List<PartReader.Piece> beforePieces, PartSnapshot.After after) {
        if (after == null) return false;
        if (blockGone(beforeFluidBlock, after.state())) return true;
        return after.sameClaimedBlockEntity() && !piecesLeft(beforePieces, after.pieces()).isEmpty();
    }

    /**
     * The block stands (not gone) but the position no longer holds the same claimed block entity: it was replaced,
     * is gone, or its reader no longer claims it (r3 review M-2). Nothing can be read from such an action, so it is
     * reported, never silent.
     */
    static boolean blockReplaced(BlockState beforeFluidBlock, PartSnapshot.After after) {
        return after != null && !after.sameClaimedBlockEntity() && !blockGone(beforeFluidBlock, after.state());
    }

    /** How an action that the existing break path does not own and that was not voided ends (r3 review M-1, M-2). */
    enum Ending {
        /** Nothing was removed (no change, or an in-place change): no row, no line, no count. */
        NOTHING(null),
        /** {@link PartRemovalAuditBridge#blockReplaced}: nothing written, one warning and one count. */
        REPLACED(BLOCK_REPLACED),
        /** A removal was seen on a moving Sable frame: no row there, one warning and one count. */
        MOVING(MOVING_FRAME),
        /** A removal was seen, but the frame is not current or the player is not live in the action's level. */
        CHECK_FAILED(FINISH_CHECK),
        /** A removal was seen and the checks passed: decide and write. */
        DECIDE(null);

        /** The reason the fail-safe reports, or null when nothing is reported. */
        final String reason;

        Ending(String reason) {
            this.reason = reason;
        }
    }

    /**
     * The order of {@link ServerScope#finish} after the owned and voided returns (r3 review M-1): what the action
     * changed is asked first, so a click that removed nothing writes no line and no count and the frame and player
     * are not asked; then the moving frame; then the finish checks.
     */
    static Ending ending(
            BlockState beforeFluidBlock,
            List<PartReader.Piece> beforePieces,
            PartSnapshot.After after,
            BooleanSupplier moving,
            BooleanSupplier checksPass) {
        if (!removalSeen(beforeFluidBlock, beforePieces, after)) {
            return blockReplaced(beforeFluidBlock, after) ? Ending.REPLACED : Ending.NOTHING;
        }
        if (moving.getAsBoolean()) return Ending.MOVING;
        return checksPass.getAsBoolean() ? Ending.DECIDE : Ending.CHECK_FAILED;
    }

    /** Air, or exactly the block the old state's fluid makes (AE2 and Ender IO leave water when waterlogged). */
    static boolean blockGone(BlockState beforeFluidBlock, BlockState afterState) {
        return afterState != null && (afterState.isAir() || beforeFluidBlock != null && beforeFluidBlock.equals(afterState));
    }

    /** The pieces whose slot held a piece before and is empty after. */
    static List<PartReader.Piece> piecesLeft(List<PartReader.Piece> before, List<PartReader.Piece> after) {
        Set<String> stillFilled = new HashSet<>();
        for (PartReader.Piece piece : after) stillFilled.add(piece.slot());
        List<PartReader.Piece> left = new ArrayList<>();
        for (PartReader.Piece piece : before) {
            if (!stillFilled.contains(piece.slot())) left.add(piece);
        }
        return left;
    }

    /**
     * The piece line (design r2 2.3): for each item type, when more pieces of that type left than the summed handed
     * over count of that type, one row per missing piece, count 1, named from the piece's item. Matched by type only,
     * so a part handed over with different data never gets a second row; within a type, pieces whose exact item was
     * not handed over are named first. A piece without an item gets no row and is counted for one warning.
     *
     * @return the number of considered pieces without an item
     */
    static int pieceLine(
            List<PartReader.Piece> considered, Map<InventoryItemIdentity, Long> handedOver, List<ItemRow> rows) {
        int unnamed = 0;
        Map<Item, List<PartReader.Piece>> byType = new LinkedHashMap<>();
        for (PartReader.Piece piece : considered) {
            if (piece.item().isEmpty()) {
                unnamed++;
                continue;
            }
            byType.computeIfAbsent(piece.item().getItem(), ignored -> new ArrayList<>()).add(piece);
        }
        for (Map.Entry<Item, List<PartReader.Piece>> type : byType.entrySet()) {
            Map<InventoryItemIdentity, Long> remaining = new LinkedHashMap<>();
            long handedOfType = 0L;
            for (Map.Entry<InventoryItemIdentity, Long> handed : handedOver.entrySet()) {
                if (handed.getValue() == null || handed.getValue() <= 0L) continue;
                if (handed.getKey().stackWithCount(1).getItem() != type.getKey()) continue;
                handedOfType += handed.getValue();
                remaining.put(handed.getKey(), handed.getValue());
            }
            long missing = type.getValue().size() - handedOfType;
            if (missing <= 0L) continue;
            List<PartReader.Piece> unmatched = new ArrayList<>();
            List<PartReader.Piece> matched = new ArrayList<>();
            for (PartReader.Piece piece : type.getValue()) {
                InventoryItemIdentity identity = InventoryItemIdentity.of(piece.item());
                long left = remaining.getOrDefault(identity, 0L);
                if (left > 0L) {
                    remaining.put(identity, left - 1L);
                    matched.add(piece);
                } else {
                    unmatched.add(piece);
                }
            }
            List<PartReader.Piece> order = new ArrayList<>(unmatched);
            order.addAll(matched);
            for (int index = 0; index < missing && index < order.size(); index++) {
                rows.add(new ItemRow(InventoryItemIdentity.of(order.get(index).item()), 1L, true));
            }
        }
        return unnamed;
    }

    /**
     * The container label of the "Removed Item" rows: the break row's block name, from the same preimage, by the
     * call {@code BlockEvidenceNormalizer} makes, so one action names the block once (one-name rule). The two are
     * equal for a clean name within 256 UTF-8 bytes: {@code ContainerTarget} cleans every label and cuts it to 256
     * bytes, while the break row's subject is only bounded at 512 characters (review-01 N7).
     */
    static String containerLabel(BlockAuditPreimage preimage) {
        return DisplayNameResolver.capturedBlockStateDisplayName(
                preimage.canonicalBlockState().storageText(), preimage.blockEntityEvidence());
    }

    /**
     * The values every row of one action shares.
     *
     * @param captureFullMetadata the writer's advisory full-metadata hint for the item rows
     */
    record RowContext(
            ActorProvenance actor,
            UUID playerId,
            String playerName,
            BlockAuditPreimage preimage,
            BlockState afterState,
            AuditLocation location,
            String providerId,
            boolean captureFullMetadata) {
        RowContext {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(preimage, "preimage");
            Objects.requireNonNull(afterState, "afterState");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    /**
     * The rows of one outcome, the break row first: a BLOCK_BREAK row from the before snapshot (proof kind
     * {@code MOD_PIECE_REMOVAL_POST_STATE}) when the block is gone, then one CONTAINER_WITHDRAWAL row per item row,
     * only when the withdrawals switch is on.
     */
    static List<AuditRecord> assemble(Outcome outcome, RowContext context, boolean withdrawalsEnabled) {
        if (outcome.kind() == Outcome.Kind.NOTHING) return List.of();
        List<AuditRecord> rows = new ArrayList<>();
        if (outcome.kind() == Outcome.Kind.BLOCK_GONE) {
            Optional<CommittedBlockEvidence> evidence = CommittedBlockEvidence.verify(
                    AuditAction.BLOCK_BREAK,
                    context.actor(),
                    context.preimage(),
                    context.afterState(),
                    true,
                    CommittedBlockEvidence.ProofKind.MOD_PIECE_REMOVAL_POST_STATE);
            // A gone block always differs from its before state; anything else is refused whole.
            if (evidence.isEmpty()) return List.of();
            rows.add(BlockEvidenceNormalizer.toAuditRecord(evidence.get(), context.location()));
        }
        if (withdrawalsEnabled && !outcome.itemRows().isEmpty()) {
            long sessionId = NEXT_SESSION.incrementAndGet();
            if (sessionId <= 0L) return List.of();
            AuditLocation location = context.location();
            BlockPos position = location.position();
            String dimension = location.dimensionId().toString();
            ContainerTarget target = ContainerTarget.block(dimension, position, containerLabel(context.preimage()),
                    BackingContextKind.WORLD_BLOCK,
                    dimension + "|" + position.getX() + "," + position.getY() + "," + position.getZ());
            for (ItemRow item : outcome.itemRows()) {
                InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(
                        context.preimage().timestamp(), context.playerId(), context.playerName(), false,
                        sessionId, 1L, TransactionDirection.WITHDRAWAL, item.item(), item.count(), target,
                        context.providerId(), EvidenceKind.AUTHORITATIVE_MOD_OPERATION, InventorySlotRole.STORAGE);
                rows.addAll(InventoryTransactionNormalizer.toAuditRecords(evidence, context.captureFullMetadata()));
            }
        }
        return rows;
    }

    /** How an action with an AE2 in-place report or a handled block change ends (design-03 5.1). */
    enum InPlaceEnd {
        /** A removal was also seen (DECIDE, MOVING or CHECK_FAILED): no row at all, one {@code in-place-mixed}. */
        MIXED,
        /** The block entity was replaced and no family hook handled it: as today, {@code block-replaced}. */
        REPLACED,
        /** A family line already covers the action, or there is no report: nothing more. */
        COVERED,
        /** The report met a moving frame: {@code moving-frame}. */
        MOVING,
        /** The finish checks failed: {@code finish-check}. */
        CHECK_FAILED,
        /** Write the report as one group. */
        WRITE
    }

    /**
     * The in-place order of {@code finish} (design-03 5.1), on plain values: a removal seen in the same action
     * first, then a replaced block no hook handled, then a covered or empty report, then the moving frame, then
     * the finish checks. {@code moving} and {@code checksPass} are asked only when the report may be written.
     */
    static InPlaceEnd inPlaceEnd(
            Ending ending,
            boolean handled,
            boolean report,
            boolean dropped,
            BooleanSupplier moving,
            BooleanSupplier checksPass) {
        if (ending == Ending.DECIDE || ending == Ending.MOVING || ending == Ending.CHECK_FAILED) return InPlaceEnd.MIXED;
        if (ending == Ending.REPLACED && !handled) return InPlaceEnd.REPLACED;
        if (dropped || !report) return InPlaceEnd.COVERED;
        if (moving.getAsBoolean()) return InPlaceEnd.MOVING;
        return checksPass.getAsBoolean() ? InPlaceEnd.WRITE : InPlaceEnd.CHECK_FAILED;
    }

    /**
     * The rows of one family report (design-03 5.1), built as {@link #assemble} builds its item rows: one row per
     * exact identity, slot role and label with its net count ({@link InPlaceMoveAudit#net}), a put row DEPOSIT and
     * a take row WITHDRAWAL, each direction only while its switch is on, one session id per action, the host
     * block's name ({@link #containerLabel}) where the row names no part label.
     */
    static List<AuditRecord> assembleInPlace(
            List<InPlaceMoveAudit.Row> report, RowContext context, boolean depositsEnabled, boolean withdrawalsEnabled) {
        List<InPlaceMoveAudit.Row> net = InPlaceMoveAudit.net(report);
        List<InPlaceMoveAudit.Row> written = new ArrayList<>();
        for (InPlaceMoveAudit.Row row : net) {
            boolean enabled = row.direction() == TransactionDirection.DEPOSIT ? depositsEnabled : withdrawalsEnabled;
            if (enabled) written.add(row);
        }
        if (written.isEmpty()) return List.of();
        long sessionId = NEXT_SESSION.incrementAndGet();
        if (sessionId <= 0L) return List.of();
        AuditLocation location = context.location();
        BlockPos position = location.position();
        String dimension = location.dimensionId().toString();
        String contextKey = dimension + "|" + position.getX() + "," + position.getY() + "," + position.getZ();
        String hostLabel = containerLabel(context.preimage());
        List<AuditRecord> rows = new ArrayList<>();
        for (InPlaceMoveAudit.Row row : written) {
            ContainerTarget target = ContainerTarget.block(dimension, position,
                    row.label() == null ? hostLabel : row.label(), BackingContextKind.WORLD_BLOCK, contextKey);
            InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(
                    context.preimage().timestamp(), context.playerId(), context.playerName(), false,
                    sessionId, 1L, row.direction(), row.item(), row.count(), target,
                    context.providerId(), EvidenceKind.AUTHORITATIVE_MOD_OPERATION, row.role());
            rows.addAll(InventoryTransactionNormalizer.toAuditRecords(evidence, context.captureFullMetadata()));
        }
        return rows;
    }

    /** What {@link ServerScope#write} did with one outcome. */
    enum WriteResult {
        NOTHING,
        TOO_MANY_ROWS,
        NOT_READY,
        REFUSED,
        STORED
    }

    // ---------------------------------------------------------------------------------------------------------
    // Per-server scope
    // ---------------------------------------------------------------------------------------------------------

    /** Builds a reader from its class name (production: by reflection; tests: fakes). */
    @FunctionalInterface
    interface ReaderFactory {
        PartReader create(String className) throws Exception;
    }

    /**
     * One loaded mod's reader row: built at server start; while not built (a failure that is not a missing member),
     * retried at a claim check at most once per {@link PartRemovalAuditBridge#WARNING_INTERVAL_MILLIS}, so a reader
     * whose constructor keeps failing neither runs on every right-click nor adds a skipped action each time
     * (review-01 N6).
     */
    static final class ReaderSlot {
        private final ReaderRow row;
        private final ReaderFactory factory;
        private final LongSupplier clock;
        private PartReader reader;
        private boolean attempted;
        private long nextAttemptMillis;

        ReaderSlot(ReaderRow row, ReaderFactory factory, LongSupplier clock) {
            this.row = row;
            this.factory = factory;
            this.clock = clock;
        }

        ReaderRow row() {
            return row;
        }

        /** The built reader, building it if not built yet, the key is active and a retry is due; else null. */
        PartReader reader() {
            if (reader != null || !AdapterFailSafe.active(row.key())) return reader;
            long now = clock.getAsLong();
            if (attempted && now < nextAttemptMillis) return null;
            attempted = true;
            nextAttemptMillis = now + WARNING_INTERVAL_MILLIS;
            reader = AdapterFailSafe.call(row.key(), CONSTRUCT_SITE, () -> factory.create(row.className()), null);
            return reader;
        }
    }

    /**
     * Everything the bridge keeps for one server run: the built readers, the action slot (with its open action), the
     * warning gates and the sink. No {@code MinecraftServer} is kept; the server is reached through the player or the
     * level of the current call. Touched only on the server thread.
     */
    static final class ServerScope {
        final List<ReaderSlot> readers;
        final PartActionSlot slot = new PartActionSlot();
        private final Predicate<List<AuditRecord>> sink;
        private final Consumer<String> warnings;
        private final Map<String, RateLimitedDiagnosticGate> gates = new ConcurrentHashMap<>();
        /** The AE2 side of the in-place catch-all (design-03 5.1 N-2), or null when AE2 is not loaded. */
        final InPlaceSide inPlaceSide;
        private OpenAction open;

        private ServerScope(
                List<ReaderSlot> readers,
                Predicate<List<AuditRecord>> sink,
                Consumer<String> warnings,
                InPlaceSide inPlaceSide) {
            this.readers = List.copyOf(readers);
            this.sink = Objects.requireNonNull(sink, "sink");
            this.warnings = Objects.requireNonNull(warnings, "warnings");
            this.inPlaceSide = inPlaceSide;
        }

        /** Builds the readers of every loaded mod in the table; a mod that is not loaded loads no class at all. */
        static ServerScope build(
                Predicate<String> modLoaded,
                ReaderFactory factory,
                Predicate<List<AuditRecord>> sink,
                Consumer<String> warnings) {
            return build(modLoaded, factory, sink, warnings, System::currentTimeMillis);
        }

        /** As {@link #build(Predicate, ReaderFactory, Predicate, Consumer)} with the reader-retry clock given. */
        static ServerScope build(
                Predicate<String> modLoaded,
                ReaderFactory factory,
                Predicate<List<AuditRecord>> sink,
                Consumer<String> warnings,
                LongSupplier clock) {
            return build(modLoaded, factory, sink, warnings, clock, null);
        }

        /** As the five-argument build, with the AE2 in-place side given (null: none). */
        static ServerScope build(
                Predicate<String> modLoaded,
                ReaderFactory factory,
                Predicate<List<AuditRecord>> sink,
                Consumer<String> warnings,
                LongSupplier clock,
                InPlaceSide inPlaceSide) {
            Objects.requireNonNull(clock, "clock");
            List<ReaderSlot> built = new ArrayList<>();
            for (ReaderRow row : READERS) {
                if (!modLoaded.test(row.key().modId())) continue;
                ReaderSlot reader = new ReaderSlot(row, factory, clock);
                reader.reader();
                built.add(reader);
            }
            return new ServerScope(built, sink, warnings, inPlaceSide);
        }

        /** The family's frame for {@code player} at {@code position}, or null ({@link #admitsInPlace}). */
        InPlaceFrame inPlaceFrame(ServerPlayer player, BlockPos position) {
            OpenAction action = open;
            if (action == null || player.server == null || !player.server.isSameThread()) return null;
            if (!admitsInPlace(action.handle, slot.current(), slot.isVoided(action.handle), action.player, player,
                    action.level, player.serverLevel(), action.position, position, player.server.getTickCount())) {
                return null;
            }
            return new InPlaceFrame(action);
        }

        /** The first active reader that claims {@code blockEntity}, or null. */
        ReaderSlot claim(BlockEntity blockEntity) {
            if (blockEntity == null) return null;
            for (ReaderSlot reader : readers) {
                if (claims(reader, blockEntity)) return reader;
            }
            return null;
        }

        boolean claims(ReaderSlot reader, BlockEntity blockEntity) {
            AdapterKey key = reader.row().key();
            if (!AdapterFailSafe.active(key)) return false;
            PartReader built = reader.reader();
            if (built == null) return false;
            return Boolean.TRUE.equals(AdapterFailSafe.call(key, CLAIM_SITE, () -> built.claims(blockEntity), Boolean.FALSE));
        }

        /**
         * The reader's checked piece list, or null (the action records nothing) when the reader is not built, its key
         * is not active, it threw (the fail-safe reports it) or it broke its contract, which is reported here: a null
         * list (r3 review m-1), more than {@link PartRemovalAuditBridge#MAX_PIECES} pieces, a null piece, a malformed
         * or repeated slot key.
         */
        List<PartReader.Piece> pieces(ReaderSlot reader, BlockEntity blockEntity) {
            AdapterKey key = reader.row().key();
            PartReader built = reader.reader();
            if (built == null) return null;
            List<PartReader.Piece> listed =
                    AdapterFailSafe.call(key, PIECES_SITE, () -> built.pieces(blockEntity), NOT_LISTED);
            // Review r3 m-1: the fail-safe's own fallback (a throw it reported, or a key that is not active) is told
            // apart from a reader that returned null, which is reported here.
            if (listed == NOT_LISTED) return null;
            if (listed == null) {
                AdapterFailSafe.invalid(key, PIECES_SITE, PART_LIST_NULL);
                return null;
            }
            if (listed.size() > MAX_PIECES) {
                AdapterFailSafe.invalid(key, PIECES_SITE, PART_LIST_BOUND);
                return null;
            }
            Set<String> slots = new HashSet<>();
            for (PartReader.Piece piece : listed) {
                if (piece == null || !piece.wellFormed() || !slots.add(piece.slot())) {
                    AdapterFailSafe.invalid(key, PIECES_SITE, PART_SLOT_KEY);
                    return null;
                }
            }
            return List.copyOf(listed);
        }

        UseBoundary beginUse(ServerPlayer player, InteractionHand hand, BlockPos position) {
            if (player == null || hand == null || position == null
                    || player.server == null || !player.server.isSameThread()) return UseBoundary.NOOP;
            if (!liveActor(player)) return UseBoundary.NOOP;
            ServerLevel level = player.serverLevel();
            // A forged far-away packet never makes the bridge read the level.
            if (!level.hasChunkAt(position) || !player.canInteractWithBlock(position, 1.0)) return UseBoundary.NOOP;
            BlockEntity blockEntity = level.getBlockEntity(position);
            ReaderSlot reader = claim(blockEntity);
            if (reader == null) return UseBoundary.NOOP;
            // The counted not-ready gate runs only once a claimed block is the target, so a right-click on anything
            // else is not a skipped capture attempt (the drawers' M-1 rule).
            if (!EventTrailManager.getInstance().admitCapture()) return UseBoundary.NOOP;
            long tick = player.server.getTickCount();
            dropStale(tick);
            PartActionSlot.Handle handle = slot.openUse(position, level, tick);
            if (handle == null) return UseBoundary.NOOP;
            // Build B N-1 (508561a), review-01 M2: the hand-out is built inside the guarded region, so a failure
            // anywhere between opening the slot and handing out the only object that ends it releases the slot.
            UseBoundary boundary = null;
            try {
                AuditBlockLocations.Frame frame = AuditBlockLocations.capture(level, position).orElse(null);
                // Review r3 M-1: a current moving frame opens too; finish reports a removal seen on it.
                if (frameOpens(frame)) {
                    BlockState liveState = level.getBlockState(position);
                    // The same call and arguments as the break path's preimage (ServerPlayerGameModeMixin), so a
                    // wrench take-down is named exactly as a hand break of the same block.
                    BlockAuditPreimage preimage = BlockAuditPreimage.capture(
                            System.currentTimeMillis(),
                            level,
                            position,
                            liveState,
                            EventTrailManager.getInstance().canAcceptAuditWrite());
                    OpenAction action =
                            open(reader, handle, level, player, hand, position, blockEntity, frame, preimage);
                    if (action != null) boundary = new UseBoundary(this, action);
                }
            } catch (Throwable failure) {
                boundary = null;
                failed(reader.row().key(), RECORD_SITE, failure);
            } finally {
                if (boundary == null) release(handle);
            }
            return boundary == null ? UseBoundary.NOOP : boundary;
        }

        Removal beginRemoval(
                Level level,
                BlockPos position,
                Player player,
                BlockAuditPreimage preimage,
                AuditBlockLocations.Frame frame) {
            if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                    || position == null || preimage == null
                    || serverLevel.getServer() == null || !serverLevel.getServer().isSameThread()) return Removal.NOOP;
            long tick = serverLevel.getServer().getTickCount();
            dropStale(tick);
            switch (slot.enterRemoval(position, serverLevel)) {
                case JOIN:
                    // A failure here reaches the static entry, which voids the open use action.
                    return new Removal(this, null, slot.join());
                case NOOP:
                    return Removal.NOOP;
                case OPEN:
                default:
                    break;
            }
            if (!frameOpens(frame) || !liveActor(serverPlayer)) return Removal.NOOP;
            BlockEntity blockEntity = serverLevel.getBlockEntity(position);
            ReaderSlot reader = claim(blockEntity);
            if (reader == null) return Removal.NOOP;
            PartActionSlot.Handle handle = slot.openRemoval(position, serverLevel, tick);
            if (handle == null) return Removal.NOOP;
            // Build B N-1 (508561a), review-01 M2: the hand-out is built inside the guarded region.
            Removal removal = null;
            try {
                OpenAction action = open(reader, handle, serverLevel, serverPlayer, InteractionHand.MAIN_HAND,
                        position, blockEntity, frame, preimage);
                if (action != null) removal = new Removal(this, action, null);
            } catch (Throwable failure) {
                removal = null;
                failed(reader.row().key(), RECORD_SITE, failure);
            } finally {
                if (removal == null) release(handle);
            }
            return removal == null ? Removal.NOOP : removal;
        }

        /** Abandons a handle whose action could not be opened, and forgets that action if it was set. */
        private void release(PartActionSlot.Handle handle) {
            slot.abandon(handle);
            if (open != null && open.handle == handle) open = null;
        }

        /**
         * Review-01 M2 (b): an action still open from an earlier tick was left by a failure that skipped its release
         * (every action lives inside one call). It is dropped with one rate-limited warning and counted once as a
         * dropped capture, so part recording never stays blocked until restart.
         */
        void dropStale(long tick) {
            PartActionSlot.Handle stale = slot.dropStale(tick);
            if (stale == null) return;
            AdapterKey key = null;
            if (open != null && open.handle == stale) {
                key = open.row.row().key();
                open.reported = true;
                open.collected.clear();
                open.inPlaceRows.clear();
                open = null;
            }
            EventTrailManager.getInstance().countDroppedCaptures(1);
            AdapterKey named = key;
            warn(key, "stale-action", count -> (named == null ? "Part removals" : name(named)) + ": " + count
                    + " action(s) not recorded, an action left open by an earlier failure was dropped");
        }

        /**
         * Review-01 M3: an action the bridge opened ends without being decided (voided, abandoned, or failing the
         * finish checks). One de-duplicated warning and the key's skipped-action count, through the fail-safe; once
         * per action, and never for an action the existing break path recorded. Never throws.
         */
        void unrecorded(OpenAction action, String reason) {
            if (action == null || action.reported || action.handle.ownedByExistingPath()) return;
            action.reported = true;
            AdapterFailSafe.invalid(action.row.row().key(), RECORD_SITE, reason);
        }

        /** Takes the before snapshot and opens the action; null when the reader failed or no actor was captured. */
        private OpenAction open(
                ReaderSlot reader,
                PartActionSlot.Handle handle,
                ServerLevel level,
                ServerPlayer player,
                InteractionHand hand,
                BlockPos position,
                BlockEntity blockEntity,
                AuditBlockLocations.Frame frame,
                BlockAuditPreimage preimage) {
            List<PartReader.Piece> pieces = pieces(reader, blockEntity);
            if (pieces == null) return null;
            ActorProvenance actor = NeoForgePlayerProvenance.capture(player, ActorProvenance.Causality.DIRECT, null)
                    .orElse(null);
            if (actor == null) return null;
            int usedSlot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
            PartSnapshot before = new PartSnapshot(
                    preimage.blockState(),
                    PartSnapshot.fluidBlockOf(preimage.blockState()),
                    blockEntity,
                    preimage,
                    pieces,
                    inventoryCopy(player),
                    usedSlot,
                    frame);
            OpenAction action = new OpenAction(reader, handle, level, player, actor, position.immutable(), before,
                    player.getItemInHand(hand).getItem());
            armInPlace(action);
            open = action;
            return action;
        }

        /**
         * Design-03 5.1 N-2: on a use action whose hand holds an AE2 memory card, the player's identity totals at
         * open, for the catch-all at finish ({@link #unseenInPlace}). Any other click pays nothing. A failure of the
         * AE2 side is reported on the family key and leaves the action unarmed and dropped (re-check 01 NIT-1: with
         * no totals at open the leftover check cannot run, so the family writes no row for the action, as
         * {@link #inPlaceLookupFailed} does); it never affects the action.
         */
        void armInPlace(OpenAction action) {
            InPlaceSide side = inPlaceSide;
            if (side == null || action.handle.path != PartActionSlot.Path.USE || action.handItem == null) return;
            try {
                if (!side.memoryCard(action.handItem)) return;
                Map<InventoryItemIdentity, Long> totals = side.playerTotals(action.player);
                if (totals == null) return;
                action.totalsAtOpen = Map.copyOf(totals);
                action.cardAtOpen = true;
            } catch (Throwable failure) {
                action.cardAtOpen = false;
                action.totalsAtOpen = null;
                action.inPlaceDropped = true;
                failed(AdapterKey.PARTS_AE2_INPLACE, RECORD_SITE, failure);
            }
        }

        /**
         * Decides and writes one action that ended normally (the slot is released by the caller). An action the
         * existing break path recorded writes nothing; a voided action writes nothing with one warning (review-01
         * M3). Then the after state is read before any check (r3 review M-1, {@link PartRemovalAuditBridge#ending}):
         * a click that removed nothing writes no line; a replaced block entity (M-2), a removal seen on a moving
         * frame (M-1) or one that fails the finish checks writes nothing with one warning and one count; the
         * inventory is copied and its identities built only once a removal is seen and may be written (review-01
         * M6).
         */
        void finish(OpenAction action) {
            if (ownedReturn(action)) return;
            if (slot.isVoided(action.handle)) {
                unrecorded(action, ACTION_VOIDED);
                return;
            }
            PartSnapshot before = action.before;
            BlockState afterState = action.level.getBlockState(action.position);
            BlockEntity afterEntity = action.level.getBlockEntity(action.position);
            boolean same = afterEntity != null && afterEntity == before.blockEntity()
                    && claims(action.row, afterEntity);
            List<PartReader.Piece> afterPieces = List.of();
            if (same) {
                // A reader failure here is already reported (by the fail-safe, or as part-list-null: r3 m-1). A
                // family report on this action is dropped with it: counted once (review-02 NIT-4).
                afterPieces = pieces(action.row, afterEntity);
                if (afterPieces == null) {
                    if (action.inPlaceTouched()) unrecorded(action, IN_PLACE_READ_FAILED);
                    return;
                }
            }
            PartSnapshot.After after = new PartSnapshot.After(afterState, same, afterPieces);
            AuditBlockLocations.Frame frame = before.frame();
            Ending ending = ending(before.fluidBlock(), before.pieces(), after, frame::moving,
                    () -> frame.isCurrent() && liveActor(action.player) && action.player.serverLevel() == action.level);
            if (inPlaceFinish(action, ending, frame::moving,
                    () -> frame.isCurrent() && liveActor(action.player) && action.player.serverLevel() == action.level,
                    () -> {
                        Optional<AuditLocation> place = frame.currentLocation();
                        if (place.isEmpty()) return null;
                        return new RowContext(
                                action.actor,
                                action.player.getUUID(),
                                action.player.getGameProfile().getName(),
                                before.preimage(),
                                after.state(),
                                place.orElseThrow(),
                                action.row.row().providerId(),
                                EventTrailManager.getInstance().canAcceptAuditWrite());
                    },
                    () -> ContainerTransactionRecordingPolicy.enabled(TransactionDirection.DEPOSIT),
                    () -> ContainerTransactionRecordingPolicy.enabled(TransactionDirection.WITHDRAWAL),
                    () -> EventTrailManager.getInstance().admitCapture())) {
                return;
            }
            if (ending != Ending.DECIDE) {
                if (ending.reason != null) unrecorded(action, ending.reason);
                return;
            }
            Optional<AuditLocation> location = frame.currentLocation();
            if (location.isEmpty()) {
                unrecorded(action, FINISH_CHECK);
                return;
            }
            Map<InventoryItemIdentity, Long> handedOver = PartRemovalItemCollector.handedOver(
                    PartRemovalItemCollector.inventoryGains(
                            before.inventory(), inventoryCopy(action.player), before.usedSlot()),
                    presentStacks(action));
            Outcome outcome = decide(new FinishFacts(false, true, before.fluidBlock(), before.pieces(), after,
                    handedOver));
            if (outcome.kind() == Outcome.Kind.NOTHING) return;
            RowContext context = new RowContext(
                    action.actor,
                    action.player.getUUID(),
                    action.player.getGameProfile().getName(),
                    before.preimage(),
                    after.state(),
                    location.orElseThrow(),
                    action.row.row().providerId(),
                    EventTrailManager.getInstance().canAcceptAuditWrite());
            write(action.row.row(), outcome, context,
                    ContainerTransactionRecordingPolicy.enabled(TransactionDirection.WITHDRAWAL),
                    () -> EventTrailManager.getInstance().admitCapture());
        }

        /**
         * Writes one outcome as one group through the sink (all or none): the unnamed-piece warning, the 512-row
         * bound, the counted readiness gate, then {@link ActionRecordBatch}. A refused group writes nothing, with one
         * rate-limited warning; no retry, no partial write.
         */
        WriteResult write(
                ReaderRow row, Outcome outcome, RowContext context, boolean withdrawalsEnabled, BooleanSupplier ready) {
            if (outcome.unnamedPieces() > 0) {
                // The count is of actions since the last line, not of pieces (review-01 N2).
                warn(row.key(), "piece-item-empty", count -> name(row.key()) + ": " + count
                        + " action(s) had a removed piece that could not be named (piece-item-empty); no row was"
                        + " written for that piece");
            }
            List<AuditRecord> rows = assemble(outcome, context, withdrawalsEnabled);
            if (rows.isEmpty()) {
                // Unreachable today (a gone block always differs from its before state), but never silent (N9).
                if (outcome.kind() == Outcome.Kind.BLOCK_GONE) {
                    // Review r3 m-5: a lasting count beside the rate-limited line.
                    EventTrailManager.getInstance().countDroppedCaptures(1);
                    warn(row.key(), "break-unverified", count -> name(row.key()) + ": " + count
                            + " action(s) not recorded, the removed block's break could not be verified");
                }
                return WriteResult.NOTHING;
            }
            return publish(row, rows, ready);
        }

        /**
         * The write gates every group of one action passes (shared by {@link #write} and {@link #writeInPlace}):
         * the 512-row bound with its count, the counted readiness gate, then {@link ActionRecordBatch}, all or none;
         * a refused group writes nothing, with one rate-limited warning.
         */
        private WriteResult publish(ReaderRow row, List<AuditRecord> rows, BooleanSupplier ready) {
            if (rows.size() > MAX_ROWS) {
                EventTrailManager.getInstance().countDroppedCaptures(1);
                warn(row.key(), "too-many-rows", count -> name(row.key()) + ": " + count
                        + " action(s) not recorded, more than " + MAX_ROWS + " rows");
                return WriteResult.TOO_MANY_ROWS;
            }
            if (!ready.getAsBoolean()) return WriteResult.NOT_READY;
            boolean stored = ActionRecordBatch.collect(sink, batch -> batch.addAll(rows));
            if (stored) return WriteResult.STORED;
            warn(row.key(), "writer-refused", count -> name(row.key()) + ": " + count
                    + " action(s) not recorded, writer refused the group");
            return WriteResult.REFUSED;
        }

        /**
         * Design-03 5.1 N-3: the existing break path owns the action. True when {@code finish} must return here. A
         * family report (or a handled crafting change) on an owned action is never written next to the existing
         * path's break row: one {@code in-place-mixed} count on the reader key, once per action.
         */
        boolean ownedReturn(OpenAction action) {
            if (!action.handle.ownedByExistingPath()) return false;
            if (action.inPlaceTouched() && !action.reported) {
                action.reported = true;
                AdapterFailSafe.invalid(action.row.row().key(), RECORD_SITE, IN_PLACE_MIXED);
            }
            return true;
        }

        /**
         * The in-place part of {@code finish} once the ending is read (design-03 5.1; review-02 MINOR-4: outside
         * {@code finish}, so its order is tested). An action the family touched ends through {@link #inPlaceTail},
         * never as a removal: the bridge's own removal rows are unchanged, with the same finish checks as the
         * removal path. An untouched action that removed nothing goes to the N-2 catch-all ({@link #unseenInPlace}).
         * True when {@code finish} must return; false lets the removal path decide. The suppliers are asked only on
         * the in-place path.
         */
        boolean inPlaceFinish(
                OpenAction action,
                Ending ending,
                BooleanSupplier moving,
                BooleanSupplier checksPass,
                Supplier<RowContext> context,
                BooleanSupplier depositsEnabled,
                BooleanSupplier withdrawalsEnabled,
                BooleanSupplier ready) {
            if (action.inPlaceTouched()) {
                InPlaceEnd end = inPlaceEnd(ending, action.handledInPlace, action.inPlaceReport,
                        action.inPlaceDropped || action.inPlaceNested, moving, checksPass);
                inPlaceTail(action, end, context, depositsEnabled.getAsBoolean(), withdrawalsEnabled.getAsBoolean(),
                        ready);
                return true;
            }
            if (ending == Ending.NOTHING) {
                unseenInPlace(action);
                return true;
            }
            return false;
        }

        /**
         * The in-place tail of {@code finish} (design-03 5.1), after the owned and voided returns and the ending:
         * {@code context} is asked only when the rows may be written, and gives null when the frame has no current
         * location (the finish check fails). Before that, an action armed with a memory card whose player changed
         * more than the reports show writes nothing and counts {@code in-place-unseen} (review-02 MAJOR-2): never a
         * partial group.
         */
        WriteResult inPlaceTail(
                OpenAction action,
                InPlaceEnd end,
                Supplier<RowContext> context,
                boolean depositsEnabled,
                boolean withdrawalsEnabled,
                BooleanSupplier ready) {
            switch (end) {
                case MIXED:
                    unrecorded(action, IN_PLACE_MIXED);
                    return WriteResult.NOTHING;
                case REPLACED:
                    unrecorded(action, BLOCK_REPLACED);
                    return WriteResult.NOTHING;
                case MOVING:
                    unrecorded(action, MOVING_FRAME);
                    return WriteResult.NOTHING;
                case CHECK_FAILED:
                    unrecorded(action, FINISH_CHECK);
                    return WriteResult.NOTHING;
                case WRITE:
                    break;
                case COVERED:
                default:
                    return WriteResult.NOTHING;
            }
            if (unseenInPlace(action)) return WriteResult.NOTHING;
            RowContext rowContext = context.get();
            if (rowContext == null) {
                unrecorded(action, FINISH_CHECK);
                return WriteResult.NOTHING;
            }
            return writeInPlace(action.row.row(), action.inPlaceRows, rowContext, depositsEnabled, withdrawalsEnabled,
                    ready);
        }

        /** Writes the family's report as one group through the same gates as {@link #write} ({@link #publish}). */
        WriteResult writeInPlace(
                ReaderRow row,
                List<InPlaceMoveAudit.Row> report,
                RowContext context,
                boolean depositsEnabled,
                boolean withdrawalsEnabled,
                BooleanSupplier ready) {
            List<AuditRecord> rows = assembleInPlace(report, context, depositsEnabled, withdrawalsEnabled);
            if (rows.isEmpty()) return WriteResult.NOTHING;
            return publish(row, rows, ready);
        }

        /**
         * Design-03 5.1 N-2, the catch-all (review-02 MAJOR-2: a leftover check, not a window test). An action armed
         * at open (a memory card in hand) that no line covers yet, whose player's whole change since open is not
         * exactly what the family's reports show ({@link InPlaceMoveAudit#leftover}: the inventory now plus the item
         * entities collected since open, memory cards and Network Tools left out as at open, less every reported
         * row), writes no family row and counts one {@code in-place-unseen} on the reader key and one dropped
         * capture: never a partial group, and never a row for the leftover. This covers a paste no hook saw, and a
         * change no hook saw beside a hook's report (an addon station whose key is OFF, after the memory-card window).
         * The hand test comes first, so the second copy is taken only for such a paste. True when the action's family
         * rows must not be written: counted here, or the AE2 side failed and was reported.
         */
        boolean unseenInPlace(OpenAction action) {
            InPlaceSide side = inPlaceSide;
            if (side == null || !action.cardAtOpen || action.reported || action.totalsAtOpen == null
                    || action.handle.ownedByExistingPath()) return false;
            Map<InventoryItemIdentity, Long> leftover;
            try {
                Map<InventoryItemIdentity, Long> now = new LinkedHashMap<>(
                        Objects.requireNonNull(side.playerTotals(action.player), "player totals"));
                InPlaceMoveAudit.totals(action.collectedSince(0), stack -> side.memoryCard(stack.getItem()))
                        .forEach((identity, count) -> now.merge(identity, count, Long::sum));
                leftover = InPlaceMoveAudit.leftover(action.totalsAtOpen, now, action.inPlaceRows,
                        identity -> side.memoryCard(identity.stackWithCount(1).getItem()));
            } catch (Throwable failure) {
                failed(AdapterKey.PARTS_AE2_INPLACE, RECORD_SITE, failure);
                return true;
            }
            if (leftover.isEmpty()) return false;
            unrecorded(action, IN_PLACE_UNSEEN);
            EventTrailManager.getInstance().countDroppedCaptures(1);
            return true;
        }

        void offer(ServerLevel level, ItemEntity entity, boolean loadedFromDisk) {
            OpenAction action = open;
            if (action == null || slot.current() != action.handle || slot.isVoided(action.handle)) return;
            try {
                // An action left open from an earlier tick (M2) collects nothing; the next begin drops it.
                if (level.getServer() == null || level.getServer().getTickCount() != action.handle.tick) return;
                // Review r3 m-4: one item entity delivered twice is counted once.
                if (PartRemovalItemCollector.alreadyCounted(action.collected, entity)) return;
                ServerPlayer player = action.player;
                PartRemovalItemCollector.Verdict verdict = PartRemovalItemCollector.admits(
                        action.level, level, loadedFromDisk, entity.getX(), entity.getY(), entity.getZ(),
                        action.position, player.getX(), player.getEyeY() - PartRemovalItemCollector.DROP_EYE_OFFSET,
                        player.getZ(), action.collected.size());
                if (verdict == PartRemovalItemCollector.Verdict.COUNT) {
                    action.collected.add(entity);
                } else if (verdict == PartRemovalItemCollector.Verdict.OVERFLOW) {
                    slot.voidOpen();
                    // Warned here; its finish must not warn a second time as a voided action (M3).
                    action.reported = true;
                    AdapterKey key = action.row.row().key();
                    // Review r3 m-5: a lasting count beside the rate-limited line.
                    EventTrailManager.getInstance().countDroppedCaptures(1);
                    warn(key, "too-many-items", count -> name(key) + ": " + count + " action(s) not recorded, more than "
                            + PartRemovalItemCollector.MAX_ITEM_ENTITIES + " item entities");
                }
            } catch (Throwable failure) {
                slot.voidOpen();
                action.reported = true;
                failed(action.row.row().key(), COLLECT_SITE, failure);
            }
        }

        /** Empties the slot for {@code action} (abandoned: voided first) and drops everything the action held. */
        void release(OpenAction action, boolean abandoned) {
            if (action == null) return;
            if (abandoned) slot.abandon(action.handle);
            else slot.close(action.handle);
            action.collected.clear();
            action.inPlaceRows.clear();
            if (open == action) open = null;
        }

        /**
         * One failure of EventTrail's part: with a key, the fail-safe sorts it (a missing member switches that key
         * OFF until restart; anything else skips the action with a de-duplicated warning); without one, a
         * rate-limited warning. Never throws; allocates nothing for a stack or memory error.
         */
        void failed(AdapterKey key, String site, Throwable failure) {
            if (key != null) {
                AdapterFailSafe.report(key, site, failure);
                return;
            }
            if (failure instanceof StackOverflowError || failure instanceof OutOfMemoryError) return;
            try {
                warn(null, "bridge-failure", count -> "Part removals: " + count
                        + " action(s) not recorded, part-removal bridge error ("
                        + EventTrailDiagnostics.safeFailureSummary(failure) + ")");
            } catch (Throwable ignored) {
                // A warning must never affect the game's action.
            }
        }

        private void warn(AdapterKey key, String reason, LongFunction<String> message) {
            try {
                String gateKey = (key == null ? "bridge" : key.name()) + "|" + reason;
                gates.computeIfAbsent(gateKey, ignored -> new RateLimitedDiagnosticGate(WARNING_INTERVAL_MILLIS))
                        .acquire()
                        .ifPresent(emission -> warnings.accept(OptionalIntegrationCompatibility.LOG_MARKER + " "
                                + message.apply(emission.suppressedSinceLastEmission() + 1L)));
            } catch (Throwable ignored) {
                // A warning must never affect the game's action.
            }
        }

        private List<ItemStack> presentStacks(OpenAction action) {
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemEntity entity : action.collected) {
                if (!PartRemovalItemCollector.presentAtFinish(
                        entity.isRemoved(), action.level.getEntity(entity.getId()), entity)) continue;
                // The stack as it is now: a partial pickup inside the call is then counted once, by the inventory.
                ItemStack stack = entity.getItem();
                if (!stack.isEmpty()) stacks.add(stack.copy());
            }
            return stacks;
        }
    }

    /** One open action: held only for the duration of one call, dropped by {@link ServerScope#release}. */
    static final class OpenAction {
        final ReaderSlot row;
        final PartActionSlot.Handle handle;
        final ServerLevel level;
        final ServerPlayer player;
        final ActorProvenance actor;
        final BlockPos position;
        final PartSnapshot before;
        final List<ItemEntity> collected = new ArrayList<>();
        /** A warning or count already covers this action's loss, so it is not reported a second time (M3). */
        boolean reported;
        /** The item in the used hand at open (design-03 5.1), or null. */
        final Item handItem;
        /** The AE2 in-place family's one report (5.1): every window's rows, written as one group at finish. */
        final List<InPlaceMoveAudit.Row> inPlaceRows = new ArrayList<>();
        /** A family window reported rows. */
        boolean inPlaceReport;
        /** A family window started inside another window of this action: no family row. */
        boolean inPlaceNested;
        /**
         * A family line already covers this action ({@link InPlaceFrame#markReported}), or the AE2 side failed when
         * the action was armed ({@link ServerScope#armInPlace}): no family row.
         */
        boolean inPlaceDropped;
        /** A family window is open now. */
        boolean windowOpen;
        /** The block changed through a family hook (5.4). */
        boolean handledInPlace;
        /** The hand held an AE2 memory card at open and the totals below were taken (N-2). */
        boolean cardAtOpen;
        /** The player's identity totals at open (N-2), or null. */
        Map<InventoryItemIdentity, Long> totalsAtOpen;

        OpenAction(
                ReaderSlot row,
                PartActionSlot.Handle handle,
                ServerLevel level,
                ServerPlayer player,
                ActorProvenance actor,
                BlockPos position,
                PartSnapshot before) {
            this(row, handle, level, player, actor, position, before, null);
        }

        OpenAction(
                ReaderSlot row,
                PartActionSlot.Handle handle,
                ServerLevel level,
                ServerPlayer player,
                ActorProvenance actor,
                BlockPos position,
                PartSnapshot before,
                Item handItem) {
            this.row = row;
            this.handle = handle;
            this.level = level;
            this.player = player;
            this.actor = actor;
            this.position = position;
            this.before = before;
            this.handItem = handItem;
        }

        /** The family reported rows or changed the block through a hook (design-03 5.1). */
        boolean inPlaceTouched() {
            return inPlaceReport || handledInPlace;
        }

        /** Copies of the stacks of the item entities admitted since {@code mark} that are still in the world. */
        List<ItemStack> collectedSince(int mark) {
            List<ItemStack> stacks = new ArrayList<>();
            for (int index = Math.max(0, mark); index < collected.size(); index++) {
                ItemEntity entity = collected.get(index);
                if (entity.isRemoved()) continue;
                ItemStack stack = entity.getItem();
                if (!stack.isEmpty()) stacks.add(stack.copy());
            }
            return stacks;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The begin gate on the location frame, on both paths: a captured frame that is current, moving or not. A Sable
     * moving frame still writes no row (design r2 2.4 item 5: a world-block target on a moving frame would be the
     * wrong backing kind), but its action opens so that {@link ServerScope#finish} reports a removal seen there with
     * one warning and one count (r3 review M-1); a click there that removes nothing writes neither.
     */
    static boolean frameOpens(AuditBlockLocations.Frame frame) {
        return frame != null && frame.isCurrent();
    }

    /**
     * A live, real player on its server thread: as the gadget recorder's live-actor test. GF-FAKEPLAYER-ID fu2: the
     * listed-player test is the counted {@link NeoForgePlayerProvenance#isAdmittedPlayer}, after the uncounted tests
     * before it, so an unlisted player that is not a FakePlayer is counted in the unlisted-player status line.
     */
    static boolean liveActor(ServerPlayer player) {
        return player != null && player.server != null && player.server.isSameThread()
                && !player.isRemoved() && !player.isSpectator() && !NeoForgePlayerProvenance.isSynthetic(player)
                && NeoForgePlayerProvenance.isAdmittedPlayer(player)
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }

    private static List<ItemStack> inventoryCopy(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> copy = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) copy.add(inventory.getItem(slot).copy());
        return copy;
    }

    private static String name(AdapterKey key) {
        return key.displayName() + " (" + key.modId() + ")";
    }

    private static boolean modLoaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    /**
     * Loads one reader by class name. A missing class, a missing public no-argument constructor or a class that is
     * not a reader is a missing member (that key OFF until restart); a failure of the reader's own constructor is
     * sorted by the fail-safe from its cause.
     */
    static PartReader instantiate(String className) throws Exception {
        Class<?> type;
        try {
            type = Class.forName(className, true, PartRemovalAuditBridge.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw MissingAdapterMemberException.missingClass(className, missing);
        }
        if (!PartReader.class.isAssignableFrom(type)) {
            throw MissingAdapterMemberException.missing(className, "implements PartReader", null, null);
        }
        Constructor<?> constructor;
        try {
            constructor = type.getConstructor();
        } catch (NoSuchMethodException missing) {
            throw MissingAdapterMemberException.missing(className, "<init>", "()V", missing);
        }
        try {
            return (PartReader) constructor.newInstance();
        } catch (IllegalAccessException inaccessible) {
            throw MissingAdapterMemberException.inaccessible(className, "<init>", "()V", inaccessible);
        }
    }
}
