package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Loader-neutral evidence state for one synchronous, explicit player discard.
 * A platform boundary may present exactly one admitted item entity. Nothing is
 * committed until the enclosing input operation returns normally.
 */
public final class ItemDropAuditSession {
    private final UUID operationId;
    private final long timestamp;
    private final ActorProvenance actor;
    private final ResourceLocation inputDimension;
    private final ItemStack expectedStack;

    private PendingEvidence pending;
    private boolean candidateSeen;
    private boolean candidateAccepted;
    private boolean observationSeen;
    private boolean invalid;
    private boolean closed;
    private RejectionReason rejectionReason = RejectionReason.NONE;
    private Completion terminalCompletion;

    public ItemDropAuditSession(
            long timestamp,
            ActorProvenance actor,
            ResourceLocation inputDimension,
            ItemStack expectedStack) {
        this(UUID.randomUUID(), timestamp, actor, inputDimension, expectedStack);
    }

    ItemDropAuditSession(
            UUID operationId,
            long timestamp,
            ActorProvenance actor,
            ResourceLocation inputDimension,
            ItemStack expectedStack) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        this.timestamp = timestamp;
        this.actor = Objects.requireNonNull(actor, "actor");
        if (actor.playerId() == null || actor.causality() != ActorProvenance.Causality.DIRECT) {
            throw new IllegalArgumentException("item-drop sessions require a direct player actor");
        }
        this.inputDimension = Objects.requireNonNull(inputDimension, "inputDimension");
        if (expectedStack == null || expectedStack.isEmpty()) {
            throw new IllegalArgumentException("item-drop sessions require a non-empty expected stack");
        }
        this.expectedStack = expectedStack.copy();
    }

    public UUID operationId() {
        return operationId;
    }

    public ActorProvenance actor() {
        return actor;
    }

    public ResourceLocation inputDimension() {
        return inputDimension;
    }

    /**
     * Correlates the exact stack entering CommonHooks with the item/count and
     * component snapshot derived from the authoritative input operation.
     * Later ItemTossEvent mutation is intentionally evaluated from the final
     * admitted entity instead of changing this candidate decision.
     */
    public boolean acceptCandidate(
            ActorProvenance candidateActor,
            ItemStack candidateStack) {
        if (closed || invalid || candidateSeen) {
            reject(RejectionReason.NESTED_OR_MULTIPLE_CANDIDATE);
            return false;
        }
        candidateSeen = true;
        if (!actor.equals(candidateActor)) {
            reject(RejectionReason.ACTOR_MISMATCH);
            return false;
        }
        if (candidateStack == null
                || candidateStack.isEmpty()
                || candidateStack.getCount() != expectedStack.getCount()
                || !ItemStack.isSameItemSameComponents(candidateStack, expectedStack)) {
            reject(RejectionReason.CANDIDATE_MISMATCH);
            return false;
        }
        candidateAccepted = true;
        return true;
    }

    /**
     * Presents the post-hook entity observation. The platform adapter has
     * already decided whether the exact entity is registered in the world.
     */
    public boolean observe(
            ActorProvenance observedActor,
            UUID itemEntityId,
            ResourceLocation admittedDimension,
            BlockPos admittedPosition,
            ItemStack admittedStack,
            boolean admitted) {
        if (closed || invalid || observationSeen) {
            reject(RejectionReason.NESTED_OR_MULTIPLE_OBSERVATION);
            return false;
        }
        observationSeen = true;

        if (!candidateAccepted) {
            reject(RejectionReason.NO_ACCEPTED_CANDIDATE);
            return false;
        }

        if (!actor.equals(observedActor)) {
            reject(RejectionReason.ACTOR_MISMATCH);
            return false;
        }
        if (admittedDimension != null && !inputDimension.equals(admittedDimension)) {
            reject(RejectionReason.DIMENSION_MISMATCH);
            return false;
        }
        if (!admitted) {
            reject(RejectionReason.NOT_ADMITTED);
            return false;
        }
        if (itemEntityId == null
                || admittedDimension == null
                || admittedPosition == null
                || admittedStack == null
                || admittedStack.isEmpty()) {
            reject(RejectionReason.EVIDENCE_MISMATCH);
            return false;
        }

        pending = new PendingEvidence(
                itemEntityId,
                admittedDimension,
                admittedPosition.immutable(),
                admittedStack.copy());
        return true;
    }

    /** Rejects recursive ownership where one input cannot identify one drop. */
    public void rejectNestedOperation() {
        reject(RejectionReason.NESTED_OR_MULTIPLE_OBSERVATION);
    }

    /** Closes the operation and emits at most one immutable evidence object. */
    public Completion complete(
            boolean operationCompletedNormally,
            ActorProvenance completingActor,
            ResourceLocation completingDimension) {
        if (closed) {
            return terminalCompletion;
        }
        if (invalid) {
            return close(Optional.empty(), rejectionReason);
        }
        if (!operationCompletedNormally) {
            return close(Optional.empty(), RejectionReason.OPERATION_FAILED);
        }
        if (!actor.equals(completingActor)) {
            return close(Optional.empty(), RejectionReason.ACTOR_MISMATCH);
        }
        if (!inputDimension.equals(completingDimension)) {
            return close(Optional.empty(), RejectionReason.DIMENSION_MISMATCH);
        }
        if (!candidateAccepted) {
            return close(Optional.empty(), RejectionReason.NO_ACCEPTED_CANDIDATE);
        }
        if (!observationSeen || pending == null) {
            return close(Optional.empty(), RejectionReason.NO_ADMITTED_ENTITY);
        }

        CommittedItemDropEvidence evidence = new CommittedItemDropEvidence(
                timestamp,
                operationId,
                pending.itemEntityId(),
                actor,
                pending.dimensionId(),
                pending.position(),
                pending.stack());
        return close(Optional.of(evidence), RejectionReason.NONE);
    }

    public Completion abort() {
        if (closed) {
            return terminalCompletion;
        }
        return close(Optional.empty(), RejectionReason.OPERATION_FAILED);
    }

    private void reject(RejectionReason reason) {
        invalid = true;
        pending = null;
        if (rejectionReason == RejectionReason.NONE) {
            rejectionReason = reason;
        }
    }

    private Completion close(
            Optional<CommittedItemDropEvidence> evidence,
            RejectionReason reason) {
        closed = true;
        pending = null;
        terminalCompletion = new Completion(operationId, evidence, reason);
        return terminalCompletion;
    }

    private record PendingEvidence(
            UUID itemEntityId,
            ResourceLocation dimensionId,
            BlockPos position,
            ItemStack stack) {
        private PendingEvidence {
            itemEntityId = Objects.requireNonNull(itemEntityId, "itemEntityId");
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            stack = Objects.requireNonNull(stack, "stack").copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public record Completion(
            UUID operationId,
            Optional<CommittedItemDropEvidence> evidence,
            RejectionReason rejectionReason) {
        public Completion {
            operationId = Objects.requireNonNull(operationId, "operationId");
            evidence = Objects.requireNonNull(evidence, "evidence");
            rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
            if (evidence.isPresent() != (rejectionReason == RejectionReason.NONE)) {
                throw new IllegalArgumentException("successful completion must contain exactly one evidence object");
            }
        }
    }

    public enum RejectionReason {
        NONE,
        NO_ADMITTED_ENTITY,
        NOT_ADMITTED,
        ACTOR_MISMATCH,
        DIMENSION_MISMATCH,
        EVIDENCE_MISMATCH,
        CANDIDATE_MISMATCH,
        NO_ACCEPTED_CANDIDATE,
        NESTED_OR_MULTIPLE_CANDIDATE,
        NESTED_OR_MULTIPLE_OBSERVATION,
        OPERATION_FAILED
    }
}
