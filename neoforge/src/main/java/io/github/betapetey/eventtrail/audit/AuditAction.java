package io.github.betapetey.eventtrail.audit;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete persisted EventTrail action-family vocabulary.
 *
 * <p>Database codes and retained internal family tokens are explicit contracts;
 * neither enum names nor declaration order are persisted. Public lookup selectors
 * are defined separately by LookupActionSelector; container phases are not new
 * persisted actions.</p>
 */
public enum AuditAction {
    BLOCK_BREAK("BLOCK_BREAK", "break", 10, AuditEvidence.Kind.BLOCK),
    BLOCK_PLACE("BLOCK_PLACE", "place", 20, AuditEvidence.Kind.BLOCK),
    CONTAINER_ACCESS("CONTAINER_ACCESS", "container_access", 30, AuditEvidence.Kind.CONTAINER_ACCESS),
    CONTAINER_DEPOSIT("CONTAINER_DEPOSIT", "container_deposit", 40, AuditEvidence.Kind.CONTAINER_TRANSFER),
    CONTAINER_WITHDRAWAL("CONTAINER_WITHDRAWAL", "container_withdrawal", 41, AuditEvidence.Kind.CONTAINER_TRANSFER),
    ENTITY_KILL("ENTITY_KILL", "kill", 50, AuditEvidence.Kind.ENTITY),
    ENTITY_DEATH("ENTITY_DEATH", "death", 60, AuditEvidence.Kind.ENTITY),
    ITEM_DROP("ITEM_DROP", "drop", 70, AuditEvidence.Kind.ITEM),
    ITEM_PICKUP("ITEM_PICKUP", "pickup", 80, AuditEvidence.Kind.ITEM),
    EXPLOSION("EXPLOSION", "explosion", 90, AuditEvidence.Kind.BLOCK);

    private static final List<AuditAction> CATALOG = Arrays.stream(values())
            .sorted(java.util.Comparator.comparingInt(AuditAction::catalogOrder))
            .toList();
    private static final Map<String, AuditAction> BY_STORAGE_CODE = uniqueIndex(true);
    private static final Map<String, AuditAction> BY_COMMAND_TOKEN = uniqueIndex(false);

    private final String storageCode;
    private final String commandToken;
    private final int catalogOrder;
    private final AuditEvidence.Kind evidenceKind;

    AuditAction(
            String storageCode,
            String commandToken,
            int catalogOrder,
            AuditEvidence.Kind evidenceKind) {
        this.storageCode = storageCode;
        this.commandToken = commandToken;
        this.catalogOrder = catalogOrder;
        this.evidenceKind = evidenceKind;
    }

    public String storageCode() {
        return storageCode;
    }

    public String commandToken() {
        return commandToken;
    }

    public int catalogOrder() {
        return catalogOrder;
    }

    public AuditEvidence.Kind evidenceKind() {
        return evidenceKind;
    }

    public boolean isContainerTransfer() {
        return evidenceKind == AuditEvidence.Kind.CONTAINER_TRANSFER;
    }

    /** The typed custody direction required by a concrete transfer action. */
    public AuditEvidence.TransferDirection transferDirection() {
        return switch (this) {
            case CONTAINER_DEPOSIT -> AuditEvidence.TransferDirection.DEPOSIT;
            case CONTAINER_WITHDRAWAL -> AuditEvidence.TransferDirection.WITHDRAWAL;
            default -> throw new IllegalStateException("Not a container transfer action");
        };
    }

    public AuditAttribution.IndexedRole indexedRole() {
        return this == ENTITY_DEATH
                ? AuditAttribution.IndexedRole.VICTIM
                : AuditAttribution.IndexedRole.ACTOR;
    }

    public static List<AuditAction> catalog() {
        return CATALOG;
    }

    public static Optional<AuditAction> fromStorageCode(String value) {
        return Optional.ofNullable(BY_STORAGE_CODE.get(value));
    }

    public static Optional<AuditAction> fromCommandToken(String value) {
        return Optional.ofNullable(BY_COMMAND_TOKEN.get(value));
    }

    private static Map<String, AuditAction> uniqueIndex(boolean storage) {
        Map<String, AuditAction> index = new LinkedHashMap<>();
        for (AuditAction action : values()) {
            String key = storage ? action.storageCode : action.commandToken;
            if (key == null || key.isBlank() || index.put(key, action) != null) {
                throw new ExceptionInInitializerError("Duplicate or blank EventTrail action contract: " + key);
            }
        }
        return Collections.unmodifiableMap(index);
    }
}
