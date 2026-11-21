package io.github.betapetey.eventtrail.events.block;

import java.util.Arrays;
import java.util.Optional;

/** Explicit bounded-work reduction attached to otherwise truthful block-effect evidence. */
public enum BlockEffectTruncation {
    NONE("none"),
    PLACEMENT_MEMBER_LIMIT("placement_member_limit"),
    EXPLOSION_EVIDENCE_LIMIT("explosion_evidence_limit"),
    EXPLOSION_STORAGE_PRESSURE("explosion_storage_pressure"),
    EXPLOSION_PROCESSING_FAILURE("explosion_processing_failure");

    private final String storageCode;

    BlockEffectTruncation(String storageCode) {
        this.storageCode = storageCode;
    }

    public String storageCode() {
        return storageCode;
    }

    public static Optional<BlockEffectTruncation> fromStorageCode(String value) {
        return Arrays.stream(values())
                .filter(truncation -> truncation.storageCode.equals(value))
                .findFirst();
    }
}
