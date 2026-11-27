package io.github.betapetey.eventtrail.storage;

import java.util.List;
import java.util.function.Consumer;

/**
 * Owner Q370 B (the B2 hand-off latency fix): the EventTrail classes the hand-off call (tryLog / tryLogAll: encoding
 * and the spool's staging) loads on first use, loaded and initialised once when a storage starts, so the first
 * calls on the game thread do not load them. The list is the EventTrail classes the round-2 JFR recording showed
 * the burst's producer threads loading in the cold MySQL leg (5 to 14 ms each; DiskSpool$Staged and SpoolFrameCodec
 * were loaded while writeLifecycleLock was held), plus the six classes the transfer version 3 decode (ItemDetails B1)
 * loads first (CompactIO's reader, DetailsBlob, ItemDetails' identity). Only loading and static initialisation: no
 * record is built, nothing is written, and none of these classes' static initialisers touches Minecraft state. A
 * class that cannot be loaded is logged at debug level and skipped; it never stops the storage from starting.
 */
final class HandOffClassPreload {
    private HandOffClassPreload() {}

    static final List<String> CLASSES = List.of(
            "io.github.betapetey.eventtrail.audit.AuditAttribution$CauseKind",
            "io.github.betapetey.eventtrail.audit.AuditAttribution$IdentityKind",
            "io.github.betapetey.eventtrail.audit.AuditAttribution$IndexedParty",
            "io.github.betapetey.eventtrail.audit.AuditAttribution$IndexedRole",
            "io.github.betapetey.eventtrail.audit.AuditEvidence$EntityOutcome",
            "io.github.betapetey.eventtrail.audit.AuditEvidence$ItemOutcome",
            "io.github.betapetey.eventtrail.audit.AuditEvidence$Unavailable",
            "io.github.betapetey.eventtrail.audit.AuditPayloadCodec$OccurrenceConstraint",
            "io.github.betapetey.eventtrail.audit.AuditRecord$1",
            "io.github.betapetey.eventtrail.audit.AuditText",
            "io.github.betapetey.eventtrail.audit.ProductionAuditPayloadCodec$1",
            "io.github.betapetey.eventtrail.audit.codec.CompactIO$MalformedException",
            "io.github.betapetey.eventtrail.audit.codec.CompactIO$Reader",
            "io.github.betapetey.eventtrail.events.block.BlockEffectPayload$ActorKind",
            "io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec",
            "io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec$DecodeResult",
            "io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec$DecodeStatus",
            "io.github.betapetey.eventtrail.events.block.BlockEntityEvidence$Status",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayload$ActorState",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayload$MetadataState",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec$1",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec$DecodeResult",
            "io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec$Status",
            "io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec",
            "io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec$AuditProjection",
            "io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec$AuditProjectionResult",
            "io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayload$MetadataState",
            "io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec",
            "io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec$1",
            "io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec$Status",
            "io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer",
            "io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer$1",
            "io.github.betapetey.eventtrail.storage.DiskSpool$Staged",
            "io.github.betapetey.eventtrail.storage.SpoolFrameCodec",
            "io.github.betapetey.eventtrail.util.AuditSnbtParser",
            "io.github.betapetey.eventtrail.util.AuditSnbtParser$ParseResult",
            "io.github.betapetey.eventtrail.util.AuditSnbtParser$Status",
            "io.github.betapetey.eventtrail.util.DetailsBlob",
            "io.github.betapetey.eventtrail.util.DetailsBlob$Decoded",
            "io.github.betapetey.eventtrail.util.DetailsBlob$Limits",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$1",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$AuditProjection",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$CauseProjection",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$DecodeStatus",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$ProjectionCausality",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$ProjectionCause",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$ProjectionIdentity",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$ProvenanceKind",
            "io.github.betapetey.eventtrail.util.EntityEventPayloadCodec$VictimKind",
            "io.github.betapetey.eventtrail.util.EventTrailText",
            "io.github.betapetey.eventtrail.util.EventTrailTextBounds",
            "io.github.betapetey.eventtrail.util.ItemDetails$Identity");

    /**
     * Loads and initialises every class in {@link #CLASSES} with {@code loader}. Returns how many loaded. A class that
     * fails is passed to {@code debug} and skipped; only a {@link VirtualMachineError} escapes.
     */
    static int preload(ClassLoader loader, Consumer<String> debug) {
        int loaded = 0;
        for (String name : CLASSES) {
            try {
                Class.forName(name, true, loader);
                loaded++;
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                debug.accept("EventTrail hand-off class preload skipped " + name + ": " + failure);
            }
        }
        return loaded;
    }
}
