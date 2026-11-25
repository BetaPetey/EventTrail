package io.github.betapetey.eventtrail.inventory.graves;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.platform.CommandPresence;
import io.github.betapetey.eventtrail.inventory.access.TrashTargets;
import io.github.betapetey.eventtrail.inventory.action.TrashMoveWriter;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveTakerEvidence;
import io.github.betapetey.eventtrail.inventory.graves.api.OwnerPortion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A grave take (graves design r6, 1.3): runs the mod's hand-over method exactly once (through {@link HookGuard}) and
 * records what the taker got, per identity and owner portion, as CONTAINER_WITHDRAWAL rows in one batch. It never
 * changes what the mod does.
 *
 * <ol start="0">
 *   <li>Inside a command (1.7) the mod's method runs and nothing is read or recorded ({@code grave-in-command}).</li>
 *   <li value="0">Step 0b: the take is recorded only if the taker acts at the grave ({@link GraveTakerEvidence}) or one
 *   of the mod's listed paths is open: an own-form marker of the taker, or an on-behalf marker at the grave (whose rows
 *   go under the marker's acting player). Otherwise the mod's method runs and nothing is read or recorded
 *   ({@code grave-take-no-scope}).</li>
 *   <li>Before: the owner, contents and owner portions; the taker's 41 slots (main, armour, offhand) and Curios items;
 *   the own references with their counts. Only then the take context is opened (closed in a {@code finally}). A spec
 *   whose contents arrive during the call (r6 2.6) gives its contents and portions through {@link #contentsLoaded}
 *   inside the call instead, and its grave counts as empty after it.</li>
 *   <li>The mod's method runs; a throw passes unchanged and nothing is recorded.</li>
 *   <li>After: contents, slots and Curios again; per identity {@code left = max(0, before - after)},
 *   {@code net} over the slots plus Curios (0 Curios gain unless both reads succeeded),
 *   {@code taken = min(left, max(0, net + ownSpilled))} ({@code max(0, net)} when a copied spill of that identity was
 *   seen), {@code unaccounted = left - taken - spilledGrave - lost} counted when not 0.</li>
 *   <li>Rows: {@code taken} split over the piles by the pile rule, one row per part; the loss pairs of destroyed items
 *   ({@link TrashMoveWriter#recordsFor}); all in one {@link ActionRecordBatch}: all or none.</li>
 *   <li>The adapter's {@link GraveAdapterSpec#afterTake} receives the split.</li>
 * </ol>
 */
public final class GraveTakes {
    /** At most this many deferred entries per player (1.7 deferred form). */
    static final int MAX_DEFERRED_PER_PLAYER = 8;

    private static final AtomicLong NEXT_ACTION = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<Marker>> MARKERS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<UUID, ArrayDeque<Deferred>> DEFERRED = new HashMap<>();
    private static final Live LIVE = new ServerLive();

    private GraveTakes() {
    }

    /**
     * A take's split, after its rows (1.3-10): the parts taken per identity (each a portion with the units taken), the
     * piles that stay in the grave (reconciled with its contents after the take), and whether the rows were stored.
     */
    public record TakeSplit(GraveIdentity grave, Map<InventoryItemIdentity, List<OwnerPortion>> taken,
            Map<InventoryItemIdentity, OwnerPortions.Pile> remaining, boolean recorded) {
        public TakeSplit {
            Objects.requireNonNull(grave, "grave");
            Map<InventoryItemIdentity, List<OwnerPortion>> parts = new LinkedHashMap<>();
            taken.forEach((item, list) -> parts.put(item, List.copyOf(list)));
            taken = Collections.unmodifiableMap(parts);
            remaining = Collections.unmodifiableMap(new LinkedHashMap<>(remaining));
        }
    }

    /**
     * The take wrap: {@code taker} is the player whose inventory the take fills; {@code level} and the identity's
     * position are the grave's. The mod's call runs exactly once with {@code args}; its result is returned unchanged.
     */
    public static <G, R> R take(GraveAdapterSpec<G> spec, String site, G grave, GraveIdentity identity, Level level,
            ServerPlayer taker, Operation<R> original, Object... args) {
        return HookGuard.wrap(spec.key(), site, original,
                once -> body(LIVE, spec, site, grave, identity, level, taker, once::call), args);
    }

    /** Units of {@code stack} the mod is destroying now, inside an open take (Tombstone's loss roll, 2.2 hook 5). */
    public static void reportLoss(ItemStack stack) {
        try {
            SpillTags.TakeFrame take = SpillTags.currentTake();
            if (take == null || stack == null || stack.isEmpty()) return;
            take.lost.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
        } catch (Throwable ignored) {
            // The loss is then unaccounted, counted at the take's end; the mod's roll is never affected.
        }
    }

    /**
     * Graves r6 2.6: inside the open take of a spec whose contents arrive during the call
     * ({@link GraveAdapterSpec#contentsDuringCall()}), the list the mod has just loaded (Epitaphs' backup file). The
     * first report of a take sets the grave's contents (in the list's order) and owner portions, copying the counts at
     * once. A null list, a report inside a command (1.7), a report outside a take or inside a default spec's take, and
     * any report after the take's first do nothing. A failure of the first report (the spec's portions throwing or
     * returning null included) is kept on the take as its report: the take then records nothing, counts the grave's
     * units unaccounted and reports the failure. Never throws.
     *
     * <p>Not closed: a report goes to the innermost open take context. With opted-in takes nested, a report made while
     * the inner one is open is the inner one's; and a nested take that opens no context (one without scope, or one
     * whose key is inactive) leaves the report of its mod's load to an open opted-in take outside it.</p>
     */
    public static void contentsLoaded(Collection<ItemStack> loaded) {
        SpillTags.TakeFrame take = null;
        try {
            if (loaded == null || CommandPresence.inCommand()) return;
            take = SpillTags.currentTake();
            if (take == null || take.pilesOfLoaded == null || take.loaded != null || take.loadFailure != null) return;
            Map<InventoryItemIdentity, Long> contents = loadedCounts(loaded);
            Map<InventoryItemIdentity, OwnerPortions.Pile> piles = take.pilesOfLoaded.apply(contents);
            take.piles.clear();
            take.piles.putAll(piles);
            take.loaded = contents;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            // Never interrupts the mod's call. The failure is the take's report: later reports are ignored, spills
            // after it are tagged all unknown, and the take reports it after the call.
            if (take != null && take.pilesOfLoaded != null && take.loaded == null && take.loadFailure == null) {
                take.piles.clear();
                take.loadFailure = failure;
            }
        }
    }

    /** Whether a take's context is open on this thread (Simple Tomb's counting view stays off then, 2.3-6.5). */
    public static boolean takeOpen() {
        return SpillTags.takeOpen();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Listed paths (1.7, route B): markers around the player's own handler.

    private enum Form {
        OWN,
        ON_BEHALF
    }

    private record Marker(Form form, UUID actorId, ServerPlayer actor, Object level, BlockPos position) {
    }

    /**
     * A listed path in its own form: {@code handler} runs exactly once with a marker that {@code actor} is the taker
     * of any take inside it. No marker is opened inside a command.
     */
    public static <R> R ownPath(ServerPlayer actor, Supplier<R> handler) {
        return ownPath(LIVE, actor, handler);
    }

    static <R> R ownPath(Live live, ServerPlayer actor, Supplier<R> handler) {
        Objects.requireNonNull(handler, "handler");
        Marker marker = null;
        try {
            if (actor != null && !live.inCommand()) marker = new Marker(Form.OWN, live.id(actor), actor, null, null);
        } catch (Throwable unreadable) {
            marker = null;
        }
        return marked(marker, handler);
    }

    /**
     * A listed path in its on-behalf form (Epitaphs' op click, 2.6): {@code handler} runs exactly once with a marker
     * that a take of the grave at {@code level} and {@code position} inside it is {@code op}'s act, its rows indexed
     * under him. Opened only when {@code op}'s own scope part is true at that grave and no command is running.
     */
    public static <R> R onBehalfPath(ServerPlayer op, Level level, BlockPos position, Supplier<R> handler) {
        return onBehalfPath(LIVE, op, level, position, handler);
    }

    static <R> R onBehalfPath(Live live, ServerPlayer op, Object level, BlockPos position, Supplier<R> handler) {
        Objects.requireNonNull(handler, "handler");
        Marker marker = null;
        try {
            if (op != null && level != null && position != null && !live.inCommand()) {
                UUID opId = live.id(op);
                if (live.actingAt(opId, level, position)) {
                    marker = new Marker(Form.ON_BEHALF, opId, op, level, position.immutable());
                }
            }
        } catch (Throwable unreadable) {
            marker = null;
        }
        return marked(marker, handler);
    }

    private static <R> R marked(Marker marker, Supplier<R> handler) {
        if (marker == null) return handler.get();
        MARKERS.get().push(marker);
        try {
            return handler.get();
        } finally {
            try {
                ArrayDeque<Marker> markers = MARKERS.get();
                if (markers.peek() == marker) {
                    markers.pop();
                } else {
                    markers.removeFirstOccurrence(marker);
                }
                // The last marker closed: the thread keeps no entry (as ExplosionBrackets does for its frames).
                if (markers.isEmpty()) MARKERS.remove();
            } catch (Throwable ignored) {
                // Closing never interrupts the handler's own result or exception.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Deferred listed paths (1.7 form 3): a one-shot entry at the player's own click, consumed by the later take.

    private record Deferred(String graveKey, long deadlineTick) {
    }

    /**
     * At the player's own click: keeps a one-shot entry for the grave {@code graveKey}, valid until
     * {@code nowTick + delayTicks}, only if his scope part is true at that grave and no command is running. At most
     * {@value #MAX_DEFERRED_PER_PLAYER} per player (the oldest is dropped).
     */
    public static void offerDeferred(ServerPlayer player, Level level, BlockPos position, String graveKey,
            long nowTick, long delayTicks) {
        offerDeferred(LIVE, player, level, position, graveKey, nowTick, delayTicks);
    }

    static void offerDeferred(Live live, ServerPlayer player, Object level, BlockPos position, String graveKey,
            long nowTick, long delayTicks) {
        try {
            if (player == null || graveKey == null || delayTicks < 0L || live.inCommand()) return;
            UUID id = live.id(player);
            if (!live.actingAt(id, level, position)) return;
            synchronized (DEFERRED) {
                ArrayDeque<Deferred> entries = DEFERRED.computeIfAbsent(id, unused -> new ArrayDeque<>());
                prune(entries, nowTick);
                while (entries.size() >= MAX_DEFERRED_PER_PLAYER) entries.pollFirst();
                entries.addLast(new Deferred(graveKey, Math.addExact(nowTick, delayTicks)));
            }
        } catch (Throwable ignored) {
            // No entry: the later take then writes nothing, which is the fail-closed side.
        }
    }

    /**
     * The mod's deferred take for the grave {@code graveKey}: runs {@code take} exactly once, inside an own-form
     * marker of {@code player} only if it consumes a matching entry before its deadline (one-shot). Inside a command no
     * entry is consumed.
     */
    public static <R> R runDeferred(ServerPlayer player, String graveKey, long nowTick, Supplier<R> take) {
        return runDeferred(LIVE, player, graveKey, nowTick, take);
    }

    static <R> R runDeferred(Live live, ServerPlayer player, String graveKey, long nowTick, Supplier<R> take) {
        Objects.requireNonNull(take, "take");
        Marker marker = null;
        try {
            if (player != null && graveKey != null && !live.inCommand()) {
                UUID id = live.id(player);
                if (consume(id, graveKey, nowTick)) marker = new Marker(Form.OWN, id, player, null, null);
            }
        } catch (Throwable unreadable) {
            marker = null;
        }
        return marked(marker, take);
    }

    /** Clears a player's deferred entries (lead registration: player logout). */
    public static void onPlayerLogout(UUID playerId) {
        if (playerId == null) return;
        synchronized (DEFERRED) {
            DEFERRED.remove(playerId);
        }
    }

    /** Clears every deferred entry (lead registration: server stopped). */
    public static void onServerStopped() {
        synchronized (DEFERRED) {
            DEFERRED.clear();
        }
    }

    /** This thread's marker stack (test seam): a fresh empty one once the last marker has closed. */
    static ArrayDeque<?> markersOfThisThread() {
        return MARKERS.get();
    }

    /** How many deferred entries {@code playerId} holds (test seam). */
    static int deferredCount(UUID playerId) {
        synchronized (DEFERRED) {
            ArrayDeque<Deferred> entries = DEFERRED.get(playerId);
            return entries == null ? 0 : entries.size();
        }
    }

    private static boolean consume(UUID id, String graveKey, long nowTick) {
        synchronized (DEFERRED) {
            ArrayDeque<Deferred> entries = DEFERRED.get(id);
            if (entries == null) return false;
            prune(entries, nowTick);
            boolean found = false;
            for (Iterator<Deferred> it = entries.iterator(); it.hasNext(); ) {
                if (it.next().graveKey().equals(graveKey)) {
                    it.remove();
                    found = true;
                    break;
                }
            }
            if (entries.isEmpty()) DEFERRED.remove(id);
            return found;
        }
    }

    private static void prune(ArrayDeque<Deferred> entries, long nowTick) {
        entries.removeIf(entry -> entry.deadlineTick() < nowTick);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The take.

    /** The live parts of a take (test seam); {@link ServerLive} is the server's. */
    interface Live {
        boolean inCommand();

        boolean actingAt(UUID playerId, Object level, BlockPos position);

        UUID id(ServerPlayer player);

        /** The player's 41 slots (main, armour, offhand), by reference. */
        List<ItemStack> slots(ServerPlayer player);

        Optional<Map<InventoryItemIdentity, Long>> curios(ServerPlayer player);

        /** The rows' admission, in TrashMoveWriter's order. */
        Admission admit(ServerPlayer player);

        boolean fullMetadata();

        boolean recorded(TransactionDirection direction);

        long now();

        TrashMoveWriter.Records lossRecords(AdapterKey key, String site, ServerPlayer player, String providerId,
                List<TrashMoveWriter.Move> moves, ShownCause withdrawalCause);

        boolean store(List<AuditRecord> records);

        void report(AdapterKey key, String site, Throwable failure);
    }

    /** A rows admission: whether rows are written for the player, and his UUID and name when they are. */
    record Admission(Status status, UUID playerId, String playerName) {
        enum Status {
            ADMITTED,
            NOT_TRACKED,
            FAILED
        }

        static Admission admitted(UUID playerId, String playerName) {
            return new Admission(Status.ADMITTED, Objects.requireNonNull(playerId, "playerId"),
                    Objects.requireNonNull(playerName, "playerName"));
        }

        static Admission notTracked() {
            return new Admission(Status.NOT_TRACKED, null, null);
        }

        static Admission failed() {
            return new Admission(Status.FAILED, null, null);
        }
    }

    /** The take's body after HookGuard (test seam): {@code callMod} runs the mod's call exactly once. */
    static <G> void body(Live live, GraveAdapterSpec<G> spec, String site, G grave, GraveIdentity identity,
            Object level, ServerPlayer taker, Runnable callMod) {
        // Step 0 (1.7).
        if (live.inCommand()) {
            GraveCounts.count(GraveCounts.Reason.GRAVE_IN_COMMAND);
            callMod.run();
            return;
        }
        // Step 0b (route B).
        ServerPlayer rowsPlayer = gate(live, identity, level, taker);
        if (rowsPlayer == null) {
            GraveCounts.count(GraveCounts.Reason.GRAVE_TAKE_NO_SCOPE);
            callMod.run();
            return;
        }
        // Step 1.
        GraveOwnerFacts owner = spec.owner(grave, site);
        // r6 2.6: a grave whose contents arrive during the call is read through contentsLoaded only.
        boolean duringCall = spec.contentsDuringCall();
        Map<InventoryItemIdentity, Long> graveBefore = duringCall ? Map.of() : spec.contents(grave);
        Map<InventoryItemIdentity, OwnerPortions.Pile> piles = duringCall ? Map.of()
                : OwnerPortions.piles(graveBefore, spec.portions(grave, graveBefore, owner));
        IdentityHashMap<ItemStack, long[]> own = new IdentityHashMap<>();
        Map<InventoryItemIdentity, Long> slotsBefore = counts(live.slots(taker), own);
        Optional<Map<InventoryItemIdentity, Long>> curiosBefore = live.curios(taker);
        SpillTags.TakeFrame frame = new SpillTags.TakeFrame(identity.graveId(), piles, own);
        if (duringCall) {
            // As on the default path, a null result fails: reported, nothing recorded, never a guessed owner.
            frame.pilesOfLoaded = contents -> OwnerPortions.piles(contents, spec.portions(grave, contents, owner));
        }
        // Steps 2 and 3: the context is open only around the mod's call.
        SpillTags.openTake(frame);
        try {
            callMod.run();
        } finally {
            SpillTags.closeTake(frame);
        }
        // Step 4. r6 2.6: a grave whose contents arrived during the call holds nothing EventTrail can read after it;
        // what the mod did not hand over stays unaccounted (counted), never credited. A failed report is counted and
        // reported, and nothing is recorded, as when a read fails on the default path.
        if (duringCall && frame.loadFailure != null) {
            GraveCounts.count(GraveCounts.Reason.GRAVE_UNACCOUNTED);
            live.report(spec.key(), site, frame.loadFailure);
            return;
        }
        if (duringCall) graveBefore = frame.loaded != null ? frame.loaded : Map.of();
        Map<InventoryItemIdentity, Long> graveAfter = duringCall ? Map.of() : spec.contents(grave);
        Map<InventoryItemIdentity, Long> slotsAfter = counts(live.slots(taker), null);
        Optional<Map<InventoryItemIdentity, Long>> curiosAfter = live.curios(taker);
        boolean curiosRead = curiosBefore.isPresent() && curiosAfter.isPresent();
        if (!curiosRead) GraveCounts.count(GraveCounts.Reason.GRAVE_CURIOS_UNREAD);
        // Step 5 and 8.
        Map<InventoryItemIdentity, List<OwnerPortion>> taken = new LinkedHashMap<>();
        for (Map.Entry<InventoryItemIdentity, Long> entry : graveBefore.entrySet()) {
            InventoryItemIdentity item = entry.getKey();
            long left = Math.max(0L, entry.getValue() - graveAfter.getOrDefault(item, 0L));
            if (left == 0L) continue;
            long net = slotsAfter.getOrDefault(item, 0L) - slotsBefore.getOrDefault(item, 0L);
            if (curiosRead) net += curiosAfter.get().getOrDefault(item, 0L) - curiosBefore.get().getOrDefault(item, 0L);
            long units = frame.uncertainSpilled.getOrDefault(item, 0L) > 0L
                    ? Math.min(left, Math.max(0L, net))
                    : Math.min(left, Math.max(0L, net + frame.ownSpilled.getOrDefault(item, 0L)));
            long unaccounted = left - units - frame.spilledGrave.getOrDefault(item, 0L)
                    - frame.lost.getOrDefault(item, 0L);
            if (unaccounted != 0L) GraveCounts.count(GraveCounts.Reason.GRAVE_UNACCOUNTED);
            if (units > 0L) {
                OwnerPortions.Taken part = frame.pile(item).take(units);
                taken.put(item, part.parts());
                frame.piles.put(item, part.remaining());
            }
        }
        // Step 9.
        boolean recorded = writeRows(live, spec, site, rowsPlayer, identity, owner, taken, frame.lost);
        // Step 10.
        TakeSplit split = new TakeSplit(identity, taken, remaining(frame, graveAfter), recorded);
        try {
            spec.afterTake(grave, split);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            live.report(spec.key(), site, failure);
        }
    }

    /** Step 0b: the player the rows are indexed under, or null when the take is not the player's own act. */
    static ServerPlayer gate(Live live, GraveIdentity identity, Object level, ServerPlayer taker) {
        if (taker == null) return null;
        UUID takerId = live.id(taker);
        Marker marker = MARKERS.get().peek();
        if (marker != null) {
            if (marker.form() == Form.OWN && marker.actorId().equals(takerId)) return taker;
            if (marker.form() == Form.ON_BEHALF && marker.level() == level
                    && marker.position().equals(identity.position())) {
                return marker.actor();
            }
        }
        return live.actingAt(takerId, level, identity.position()) ? taker : null;
    }

    /** Counts per identity of {@code slots}; with {@code own} set, each stack's reference and count are kept. */
    static Map<InventoryItemIdentity, Long> counts(List<ItemStack> slots, IdentityHashMap<ItemStack, long[]> own) {
        Map<InventoryItemIdentity, Long> counts = new HashMap<>();
        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
            if (own != null) own.put(stack, new long[] {stack.getCount(), 0L});
        }
        return counts;
    }

    /** Counts per identity of a list a mod loaded during its call (r6 2.6), in the list's order. */
    static Map<InventoryItemIdentity, Long> loadedCounts(Collection<ItemStack> loaded) {
        Map<InventoryItemIdentity, Long> counts = new LinkedHashMap<>();
        for (ItemStack stack : loaded) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Math::addExact);
        }
        return counts;
    }

    /** The piles that stay, reconciled with the grave's contents after the take (excess by the pile rule). */
    static Map<InventoryItemIdentity, OwnerPortions.Pile> remaining(SpillTags.TakeFrame frame,
            Map<InventoryItemIdentity, Long> graveAfter) {
        Set<InventoryItemIdentity> items = new LinkedHashSet<>(frame.piles.keySet());
        items.addAll(graveAfter.keySet());
        Map<InventoryItemIdentity, OwnerPortions.Pile> remaining = new LinkedHashMap<>();
        for (InventoryItemIdentity item : items) {
            long count = Math.max(0L, graveAfter.getOrDefault(item, 0L));
            if (count == 0L) continue;
            OwnerPortions.Pile pile = frame.pile(item);
            long total = pile.total();
            if (total > count) {
                pile = pile.take(total - count).remaining();
            } else if (total < count) {
                pile = pile.plusUnknown(count - total);
            }
            remaining.put(item, pile);
        }
        return remaining;
    }

    /** Step 9: the take's rows and loss pairs in one batch; true only when storage took them. */
    static boolean writeRows(Live live, GraveAdapterSpec<?> spec, String site, ServerPlayer rowsPlayer,
            GraveIdentity identity, GraveOwnerFacts owner, Map<InventoryItemIdentity, List<OwnerPortion>> taken,
            Map<InventoryItemIdentity, Long> lost) {
        boolean anyLoss = lost.values().stream().anyMatch(units -> units > 0L);
        if (taken.isEmpty() && !anyLoss) return false;
        try {
            Admission admission = live.admit(rowsPlayer);
            if (admission.status() == Admission.Status.NOT_TRACKED) return false;
            if (admission.status() != Admission.Status.ADMITTED) {
                GraveCounts.count(GraveCounts.Reason.GRAVE_ROWS_NOT_RECORDED);
                return false;
            }
            List<AuditRecord> lossRecords = List.of();
            if (anyLoss) {
                Optional<TrashTargets.TrashTarget> trash = spec.lossTarget(rowsPlayer, site);
                TrashMoveWriter.Records built = trash.isEmpty() ? null : live.lossRecords(spec.key(), site,
                        rowsPlayer, spec.lossProviderId(), lossMoves(identity, trash.get(), lost),
                        ShownCause.viaGrave(identity.graveId(), identity.display(), identity.subject(),
                                owner.owner()));
                if (built == null || built.outcome() == TrashMoveWriter.Outcome.NOT_RECORDED) {
                    GraveCounts.count(GraveCounts.Reason.GRAVE_LOSS_UNRECORDED);
                    return false;
                }
                if (built.outcome() != TrashMoveWriter.Outcome.WRITTEN) return false;
                lossRecords = built.records();
            }
            List<AuditRecord> rows = rows(live.now(), admission.playerId(), admission.playerName(),
                    NEXT_ACTION.incrementAndGet(), spec, identity, taken, live.fullMetadata(), live);
            if (rows.isEmpty() && lossRecords.isEmpty()) return true;
            List<AuditRecord> all = new ArrayList<>(rows);
            all.addAll(lossRecords);
            boolean stored = ActionRecordBatch.collect(live::store, batch -> batch.addAll(all));
            if (!stored) GraveCounts.count(GraveCounts.Reason.GRAVE_ROWS_NOT_RECORDED);
            return stored;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            GraveCounts.count(GraveCounts.Reason.GRAVE_ROWS_NOT_RECORDED);
            live.report(spec.key(), site, failure);
            return false;
        }
    }

    /** One CONTAINER_WITHDRAWAL row per (identity, part), one action id, the policy's direction check (1.4). */
    static List<AuditRecord> rows(long when, UUID playerId, String playerName, long actionId,
            GraveAdapterSpec<?> spec, GraveIdentity identity, Map<InventoryItemIdentity, List<OwnerPortion>> taken,
            boolean fullMetadata, Live live) {
        List<AuditRecord> rows = new ArrayList<>();
        if (!live.recorded(TransactionDirection.WITHDRAWAL)) return rows;
        taken.forEach((item, parts) -> {
            for (OwnerPortion part : parts) {
                if (part.count() <= 0L) continue;
                InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(when, playerId, playerName,
                        false, actionId, 1L, TransactionDirection.WITHDRAWAL, item, part.count(), identity.target(),
                        part.pulled() ? spec.pulledProviderId() : spec.providerId(),
                        EvidenceKind.AUTHORITATIVE_MOD_OPERATION, InventorySlotRole.STORAGE);
                rows.addAll(InventoryTransactionNormalizer.toAuditRecords(evidence, fullMetadata,
                        ShownCause.viaGrave(identity.graveId(), identity.display(), identity.subject(),
                                part.owner())));
            }
        });
        return rows;
    }

    private static List<TrashMoveWriter.Move> lossMoves(GraveIdentity identity, TrashTargets.TrashTarget trash,
            Map<InventoryItemIdentity, Long> lost) {
        List<TrashMoveWriter.Move> moves = new ArrayList<>();
        lost.forEach((item, units) -> {
            if (units <= 0L) return;
            moves.add(TrashMoveWriter.Move.outOfContainer(identity.target(), item, units));
            moves.add(TrashMoveWriter.Move.intoTrash(trash, item, units));
        });
        return moves;
    }

    /** The server's live parts. */
    private static final class ServerLive implements Live {
        @Override
        public boolean inCommand() {
            return CommandPresence.inCommand();
        }

        @Override
        public boolean actingAt(UUID playerId, Object level, BlockPos position) {
            return GraveTakerEvidence.actingAt(playerId, level, position);
        }

        @Override
        public UUID id(ServerPlayer player) {
            return player.getUUID();
        }

        @Override
        public List<ItemStack> slots(ServerPlayer player) {
            Inventory inventory = player.getInventory();
            List<ItemStack> slots = new ArrayList<>(41);
            slots.addAll(inventory.items);
            slots.addAll(inventory.armor);
            slots.addAll(inventory.offhand);
            return slots;
        }

        @Override
        public Optional<Map<InventoryItemIdentity, Long>> curios(ServerPlayer player) {
            return CuriosItems.read(player, player.level().registryAccess());
        }

        @Override
        public Admission admit(ServerPlayer player) {
            try {
                if (player != null && EventTrailEventHandler.isFakePlayer(player)) return Admission.notTracked();
                if (player == null || player.server == null || !player.server.isSameThread()) {
                    return Admission.failed();
                }
                if (!EventTrailEventHandler.isAdmittedPlayer(player)) return Admission.notTracked();
                if (!EventTrailManager.getInstance().admitCapture()) return Admission.notTracked();
                return Admission.admitted(player.getUUID(), player.getGameProfile().getName());
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                return Admission.failed();
            }
        }

        @Override
        public boolean fullMetadata() {
            // The full-metadata hint (af-2's assignment form), not a gate: admit() above takes admitCapture().
            boolean captureFullMetadata = EventTrailManager.getInstance().canAcceptAuditWrite();
            return captureFullMetadata;
        }

        @Override
        public boolean recorded(TransactionDirection direction) {
            return ContainerTransactionRecordingPolicy.enabled(direction);
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public TrashMoveWriter.Records lossRecords(AdapterKey key, String site, ServerPlayer player,
                String providerId, List<TrashMoveWriter.Move> moves, ShownCause withdrawalCause) {
            return TrashMoveWriter.recordsFor(key, site, player, providerId, moves, withdrawalCause);
        }

        @Override
        public boolean store(List<AuditRecord> records) {
            return EventTrailManager.getInstance().tryLogAll(records);
        }

        @Override
        public void report(AdapterKey key, String site, Throwable failure) {
            AdapterFailSafe.report(key, site, failure);
        }
    }
}
