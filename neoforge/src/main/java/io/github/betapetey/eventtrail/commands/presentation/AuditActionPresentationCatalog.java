package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Plain-language action vocabulary shared by inspect and structured lookup. */
public final class AuditActionPresentationCatalog {
    /** Own-slot screens "opened" line: appended to the open and close label of a row marked "items not tracked". */
    public static final String ITEMS_NOT_TRACKED_SUFFIX = " (items not tracked)";
    /**
     * GF-TRASHTARGET (Question 15): the labels of a trash target's rows. A trash keeps what was put into it until a
     * later loss that writes nothing, so its rows say "put into trash" and "taken from trash", never "destroyed", and
     * they never read as a block's container at the trash's access point.
     */
    public static final String PUT_INTO_TRASH = "Put Into Trash";
    public static final String TAKEN_FROM_TRASH = "Taken From Trash";
    public static final String OPEN_TRASH = "Open Trash";
    public static final String CLOSE_TRASH = "Close Trash";

    private final Map<AuditAction, String> labels;

    public AuditActionPresentationCatalog() {
        EnumMap<AuditAction, String> configured = new EnumMap<>(AuditAction.class);
        configured.put(AuditAction.BLOCK_BREAK, "Break Block");
        configured.put(AuditAction.BLOCK_PLACE, "Place Block");
        configured.put(AuditAction.CONTAINER_ACCESS, "Access Container");
        configured.put(AuditAction.CONTAINER_DEPOSIT, "Added Item");
        configured.put(AuditAction.CONTAINER_WITHDRAWAL, "Removed Item");
        configured.put(AuditAction.ENTITY_KILL, "Kill Entity");
        configured.put(AuditAction.ENTITY_DEATH, "Entity Death");
        configured.put(AuditAction.ITEM_DROP, "Drop Item");
        configured.put(AuditAction.ITEM_PICKUP, "Pick Up Item");
        configured.put(AuditAction.EXPLOSION, "Explosion");
        if (configured.size() != AuditAction.catalog().size()) {
            throw new IllegalStateException("Every EventTrail action requires a presentation label");
        }
        labels = Map.copyOf(configured);
    }

    public String label(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.evidence() instanceof AuditEvidence.ContainerAccess access) {
            boolean open = access.phase() == AuditEvidence.AccessPhase.OPEN;
            // GF-TRASHTARGET: a trash target's open and close rows name a trash, not a container.
            if (ContainerAccessPayloadCodec.trashTarget(access.encodedPayload())) {
                return open ? OPEN_TRASH : CLOSE_TRASH;
            }
            String label = open ? "Open Container" : "Close Container";
            // Own-slot screens "opened" line (Owner decision A, 11-14): the row's screen records no item moves.
            return ContainerAccessPayloadCodec.itemsNotTracked(access.encodedPayload())
                    ? label + ITEMS_NOT_TRACKED_SUFFIX
                    : label;
        }
        if (record.evidence() instanceof AuditEvidence.ContainerTransfer transfer
                && trashTransfer(record.action(), transfer)) {
            return record.action() == AuditAction.CONTAINER_DEPOSIT ? PUT_INTO_TRASH : TAKEN_FROM_TRASH;
        }
        return label(record.action());
    }

    public String label(AuditAction action) {
        String label = labels.get(Objects.requireNonNull(action, "action"));
        if (label == null) throw new IllegalArgumentException("Unsupported EventTrail action");
        return label;
    }

    /**
     * GF-TRASHTARGET: whether an item row's stored payload decodes, names a {@link BackingContextKind#TRASH} target,
     * and agrees with the row's action. Anything else keeps the ordinary label; never throws.
     */
    private static boolean trashTransfer(AuditAction action, AuditEvidence.ContainerTransfer transfer) {
        try {
            ContainerTransferPayloadCodec.DecodeResult decoded =
                    ContainerTransferPayloadCodec.decode(transfer.encodedPayload());
            if (!decoded.successful() || decoded.payload().backingContextKind() != BackingContextKind.TRASH) {
                return false;
            }
            TransactionDirection direction = decoded.payload().direction();
            return action == AuditAction.CONTAINER_DEPOSIT
                    ? direction == TransactionDirection.DEPOSIT
                    : action == AuditAction.CONTAINER_WITHDRAWAL && direction == TransactionDirection.WITHDRAWAL;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
