package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidenceCapturer;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.CommittedPlacementEvidence;
import io.github.betapetey.eventtrail.events.evidence.PlacementAuditSession;
import io.github.betapetey.eventtrail.events.evidence.PlacementEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.PlacementItemProvenance;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import net.minecraft.core.BlockPos;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * NeoForge 1.21.1 ingress for committed player block-placement evidence.
 *
 * <p>The active operation is server-thread-owned and exists only around one
 * synchronous {@code CommonHooks.onPlaceItemIntoWorld} call. Place events add
 * candidates; only the enclosing post-call boundary may normalize and persist
 * final-world evidence.</p>
 */
public final class NeoForgePlacementAuditBridge {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final ThreadLocal<ActiveOperation> ACTIVE = new ThreadLocal<>();
    private static final Boundary INACTIVE = new Boundary(null);
    private static final int MAX_PLATFORM_SNAPSHOT_INPUTS =
            PlacementAuditSession.MAX_RETAINED_MEMBERS + 1;
    private static final TruncationWarningLimiter TRUNCATION_WARNINGS =
            new TruncationWarningLimiter(60_000L);

    private NeoForgePlacementAuditBridge() {
    }

    /** GF-SCOPE: whether a placement boundary is open on this thread (its captured positions are its own). */
    static boolean capturing() {
        return ACTIVE.get() != null;
    }

    /** Opens one exact server-side BlockItem use without retaining mutable item data. */
    public static Boundary begin(UseOnContext context) {
        ActiveOperation existing = ACTIVE.get();
        if (existing != null) {
            existing.session.rejectNestedOperation();
            return INACTIVE;
        }

        try {
            if (context == null
                    || !(context.getLevel() instanceof ServerLevel level)
                    || !(context.getPlayer() instanceof ServerPlayer player)
                    || player.level() != level
                    || !level.getServer().isSameThread()
                    || !(context.getItemInHand().getItem() instanceof BlockItem)
                    || !EventTrailManager.getInstance().admitCapture()) {
                return INACTIVE;
            }

            Optional<ActorProvenance> actor = NeoForgePlayerProvenance.capture(
                    player,
                    ActorProvenance.Causality.DIRECT,
                    null);
            Optional<PlacementItemProvenance> item = PlacementItemProvenance.capture(
                    context.getItemInHand(),
                    EventTrailManager.getInstance().canAcceptAuditWrite());
            if (actor.isEmpty() || item.isEmpty()) {
                return INACTIVE;
            }

            PlacementAuditSession session = new PlacementAuditSession(
                    System.currentTimeMillis(),
                    actor.get(),
                    level.dimension().location(),
                    item.get());
            ActiveOperation operation = new ActiveOperation(player, level, session, new HashMap<>());
            // The boundary is built before ACTIVE is set, so nothing that can fail lies between setting ACTIVE and
            // handing out the only object that ends it. Otherwise a failure there would leave ACTIVE set with no
            // boundary, and every later placement on this thread would be rejected as nested.
            Boundary boundary = new Boundary(operation);
            ACTIVE.set(operation);
            return boundary;
        } catch (RuntimeException | LinkageError failure) {
            ACTIVE.remove();
            logFailure("begin", failure);
            return INACTIVE;
        }
    }

    /** Captures one tentative single-place snapshot without writing an audit row. */
    public static void observeSingle(BlockEvent.EntityPlaceEvent event) {
        ActiveOperation operation = ACTIVE.get();
        if (operation == null) return;

        try {
            if (event instanceof BlockEvent.EntityMultiPlaceEvent
                    || !matches(operation, event.getEntity(), event.getLevel())) {
                operation.session.abort();
                return;
            }
            accept(
                    operation,
                    PlacementAuditSession.EventKind.SINGLE,
                    List.of(event.getBlockSnapshot()),
                    0);
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("observe single-place candidate", failure);
        }
    }

    /** Captures tentative multi-place snapshots; the config may retain only the primary member. */
    public static void observeMulti(
            BlockEvent.EntityMultiPlaceEvent event,
            boolean trackMultiBlockPlace) {
        ActiveOperation operation = ACTIVE.get();
        if (operation == null) return;

        try {
            if (!matches(operation, event.getEntity(), event.getLevel())) {
                operation.session.abort();
                return;
            }
            List<BlockSnapshot> snapshots = event.getReplacedBlockSnapshots();
            SelectedSnapshots<BlockSnapshot> selected =
                    selectMultiSnapshots(snapshots, trackMultiBlockPlace);
            if (selected.snapshots().isEmpty()) {
                operation.session.abort();
                return;
            }
            accept(
                    operation,
                    trackMultiBlockPlace
                            ? PlacementAuditSession.EventKind.MULTI
                            : PlacementAuditSession.EventKind.SINGLE,
                    selected.snapshots(),
                    selected.omittedMembers());
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("observe multi-place candidates", failure);
        }
    }

