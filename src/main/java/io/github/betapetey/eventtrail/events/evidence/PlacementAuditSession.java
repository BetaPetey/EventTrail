package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.events.block.BlockAuditLimits;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.CanonicalBlockStateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Loader-neutral, operation-scoped evidence for one synchronous BlockItem use.
 * A platform adapter admits immutable snapshot candidates during its placement
 * event boundary, then supplies final-world observations only after the whole
 * placement call returns normally.
 */
public final class PlacementAuditSession {
    public static final int MAX_RETAINED_MEMBERS = BlockAuditLimits.MAX_PLACEMENT_MEMBERS;
    public static final int MAX_FINAL_BLOCK_ENTITY_METADATA_BYTES =
            BlockAuditLimits.MAX_PLACEMENT_METADATA_BYTES;

    private final UUID operationId;
    private final long timestamp;
    private final ActorProvenance actor;
    private final ResourceLocation dimensionId;
    private final PlacementItemProvenance itemProvenance;
    private final List<Candidate> candidates = new ArrayList<>();
    private final Set<BlockPos> seenPositions = new HashSet<>();

    private int presentedMembers;
    private int duplicateMembers;
    private int memberLimitOmissions;
    private boolean eventObserved;
    private boolean invalid;
    private boolean closed;
    private RejectionReason rejectionReason = RejectionReason.NONE;
    private Summary terminalSummary;

    public PlacementAuditSession(
            long timestamp,
            ActorProvenance actor,
            ResourceLocation dimensionId,
            PlacementItemProvenance itemProvenance) {
        this(UUID.randomUUID(), timestamp, actor, dimensionId, itemProvenance);
    }

