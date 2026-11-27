package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.util.DetailsBlob;
import io.github.betapetey.eventtrail.util.ItemDetails;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Maps bounded immutable transfer evidence into grouped audit facts. */
public final class InventoryTransactionNormalizer {
    private InventoryTransactionNormalizer() {}

    public static AuditAction actionFor(TransactionDirection direction) {
        return switch (java.util.Objects.requireNonNull(direction, "direction")) {
            case DEPOSIT -> AuditAction.CONTAINER_DEPOSIT;
            case WITHDRAWAL -> AuditAction.CONTAINER_WITHDRAWAL;
        };
    }

    public static List<InventoryTransactionEvidence> chunk(InventoryTransactionEvidence evidence) {
        List<InventoryTransactionEvidence> chunks = new ArrayList<>();
        long remaining = evidence.quantity();
        while (remaining > 0L) {
            long quantity = Math.min(remaining, Integer.MAX_VALUE);
            chunks.add(evidence.withQuantity(quantity));
            remaining -= quantity;
        }
        return List.copyOf(chunks);
    }

    public static List<AuditRecord> toAuditRecords(
            InventoryTransactionEvidence evidence,
            boolean captureFullMetadata) {
        return toAuditRecords(evidence, captureFullMetadata, null);
    }

    /**
     * As {@link #toAuditRecords(InventoryTransactionEvidence, boolean)}, with one shown cause on every chunk's payload
     * (graves design r6, 1.4). A null cause gives the same records as the two-argument form.
     *
     * <p>ItemDetails plan 1.4 (B1): each chunk's item is captured by {@link ItemDetails#capture} and written as a
     * version 3 payload, with or without the cause, with evidence version
     * {@link ContainerTransferPayloadCodec#DETAILS_VERSION}. {@code captureFullMetadata} false is writer pressure
     * (BUSY), as before.</p>
     */
    public static List<AuditRecord> toAuditRecords(
            InventoryTransactionEvidence evidence,
            boolean captureFullMetadata,
            ShownCause cause) {
        if (!evidence.target().hasAccessPosition()) {
            throw new IllegalArgumentException("Current EventTrail rows require an access position");
        }

        List<AuditRecord> records = new ArrayList<>();
        for (InventoryTransactionEvidence chunk : chunk(evidence)) {
            ItemStack stack = chunk.item().stackWithCount((int) chunk.quantity());
            ItemDetails details = ItemDetails.capture(stack, captureFullMetadata, DetailsBlob.Limits.ITEM);
            ContainerTransferPayload payload = ContainerTransferPayload.fromDetails(
                    chunk,
                    chunk.syntheticActor(),
                    chunk.providerId(),
                    details,
                    cause);
            records.add(AuditRecord.captureAt(
                    chunk.occurredAtEpochMillis(),
                    actionFor(chunk.direction()),
                    AuditAttribution.actor(
                            chunk.playerId(),
                            chunk.playerName(),
                            chunk.syntheticActor(),
                            AuditAttribution.Causality.DIRECT,
                            null),
                    AuditLocation.of(
                            chunk.target().dimensionId(),
                            chunk.target().x(),
                            chunk.target().y(),
                            chunk.target().z()),
                    new AuditSubject(
                            AuditSubject.Kind.CONTAINER,
                            payload.backingReference(),
                            chunk.target().label()),
                    new AuditEvidence.ContainerTransfer(
                            ContainerTransferPayloadCodec.evidenceVersionOf(payload),
                            chunk.direction() == TransactionDirection.DEPOSIT
                                    ? AuditEvidence.TransferDirection.DEPOSIT
                                    : AuditEvidence.TransferDirection.WITHDRAWAL,
                            payload.itemId(),
                            payload.quantity(),
                            ContainerTransferPayloadCodec.encode(payload))));
        }
        return List.copyOf(records);
    }
}