    /**
     * Selects the evidence owned by the historical multi-place setting.
     * Disabled means the platform's primary snapshot only: a later member must
     * never replace it if the primary member does not survive finalization.
     */
    static <T> SelectedSnapshots<T> selectMultiSnapshots(
            List<T> snapshots, boolean trackMultiBlockPlace) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new SelectedSnapshots<>(List.of(), 0);
        }
        if (!trackMultiBlockPlace) {
            return new SelectedSnapshots<>(List.of(snapshots.getFirst()), 0);
        }

        int convertedMembers = Math.min(snapshots.size(), MAX_PLATFORM_SNAPSHOT_INPUTS);
        return new SelectedSnapshots<>(
                List.copyOf(snapshots.subList(0, convertedMembers)),
                snapshots.size() - convertedMembers);
    }

    /**
     * Closes a normally-returned platform operation and reads final state only
     * after NeoForge has either restored snapshots or completed notifications.
     */
    public static void finish(Boundary boundary, InteractionResult result) {
        ActiveOperation operation = boundary == null ? null : boundary.operation;
        if (operation == null) return;

        try {
            if (ACTIVE.get() != operation) {
                operation.session.abort();
                return;
            }

            boolean succeeded = result != null
                    && result.consumesAction()
                    && operation.player.level() == operation.level
                    && operation.level.getServer().isSameThread()
                    && EventTrailManager.getInstance().admitCapture();
            List<PlacementAuditSession.FinalObservation> observations = succeeded
                    ? captureFinalObservations(operation)
                    : List.of();
            PlacementAuditSession.Completion completion = operation.session.complete(
                    succeeded,
                    operation.session.actor(),
                    operation.level.dimension().location(),
                    observations);

            ACTIVE.remove();
            persist(completion, operation.frames);
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("finalize committed placement", failure);
        } finally {
            operation.frames.clear();
            if (ACTIVE.get() == operation) {
                ACTIVE.remove();
            }
        }
    }

    /** Expires a platform operation whose wrapped call did not return normally. */
    public static void abort(Boundary boundary) {
        ActiveOperation operation = boundary == null ? null : boundary.operation;
        if (operation == null) return;
        try {
            operation.session.abort();
        } catch (RuntimeException | LinkageError failure) {
            logFailure("abort placement", failure);
        } finally {
            operation.frames.clear();
            if (ACTIVE.get() == operation) {
                ACTIVE.remove();
            }
        }
    }

    private static void accept(
            ActiveOperation operation,
            PlacementAuditSession.EventKind eventKind,
            List<BlockSnapshot> snapshots,
            int platformOmittedMembers) {
        List<PlacementAuditSession.CandidateInput> inputs = new ArrayList<>(snapshots.size());
        operation.frames.clear();
        ResourceLocation dimensionId = operation.level.dimension().location();
        for (BlockSnapshot snapshot : snapshots) {
            if (snapshot == null
                    || snapshot.getLevel() != operation.level
                    || !operation.level.dimension().equals(snapshot.getDimension())) {
                operation.session.abort();
                return;
            }
            inputs.add(new PlacementAuditSession.CandidateInput(
                    operation.session.actor(),
                    dimensionId,
                    snapshot.getPos(),
                    snapshot.getState(),
                    operation.level.getBlockState(snapshot.getPos())));
            AuditBlockLocations.capture(operation.level, snapshot.getPos())
                    .ifPresent(frame -> operation.frames.put(snapshot.getPos().immutable(), frame));
        }
        // A LOWEST listener can still be followed by another listener at the
        // same priority. Its cancellation flag is therefore only tentative.
        // The wrapped CommonHooks return and restored/final world state are the
        // authoritative commit decision at finish().
        operation.session.acceptEvent(
                eventKind,
                operation.session.actor(),
                dimensionId,
                false,
                inputs,
                platformOmittedMembers);
    }

    private static List<PlacementAuditSession.FinalObservation> captureFinalObservations(
            ActiveOperation operation) {
        List<PlacementAuditSession.Candidate> candidates = operation.session.candidates();
        List<PlacementAuditSession.FinalObservation> observations =
                new ArrayList<>(candidates.size());
        ResourceLocation dimensionId = operation.level.dimension().location();
        boolean captureFullMetadata = EventTrailManager.getInstance().canAcceptAuditWrite();
        int remainingMetadataBytes =
                PlacementAuditSession.MAX_FINAL_BLOCK_ENTITY_METADATA_BYTES;

        for (PlacementAuditSession.Candidate candidate : candidates) {
            AuditBlockLocations.Frame frame = operation.frames.get(candidate.position());
            if (frame == null || !frame.isCurrent()) continue;
            BlockEntityEvidence metadata;
            if (!captureFullMetadata) {
                metadata = BlockEntityEvidenceCapturer.capture(
                        operation.level, candidate.position(), false);
            } else if (remainingMetadataBytes == 0) {
                BlockEntityEvidence presence = BlockEntityEvidenceCapturer.capture(
                        operation.level, candidate.position(), false);
                metadata = presence.status() == BlockEntityEvidence.Status.OMITTED_WRITER_PRESSURE
                        ? BlockEntityEvidence.omittedForOperationBudget()
                        : presence;
            } else {
                metadata = BlockEntityEvidenceCapturer.capture(
                        operation.level, candidate.position(), true);
                if (metadata.status() == BlockEntityEvidence.Status.CAPTURED) {
                    int metadataBytes = metadata.retainedUtf8Bytes();
                    if (metadataBytes > remainingMetadataBytes) {
                        metadata = BlockEntityEvidence.omittedForOperationBudget();
                        remainingMetadataBytes = 0;
                    } else {
                        remainingMetadataBytes -= metadataBytes;
                    }
                }
            }
            observations.add(new PlacementAuditSession.FinalObservation(
                    candidate,
                    operation.session.actor(),
                    dimensionId,
                    candidate.position(),
                    operation.level.getBlockState(candidate.position()),
                    metadata));
        }
        return observations;
    }

    private static void persist(PlacementAuditSession.Completion completion, Map<BlockPos, AuditBlockLocations.Frame> frames) {
        List<CommittedPlacementEvidence> evidence = completion.evidence();
        PlacementAuditSession.Summary summary = completion.summary();

        publishAction(
                evidence,
                frames,
                (value, location) -> PlacementEvidenceNormalizer.toAuditRecord(value, location),
                EventTrailManager.getInstance()::tryLogAll);

        if (summary.truncated()) {
            TruncationWarningDecision warning =
                    TRUNCATION_WARNINGS.record(System.currentTimeMillis());
            if (warning.log()) {
                LOGGER.warn(
                        "EventTrail placement evidence was truncated for operation {} in {}: "
                                + "{} member omission(s), {} metadata omission(s); "
                                + "{} similar warning(s) suppressed",
                        summary.operationId(),
                        evidence.isEmpty() ? "unknown" : evidence.getFirst().dimensionId(),
                        summary.memberLimitOmissions(),
                        summary.metadataOmissions(),
                        warning.suppressed());
            }
        }
    }

    /**
     * Row C (writer-01 2.6): builds every row of one placement action before publishing any, then publishes them
     * once, as one group, so the writer stores the whole action or none of it. A member whose frame is missing or
     * no longer current is a policy omission and is skipped, as before. A build failure throws before anything is
     * published, so the action is dropped whole instead of being stored in part. Public only for the writer's group
     * tests.
     *
     * @return whether a group was published and accepted
     */
    public static boolean publishAction(
            List<CommittedPlacementEvidence> evidence,
            Map<BlockPos, AuditBlockLocations.Frame> frames,
            BiFunction<CommittedPlacementEvidence, AuditLocation, AuditRecord> builder,
            Predicate<List<AuditRecord>> publisher) {
        List<AuditRecord> entries = new ArrayList<>(evidence.size());
        for (CommittedPlacementEvidence value : evidence) {
            AuditBlockLocations.Frame frame = frames.get(value.position());
            if (frame != null) frame.currentLocation()
                    .map(location -> builder.apply(value, location))
                    .ifPresent(entries::add);
        }
        return !entries.isEmpty() && publisher.test(entries);
    }

    private static boolean matches(
            ActiveOperation operation,
            Entity eventEntity,
            Object eventLevel) {
        return eventEntity == operation.player && eventLevel == operation.level;
    }

    private static void logFailure(String phase, Throwable failure) {
        LOGGER.error(
                "Could not {} ({})",
                phase,
                EventTrailDiagnostics.safeFailureSummary(failure));
    }

    /** Opaque ownership token for one synchronous CommonHooks invocation. */
    public static final class Boundary {
        private final ActiveOperation operation;

        private Boundary(ActiveOperation operation) {
            this.operation = operation;
        }
    }

    private record ActiveOperation(
            ServerPlayer player,
            ServerLevel level,
            PlacementAuditSession session,
            Map<BlockPos, AuditBlockLocations.Frame> frames) {
    }

    /** Small process-local limiter; row-level truncation markers are never suppressed. */
    static final class TruncationWarningLimiter {
        private final long intervalMillis;
        private long nextWarningAt;
        private long suppressed;

        TruncationWarningLimiter(long intervalMillis) {
            if (intervalMillis <= 0L) {
                throw new IllegalArgumentException("intervalMillis must be positive");
            }
            this.intervalMillis = intervalMillis;
        }

        synchronized TruncationWarningDecision record(long now) {
            if (now < nextWarningAt) {
                if (suppressed < Long.MAX_VALUE) suppressed++;
                return new TruncationWarningDecision(false, 0L);
            }

            long previouslySuppressed = suppressed;
            suppressed = 0L;
            nextWarningAt = now > Long.MAX_VALUE - intervalMillis
                    ? Long.MAX_VALUE
                    : now + intervalMillis;
            return new TruncationWarningDecision(true, previouslySuppressed);
        }
    }

    record TruncationWarningDecision(boolean log, long suppressed) {
    }

    record SelectedSnapshots<T>(List<T> snapshots, int omittedMembers) {
    }
}