    PlacementAuditSession(
            UUID operationId,
            long timestamp,
            ActorProvenance actor,
            ResourceLocation dimensionId,
            PlacementItemProvenance itemProvenance) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        this.timestamp = timestamp;
        this.actor = Objects.requireNonNull(actor, "actor");
        if (actor.playerId() == null || actor.causality() != ActorProvenance.Causality.DIRECT) {
            throw new IllegalArgumentException("placement sessions require a direct player actor");
        }
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.itemProvenance = Objects.requireNonNull(itemProvenance, "itemProvenance");
    }

    public UUID operationId() {
        return operationId;
    }

    public ActorProvenance actor() {
        return actor;
    }

    public ResourceLocation dimensionId() {
        return dimensionId;
    }

    public PlacementItemProvenance itemProvenance() {
        return itemProvenance;
    }

    /**
     * Admits snapshot candidates from one platform placement-event boundary.
     * The enclosing platform operation still owns the final cancellation and
     * rollback decision. A second event boundary is ambiguous and fails closed.
     */
    public boolean acceptEvent(
            EventKind eventKind,
            ActorProvenance eventActor,
            ResourceLocation eventDimension,
            boolean canceled,
            List<CandidateInput> snapshotInputs) {
        return acceptEvent(
                eventKind, eventActor, eventDimension, canceled, snapshotInputs, 0);
    }

    /**
     * Accepts the bounded platform conversion and the exact number of raw
     * members omitted before conversion. This keeps adapter work bounded while
     * preserving an explicit truncation fact.
     */
    public boolean acceptEvent(
            EventKind eventKind,
            ActorProvenance eventActor,
            ResourceLocation eventDimension,
            boolean canceled,
            List<CandidateInput> snapshotInputs,
            int platformOmittedMembers) {
        if (closed || invalid || eventObserved) {
            reject(RejectionReason.NESTED_OR_MULTIPLE_EVENT_BOUNDARY);
            return false;
        }
        eventObserved = true;

        if (canceled) {
            reject(RejectionReason.CANCELED);
            return false;
        }
        if (!actor.equals(eventActor)) {
            reject(RejectionReason.ACTOR_MISMATCH);
            return false;
        }
        if (!dimensionId.equals(eventDimension)) {
            reject(RejectionReason.DIMENSION_MISMATCH);
            return false;
        }
        if (eventKind == null || snapshotInputs == null || platformOmittedMembers < 0) {
            reject(RejectionReason.EVIDENCE_MISMATCH);
            return false;
        }

        long totalPresented = (long) snapshotInputs.size() + platformOmittedMembers;
        presentedMembers = (int) Math.min(Integer.MAX_VALUE, totalPresented);
        memberLimitOmissions = platformOmittedMembers;
        if (eventKind == EventKind.SINGLE && presentedMembers != 1
                || eventKind == EventKind.MULTI && presentedMembers < 2) {
            reject(RejectionReason.EVIDENCE_MISMATCH);
            return false;
        }
        for (int snapshotIndex = 0; snapshotIndex < snapshotInputs.size(); snapshotIndex++) {
            CandidateInput input = snapshotInputs.get(snapshotIndex);
            if (input == null
                    || !actor.equals(input.actor())
                    || !dimensionId.equals(input.dimensionId())) {
                reject(input != null && !actor.equals(input.actor())
                        ? RejectionReason.ACTOR_MISMATCH
                        : input != null && !dimensionId.equals(input.dimensionId())
                                ? RejectionReason.DIMENSION_MISMATCH
                                : RejectionReason.EVIDENCE_MISMATCH);
                return false;
            }

            BlockPos immutablePosition = input.position().immutable();
            if (!seenPositions.add(immutablePosition)) {
                duplicateMembers++;
                continue;
            }
            if (candidates.size() >= MAX_RETAINED_MEMBERS) {
                seenPositions.remove(immutablePosition);
                if (memberLimitOmissions < Integer.MAX_VALUE) {
                    memberLimitOmissions++;
                }
                continue;
            }

            candidates.add(new Candidate(
                    this,
                    operationId,
                    candidates.size(),
                    snapshotIndex == 0,
                    actor,
                    dimensionId,
                    immutablePosition,
                    input.preUseState(),
                    input.eventState()));
        }

        if (candidates.isEmpty()) {
            reject(RejectionReason.EVIDENCE_MISMATCH);
            return false;
        }
        return true;
    }

    /** Immutable handles that the adapter must resolve against the final world. */
    public List<Candidate> candidates() {
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    /** Marks recursive/nested ownership as unprovable without affecting gameplay. */
    public void rejectNestedOperation() {
        reject(RejectionReason.NESTED_OR_MULTIPLE_EVENT_BOUNDARY);
    }

    /**
     * Closes the exact operation. Structural observation mismatches reject the
     * whole operation; final air or a restored pre-use state rejects only that
     * member, allowing truthful partial survival of a multi-place operation.
     */
    public Completion complete(
            boolean operationSucceeded,
            ActorProvenance completingActor,
            ResourceLocation completingDimension,
            List<FinalObservation> observations) {
        if (closed) {
            return new Completion(List.of(), terminalSummary == null
                    ? summary(0, 0, 0, 0)
                    : terminalSummary);
        }
        if (invalid || !eventObserved) {
            return closeWithoutEvidence(rejectionReason == RejectionReason.NONE
                    ? RejectionReason.NO_ACCEPTED_EVENT
                    : rejectionReason);
        }
        if (!operationSucceeded) {
            return closeWithoutEvidence(RejectionReason.OPERATION_FAILED);
        }
        if (!actor.equals(completingActor)) {
            return closeWithoutEvidence(RejectionReason.ACTOR_MISMATCH);
        }
        if (!dimensionId.equals(completingDimension) || observations == null) {
            return closeWithoutEvidence(observations == null
                    ? RejectionReason.EVIDENCE_MISMATCH
                    : RejectionReason.DIMENSION_MISMATCH);
        }

        Map<Candidate, FinalObservation> byCandidate = new IdentityHashMap<>();
        for (FinalObservation observation : observations) {
            if (observation == null
                    || observation.candidate().owner != this
                    || !operationId.equals(observation.candidate().operationId())
                    || !actor.equals(observation.actor())
                    || !dimensionId.equals(observation.dimensionId())
                    || !observation.candidate().position().equals(observation.position())
                    || byCandidate.put(observation.candidate(), observation) != null) {
                return closeWithoutEvidence(rejectionFor(observation));
            }
        }

        List<PendingEvidence> pending = new ArrayList<>();
        int rejectedFinalMembers = 0;
        for (Candidate candidate : candidates) {
            FinalObservation observation = byCandidate.get(candidate);
            if (observation == null
                    || observation.finalState().isAir()
                    || observation.finalState().equals(candidate.preUseState())
                    || !placedBlock(candidate, observation.finalState())) {
                rejectedFinalMembers++;
                continue;
            }
            pending.add(new PendingEvidence(candidate, observation));
        }

        List<CommittedPlacementEvidence> evidence = new ArrayList<>(pending.size());
        int metadataBytesRetained = 0;
        int metadataOmissions = 0;
        for (PendingEvidence value : pending) {
            BlockEntityEvidence metadata = value.observation().blockEntityEvidence();
            if (metadata.status() == BlockEntityEvidence.Status.OMITTED_OPERATION_BUDGET) {
                metadataOmissions++;
            } else if (metadata.status() == BlockEntityEvidence.Status.CAPTURED) {
                int remaining = MAX_FINAL_BLOCK_ENTITY_METADATA_BYTES - metadataBytesRetained;
                int bytes = metadata.retainedUtf8Bytes();
                if (bytes > remaining) {
                    metadata = BlockEntityEvidence.omittedForOperationBudget();
                    metadataOmissions++;
                } else {
                    metadataBytesRetained += bytes;
                }
            }

            Candidate candidate = value.candidate();
            evidence.add(new CommittedPlacementEvidence(
                    timestamp,
                    operationId,
                    candidate.memberIndex(),
                    candidates.size(),
                    candidate.firstSnapshot(),
                    actor,
                    itemProvenance,
                    dimensionId,
                    candidate.position(),
                    candidate.preUseState(),
                    candidate.eventState(),
                    value.observation().finalState(),
                    CanonicalBlockStateSnapshot.capture(value.observation().finalState()),
                    metadata,
                    memberLimitOmissions > 0 && value == pending.getFirst()
                            ? BlockEffectTruncation.PLACEMENT_MEMBER_LIMIT
                            : BlockEffectTruncation.NONE));
        }

        closed = true;
        terminalSummary = summary(
                evidence.size(), rejectedFinalMembers, metadataBytesRetained, metadataOmissions);
        clearRetainedCandidates();
        return new Completion(evidence, terminalSummary);
    }

    /**
     * GF-BLOCKIDENTITY: a member is a placed block only when its Block changed. The one exception is the clicked
     * (first) snapshot adding the held block to itself, such as slab on slab, a second candle or sea pickle, or another
     * snow layer: same Block before and after, and that Block is the held BlockItem's block. A later snapshot whose
     * Block did not change (an existing block the placement rewrote) is never a placement, and a block on the Q38
     * list never is.
     */
    private boolean placedBlock(Candidate candidate, BlockState finalState) {
        if (BlockIdentity.excluded(finalState)) return false;
        if (BlockIdentity.blockDiffers(candidate.preUseState(), finalState)) return true;
        return candidate.firstSnapshot()
                && itemProvenance.heldBlock() != null
                && finalState.getBlock() == itemProvenance.heldBlock();
    }

    /** Expires this operation after an exception without manufacturing evidence. */
    public Summary abort() {
        if (!closed) {
            rejectionReason = RejectionReason.ABORTED;
            invalid = true;
            closed = true;
            terminalSummary = summary(0, candidates.size(), 0, 0);
            clearRetainedCandidates();
        }
        return terminalSummary == null ? summary(0, 0, 0, 0) : terminalSummary;
    }

    private Completion closeWithoutEvidence(RejectionReason reason) {
        rejectionReason = reason;
        invalid = true;
        closed = true;
        terminalSummary = summary(0, candidates.size(), 0, 0);
        clearRetainedCandidates();
        return new Completion(List.of(), terminalSummary);
    }

    private void reject(RejectionReason reason) {
        if (closed) return;
        invalid = true;
        rejectionReason = reason;
        clearRetainedCandidates();
    }

    private RejectionReason rejectionFor(FinalObservation observation) {
        if (observation == null || observation.candidate().owner != this) {
            return RejectionReason.EVIDENCE_MISMATCH;
        }
        if (!actor.equals(observation.actor())) {
            return RejectionReason.ACTOR_MISMATCH;
        }
        if (!dimensionId.equals(observation.dimensionId())) {
            return RejectionReason.DIMENSION_MISMATCH;
        }
        if (!observation.candidate().position().equals(observation.position())) {
            return RejectionReason.POSITION_MISMATCH;
        }
        return RejectionReason.EVIDENCE_MISMATCH;
    }

    private Summary summary(
            int committedMembers,
            int rejectedFinalMembers,
            int metadataBytesRetained,
            int metadataOmissions) {
        return new Summary(
                operationId,
                presentedMembers,
                candidates.size(),
                duplicateMembers,
                memberLimitOmissions,
                committedMembers,
                rejectedFinalMembers,
                metadataBytesRetained,
                metadataOmissions,
                rejectionReason);
    }

    private void clearRetainedCandidates() {
        candidates.clear();
        seenPositions.clear();
    }

    public enum EventKind {
        SINGLE,
        MULTI
    }

    public enum RejectionReason {
        NONE,
        CANCELED,
        OPERATION_FAILED,
        ABORTED,
        NO_ACCEPTED_EVENT,
        NESTED_OR_MULTIPLE_EVENT_BOUNDARY,
        ACTOR_MISMATCH,
        DIMENSION_MISMATCH,
        POSITION_MISMATCH,
        EVIDENCE_MISMATCH
    }

    public record CandidateInput(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos position,
            BlockState preUseState,
            BlockState eventState) {
        public CandidateInput {
            actor = Objects.requireNonNull(actor, "actor");
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            preUseState = Objects.requireNonNull(preUseState, "preUseState");
            eventState = Objects.requireNonNull(eventState, "eventState");
        }
    }

    public static final class Candidate {
        private final PlacementAuditSession owner;
        private final UUID operationId;
        private final int memberIndex;
        private final boolean firstSnapshot;
        private final ActorProvenance actor;
        private final ResourceLocation dimensionId;
        private final BlockPos position;
        private final BlockState preUseState;
        private final BlockState eventState;

        private Candidate(
                PlacementAuditSession owner,
                UUID operationId,
                int memberIndex,
                boolean firstSnapshot,
                ActorProvenance actor,
                ResourceLocation dimensionId,
                BlockPos position,
                BlockState preUseState,
                BlockState eventState) {
            this.owner = owner;
            this.operationId = operationId;
            this.memberIndex = memberIndex;
            this.firstSnapshot = firstSnapshot;
            this.actor = actor;
            this.dimensionId = dimensionId;
            this.position = position;
            this.preUseState = preUseState;
            this.eventState = eventState;
        }

        public UUID operationId() {
            return operationId;
        }

        public int memberIndex() {
            return memberIndex;
        }

        public boolean firstSnapshot() {
            return firstSnapshot;
        }

        public ActorProvenance actor() {
            return actor;
        }

        public ResourceLocation dimensionId() {
            return dimensionId;
        }

        public BlockPos position() {
            return position;
        }

        public BlockState preUseState() {
            return preUseState;
        }

        public BlockState eventState() {
            return eventState;
        }
    }

    public record FinalObservation(
            Candidate candidate,
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos position,
            BlockState finalState,
            BlockEntityEvidence blockEntityEvidence) {
        public FinalObservation {
            candidate = Objects.requireNonNull(candidate, "candidate");
            actor = Objects.requireNonNull(actor, "actor");
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            finalState = Objects.requireNonNull(finalState, "finalState");
            blockEntityEvidence = Objects.requireNonNull(
                    blockEntityEvidence, "blockEntityEvidence");
        }
    }

    public record Completion(
            List<CommittedPlacementEvidence> evidence,
            Summary summary) {
        public Completion {
            evidence = List.copyOf(evidence);
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    public record Summary(
            UUID operationId,
            int presentedMembers,
            int retainedMembers,
            int duplicateMembers,
            int memberLimitOmissions,
            int committedMembers,
            int rejectedFinalMembers,
            int metadataBytesRetained,
            int metadataOmissions,
            RejectionReason rejectionReason) {
        public Summary {
            operationId = Objects.requireNonNull(operationId, "operationId");
            rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
        }

        public boolean truncated() {
            return memberLimitOmissions > 0 || metadataOmissions > 0;
        }
    }

    private record PendingEvidence(Candidate candidate, FinalObservation observation) {
    }
}
