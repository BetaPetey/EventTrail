package io.github.betapetey.eventtrail.audit;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Typed, versioned persisted evidence carried by one audit fact. */
public sealed interface AuditEvidence permits
        AuditEvidence.Block,
        AuditEvidence.Item,
        AuditEvidence.Entity,
        AuditEvidence.ContainerAccess,
        AuditEvidence.ContainerTransfer,
        AuditEvidence.Unavailable {

    Kind kind();

    int version();

    /** Deterministic bounded payload for schema-v1 {@code extra_data}. */
    String encodedPayload();

    record Block(int version, String encodedPayload) implements AuditEvidence {
        public Block {
            requireVersion(version);
            encodedPayload = requirePayload(encodedPayload);
        }

        @Override public Kind kind() { return Kind.BLOCK; }
    }

    record Item(int version, ItemOutcome outcome, String encodedPayload) implements AuditEvidence {
        public Item {
            requireVersion(version);
            outcome = Objects.requireNonNull(outcome, "outcome");
            encodedPayload = requirePayload(encodedPayload);
        }

        @Override public Kind kind() { return Kind.ITEM; }
    }

    record Entity(int version, EntityOutcome outcome, String encodedPayload) implements AuditEvidence {
        public Entity {
            requireVersion(version);
            outcome = Objects.requireNonNull(outcome, "outcome");
            encodedPayload = requirePayload(encodedPayload);
        }

        @Override public Kind kind() { return Kind.ENTITY; }
    }

    record ContainerAccess(
            int version,
            AccessPhase phase,
            String encodedPayload) implements AuditEvidence {
        public ContainerAccess {
            requireVersion(version);
            phase = Objects.requireNonNull(phase, "phase");
            encodedPayload = requirePayload(encodedPayload);
        }

        @Override public Kind kind() { return Kind.CONTAINER_ACCESS; }
    }

    record ContainerTransfer(
            int version,
            TransferDirection direction,
            ResourceLocation itemId,
            long quantity,
            String encodedPayload) implements AuditEvidence {
        public ContainerTransfer {
            requireVersion(version);
            direction = Objects.requireNonNull(direction, "direction");
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (quantity <= 0L || quantity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "A persisted container-transfer chunk requires an integer-sized positive quantity");
            }
            encodedPayload = requirePayload(encodedPayload);
        }

        @Override public Kind kind() { return Kind.CONTAINER_TRANSFER; }
    }

    /** Controlled restoration result for an unsupported private-alpha payload. */
    record Unavailable(Kind kind, Status status) implements AuditEvidence {
        public Unavailable {
            kind = Objects.requireNonNull(kind, "kind");
            status = Objects.requireNonNull(status, "status");
        }

        @Override public int version() { return 0; }

        @Override public String encodedPayload() { return null; }
    }

    enum Kind { BLOCK, ITEM, ENTITY, CONTAINER_ACCESS, CONTAINER_TRANSFER }

    enum ItemOutcome { DROP, PICKUP }

    enum EntityOutcome { KILL, DEATH }

    enum AccessPhase { OPEN, CLOSE }

    enum TransferDirection { DEPOSIT, WITHDRAWAL }

    enum Status { UNSUPPORTED, MALFORMED, TOO_LARGE }

    private static void requireVersion(int version) {
        if (version <= 0) throw new IllegalArgumentException("Evidence version must be positive");
    }

    private static String requirePayload(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Supported evidence requires a nonblank encoded payload");
        }
        return value;
    }
}
