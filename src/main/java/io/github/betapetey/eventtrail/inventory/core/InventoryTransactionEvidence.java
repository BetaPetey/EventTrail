package io.github.betapetey.eventtrail.inventory.core;

import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one item identity crossing one player/device boundary. */
public record InventoryTransactionEvidence(
        long occurredAtEpochMillis,
        UUID playerId,
        String playerName,
        boolean syntheticActor,
        long sessionId,
        long actionSequence,
        TransactionDirection direction,
        InventoryItemIdentity item,
        long quantity,
        ContainerTarget target,
        String providerId,
        EvidenceKind evidenceKind,
        InventorySlotRole slotRole) {

    public InventoryTransactionEvidence {
        if (occurredAtEpochMillis < 0L) {
            throw new IllegalArgumentException("occurredAtEpochMillis must not be negative");
        }
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (sessionId <= 0L) throw new IllegalArgumentException("sessionId must be positive");
        if (actionSequence <= 0L) throw new IllegalArgumentException("actionSequence must be positive");
        direction = Objects.requireNonNull(direction, "direction");
        item = Objects.requireNonNull(item, "item");
        if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
        target = Objects.requireNonNull(target, "target");
        if (providerId == null || providerId.isBlank()
                || providerId.length() > ContainerTransferPayload.MAX_PROVIDER_CHARS
                || !providerId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("providerId must be a bounded canonical adapter identity");
        }
        evidenceKind = Objects.requireNonNull(evidenceKind, "evidenceKind");
        slotRole = Objects.requireNonNull(slotRole, "slotRole");
        if (!slotRole.isRealDeviceCustody()) {
            throw new IllegalArgumentException("Non-custody slot roles cannot produce transaction evidence");
        }
    }

    public InventoryTransactionEvidence withQuantity(long newQuantity) {
        return new InventoryTransactionEvidence(
                occurredAtEpochMillis,
                playerId,
                playerName,
                syntheticActor,
                sessionId,
                actionSequence,
                direction,
                item,
                newQuantity,
                target,
                providerId,
                evidenceKind,
                slotRole);
    }
}
