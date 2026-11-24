package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.access.TrashTargets;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * GF-TRASHTARGET (Question 15): the small writer for trash moves that have no open session (a trash slot in the
 * player's own screen, a delete hotkey on a container slot, an untrash), on the FunctionalStorageDirectActions pattern:
 * every row of one player action is built into one {@link ActionRecordBatch} and handed to storage once, all or none.
 *
 * <ul>
 *   <li>A move is a deposit into or a withdrawal out of a {@link BackingContextKind#TRASH} target, or the withdrawal
 *   out of a container that the same action put into the trash. When an action has container withdrawals, they must
 *   equal its trash deposits item by item: a half move is refused whole.</li>
 *   <li>Quantities come from the caller, from the mod's own path (the slot's inserter or extractor, or the mod's own
 *   event stack after the trash happened). This class offers no diff of the trash contents: a later loss inside a trash
 *   (an oldest stack pushed out, a stack overwritten, a screen closing) is not a move and writes nothing.</li>
 *   <li>Rows are written for a real player on the server thread. A synthetic player, or one that is not the server's
 *   listed player for its UUID, is not tracked, as everywhere; the second is counted in the unlisted-player status
 *   line, and a capture the runtime does not admit is counted by the shared gate. Any other failure, or a storage
 *   refusal, writes
 *   none of the action's rows and is counted ({@link #notRecordedCount()}); a failure is also reported to the
 *   adapter's fail-safe and warned (rate-limited). The mod's trash is never affected.</li>
 *   <li>Each direction follows the configured container recording policy, as every container row does.</li>
 *   <li>{@link #recordsFor} builds the same rows without writing them, for a caller whose own action's batch takes
 *   them (graves design r6: a Tombstone take and its loss pairs).</li>
 * </ul>
 */
public final class TrashMoveWriter {
    private static final AtomicLong NEXT_ACTION = new AtomicLong();
    private static final LongAdder NOT_RECORDED = new LongAdder();
    private static final RateLimitedDiagnosticGate NOT_RECORDED_WARNING = new RateLimitedDiagnosticGate(60_000);

    /** What became of one action. */
    public enum Outcome {
        /** Storage took every row of the action (or the recording policy kept none of them). */
        WRITTEN,
        /**
         * Not tracked: a synthetic player, an unlisted player (counted in the unlisted-player status line), or a
         * capture the runtime did not admit (counted by the shared gate).
         */
        NOT_TRACKED,
        /** None of the action's rows was written; counted. */
        NOT_RECORDED
    }

    /** One move of one item, with the quantity the mod's own path moved. */
    public record Move(TransactionDirection direction, ContainerTarget target, InventoryItemIdentity item,
            long quantity) {
        public Move {
            direction = Objects.requireNonNull(direction, "direction");
            target = Objects.requireNonNull(target, "target");
            item = Objects.requireNonNull(item, "item");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
            if (target.backingContextKind() != BackingContextKind.TRASH
                    && direction != TransactionDirection.WITHDRAWAL) {
                throw new IllegalArgumentException("The container part of a trash move is a withdrawal");
            }
        }

        /** Items a player put into a trash. */
        public static Move intoTrash(TrashTargets.TrashTarget trash, InventoryItemIdentity item, long quantity) {
            return new Move(TransactionDirection.DEPOSIT, Objects.requireNonNull(trash, "trash").target(), item,
                    quantity);
        }

        /** Items a player took back out of a trash. */
        public static Move outOfTrash(TrashTargets.TrashTarget trash, InventoryItemIdentity item, long quantity) {
            return new Move(TransactionDirection.WITHDRAWAL, Objects.requireNonNull(trash, "trash").target(), item,
                    quantity);
        }

        /** Items the same action took out of a container slot straight into the trash. */
        public static Move outOfContainer(ContainerTarget container, InventoryItemIdentity item, long quantity) {
            if (Objects.requireNonNull(container, "container").backingContextKind() == BackingContextKind.TRASH) {
                throw new IllegalArgumentException("A move out of a trash is outOfTrash");
            }
            return new Move(TransactionDirection.WITHDRAWAL, container, item, quantity);
        }

        boolean trash() {
            return target.backingContextKind() == BackingContextKind.TRASH;
        }
    }

    private TrashMoveWriter() {
    }

    /**
     * Writes every move of one action by {@code player} as one batch: all or none. {@code key} and {@code site} name
     * the calling adapter for a failure report; {@code providerId} is its canonical provider id.
     */
    public static Outcome write(AdapterKey key, String site, ServerPlayer player, String providerId,
            List<Move> moves) {
        Consumer<Throwable> report = failure -> AdapterFailSafe.report(key, site, failure);
        return admitted(player, EventTrailEventHandler::isFakePlayer, TrashMoveWriter::onServerThread,
                EventTrailEventHandler::isAdmittedPlayer, () -> EventTrailManager.getInstance().admitCapture(),
                admittedPlayer -> {
                    EventTrailManager manager = EventTrailManager.getInstance();
                    return write(System.currentTimeMillis(), admittedPlayer.getUUID(),
                            admittedPlayer.getGameProfile().getName(), providerId, moves,
                            manager.canAcceptAuditWrite(), manager::tryLogAll,
                            ContainerTransactionRecordingPolicy::enabled, report);
                }, report);
    }

    /**
     * The public {@code write}'s whole admission decision, in order (test seam):
     * <ol>
     *   <li>a synthetic player is not tracked and not counted, as everywhere;</li>
     *   <li>no player, or a player with no server or off its server thread, is a failure: counted and reported;</li>
     *   <li>a player that is not the server's own listed player for its UUID (a mod's ServerPlayer subclass that is not
     *   a FakePlayer but carries a real player's UUID) is not tracked: it is not a player's action, so it is not a lost
     *   player row. The public {@code write} uses {@link EventTrailEventHandler#isAdmittedPlayer} here, as every other
     *   admission point does, so the refusal is counted in the unlisted-player status line
     *   ({@code NeoForgePlayerProvenance.unlistedPlayerRefusals()}), never silent. There is no separate live
     *   ({@code isRemoved}) test: a removed object that the list still holds (the logout or respawn tick) is the server's
     *   own object for its UUID, so its move is that player's own;</li>
     *   <li>a capture the runtime does not admit is not tracked (counted by the shared gate);</li>
     *   <li>else {@code admittedWrite} writes the action.</li>
     * </ol>
     * Any throw from a check or from {@code admittedWrite} writes nothing and is counted and reported.
     */
    static <P> Outcome admitted(P player, Predicate<? super P> synthetic, Predicate<? super P> onServerThread,
            Predicate<? super P> admission, BooleanSupplier admitCapture, Function<? super P, Outcome> admittedWrite,
            Consumer<Throwable> report) {
        try {
            if (player != null && synthetic.test(player)) return Outcome.NOT_TRACKED;
            if (player == null || !onServerThread.test(player)) {
                throw new IllegalStateException("A trash move is written for a server player on the server thread");
            }
            if (!admission.test(player)) return Outcome.NOT_TRACKED;
            if (!admitCapture.getAsBoolean()) return Outcome.NOT_TRACKED;
            return Objects.requireNonNull(admittedWrite.apply(player), "outcome");
        } catch (Throwable failure) {
            return notRecorded(failure, report);
        }
    }

    private static boolean onServerThread(ServerPlayer player) {
        return player.server != null && player.server.isSameThread();
    }

    /** As the public {@code write}, after the player and runtime checks (test seam). */
    static Outcome write(long when, UUID playerId, String playerName, String providerId, List<Move> moves,
            boolean fullMetadata, Predicate<List<AuditRecord>> sink, Predicate<TransactionDirection> recorded,
            Consumer<Throwable> report) {
        try {
            List<Move> checked = checked(moves);
            long actionId = NEXT_ACTION.incrementAndGet();
            boolean stored = ActionRecordBatch.collect(sink, batch -> batch.addAll(records(when, playerId,
                    playerName, actionId, providerId, checked, fullMetadata, recorded, null)));
            // A storage refusal is counted here; storage warns for its own refusals.
            return stored ? Outcome.WRITTEN : notRecorded(null, report);
        } catch (Throwable failure) {
            return notRecorded(failure, report);
        }
    }

    /**
     * The rows of one action, built and not written (graves design r6, 2.2 hook 5 and F22): {@code records} holds
     * them only when {@code outcome} is {@link Outcome#WRITTEN}, which here means every row was built; it is empty
     * otherwise, and this class hands nothing to storage in any case.
     */
    public record Records(Outcome outcome, List<AuditRecord> records) {
        public Records {
            outcome = Objects.requireNonNull(outcome, "outcome");
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            if (outcome != Outcome.WRITTEN && !records.isEmpty()) {
                throw new IllegalArgumentException("Only a built action has rows");
            }
        }
    }

    /**
     * The rows {@link #write} would write for the same action by {@code player}, with {@code withdrawalCause} on each
     * container withdrawal's payload (the trash rows carry no cause), for a caller that puts them in its own action's
     * one batch: the grave take and its Tombstone loss pairs (graves design r6, 2.2 hook 5, F22). The admission is
     * {@code write}'s own, in the same order. {@link Outcome#NOT_RECORDED} (counted and reported here, as by
     * {@code write}) means the caller must write none of its action's rows. A null cause gives exactly
     * {@code write}'s rows.
     */
    public static Records recordsFor(AdapterKey key, String site, ServerPlayer player, String providerId,
            List<Move> moves, ShownCause withdrawalCause) {
        Consumer<Throwable> report = failure -> AdapterFailSafe.report(key, site, failure);
        List<AuditRecord> built = new ArrayList<>();
        Outcome outcome = admitted(player, EventTrailEventHandler::isFakePlayer, TrashMoveWriter::onServerThread,
                EventTrailEventHandler::isAdmittedPlayer, () -> EventTrailManager.getInstance().admitCapture(),
                admittedPlayer -> {
                    Records records = recordsFor(System.currentTimeMillis(), admittedPlayer.getUUID(),
                            admittedPlayer.getGameProfile().getName(), providerId, moves,
                            EventTrailManager.getInstance().canAcceptAuditWrite(),
                            ContainerTransactionRecordingPolicy::enabled, withdrawalCause, report);
                    built.addAll(records.records());
                    return records.outcome();
                }, report);
        return new Records(outcome, outcome == Outcome.WRITTEN ? built : List.of());
    }

    /** As the public {@code recordsFor}, after the player and runtime checks (test seam). */
    static Records recordsFor(long when, UUID playerId, String playerName, String providerId, List<Move> moves,
            boolean fullMetadata, Predicate<TransactionDirection> recorded, ShownCause withdrawalCause,
            Consumer<Throwable> report) {
        try {
            List<Move> checked = checked(moves);
            long actionId = NEXT_ACTION.incrementAndGet();
            return new Records(Outcome.WRITTEN, records(when, playerId, playerName, actionId, providerId, checked,
                    fullMetadata, recorded, withdrawalCause));
        } catch (Throwable failure) {
            return new Records(notRecorded(failure, report), List.of());
        }
    }

    /** Every row of checked moves that the policy keeps, in order; the cause goes on container withdrawals only. */
    private static List<AuditRecord> records(long when, UUID playerId, String playerName, long actionId,
            String providerId, List<Move> checked, boolean fullMetadata, Predicate<TransactionDirection> recorded,
            ShownCause withdrawalCause) {
        List<AuditRecord> records = new ArrayList<>();
        for (Move move : checked) {
            if (!recorded.test(move.direction())) continue;
            InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(when, playerId,
                    playerName, false, actionId, 1L, move.direction(), move.item(), move.quantity(),
                    move.target(), providerId, EvidenceKind.AUTHORITATIVE_MOD_OPERATION,
                    InventorySlotRole.STORAGE);
            records.addAll(InventoryTransactionNormalizer.toAuditRecords(evidence, fullMetadata,
                    move.trash() ? null : withdrawalCause));
        }
        return records;
    }

    /** Actions of which no row was written because of a failure or a storage refusal, since this process started. */
    public static long notRecordedCount() {
        return NOT_RECORDED.sum();
    }

    /**
     * The moves of one action, checked: at least one, none null, at least one into or out of a trash, and, when the
     * action takes items out of containers, those withdrawals equal its trash deposits item by item.
     */
    private static List<Move> checked(List<Move> moves) {
        List<Move> checked = List.copyOf(Objects.requireNonNull(moves, "moves"));
        if (checked.isEmpty()) throw new IllegalArgumentException("A trash action has at least one move");
        Map<InventoryItemIdentity, Long> containerOut = new HashMap<>();
        Map<InventoryItemIdentity, Long> trashIn = new HashMap<>();
        boolean anyTrash = false;
        for (Move move : checked) {
            if (move.trash()) {
                anyTrash = true;
                if (move.direction() == TransactionDirection.DEPOSIT) trashIn.merge(move.item(), move.quantity(), Math::addExact);
            } else {
                containerOut.merge(move.item(), move.quantity(), Math::addExact);
            }
        }
        if (!anyTrash) throw new IllegalArgumentException("A trash action moves items into or out of a trash");
        if (!containerOut.isEmpty() && !containerOut.equals(trashIn)) {
            throw new IllegalArgumentException("Container withdrawals of a trash action must equal its trash deposits");
        }
        return checked;
    }

    private static Outcome notRecorded(Throwable failure, Consumer<Throwable> report) {
        NOT_RECORDED.increment();
        if (failure == null) return Outcome.NOT_RECORDED;
        try {
            report.accept(failure);
        } catch (Throwable ignored) {
            // The report must never affect the mod's trash.
        }
        try {
            NOT_RECORDED_WARNING.acquire().ifPresent(emission -> LogManager.getLogger("eventtrail").warn(
                    "Could not record a trash move ({}); none of its rows was written; "
                            + "suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // A warning must never affect the mod's trash.
        }
        return Outcome.NOT_RECORDED;
    }
}
