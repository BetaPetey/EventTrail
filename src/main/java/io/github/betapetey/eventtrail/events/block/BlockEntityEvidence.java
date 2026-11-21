package io.github.betapetey.eventtrail.events.block;

import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Typed block-entity metadata result; non-captured outcomes never carry fabricated data. */
public final class BlockEntityEvidence {
    private final Status status;
    private final CompoundTag capturedData;
    private final String canonicalSnbt;

    private BlockEntityEvidence(Status status, CompoundTag capturedData, String canonicalSnbt) {
        this.status = Objects.requireNonNull(status, "status");
        if (status == Status.CAPTURED) {
            this.capturedData = Objects.requireNonNull(capturedData, "capturedData").copy();
            this.canonicalSnbt = Objects.requireNonNull(canonicalSnbt, "canonicalSnbt");
        } else {
            if (capturedData != null || canonicalSnbt != null) {
                throw new IllegalArgumentException("Only captured metadata may carry retained data");
            }
            this.capturedData = null;
            this.canonicalSnbt = null;
        }
    }

    public static BlockEntityEvidence absent() {
        return outcome(Status.ABSENT);
    }

    public static BlockEntityEvidence failed() {
        return outcome(Status.FAILED);
    }

    public static BlockEntityEvidence tooLarge() {
        return outcome(Status.TOO_LARGE);
    }

    public static BlockEntityEvidence omittedForWriterPressure() {
        return outcome(Status.OMITTED_WRITER_PRESSURE);
    }

    public static BlockEntityEvidence omittedForOperationBudget() {
        return outcome(Status.OMITTED_OPERATION_BUDGET);
    }

    /** Builds captured evidence from a defensively sanitized, bounded copy. */
    public static BlockEntityEvidence captured(CompoundTag data) {
        CompoundTag sanitized = BlockEntityTagSanitizer.sanitize(data);
        String canonical = BlockEntityTagSanitizer.canonicalSnbt(sanitized);
        if (sanitized.sizeInBytes() > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES
                || canonical.length() > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_CHARS
                || canonical.getBytes(StandardCharsets.UTF_8).length
                        > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_UTF8_BYTES) {
            throw new IllegalArgumentException("Captured block-entity metadata exceeds its bound");
        }
        return captured(sanitized, canonical);
    }

    static BlockEntityEvidence captured(CompoundTag data, String canonicalSnbt) {
        return new BlockEntityEvidence(Status.CAPTURED, data, canonicalSnbt);
    }

    public static BlockEntityEvidence outcome(Status status) {
        if (status == Status.CAPTURED) {
            throw new IllegalArgumentException("Captured metadata requires retained data");
        }
        return new BlockEntityEvidence(status, null, null);
    }

    public Status status() {
        return status;
    }

    public Optional<CompoundTag> capturedData() {
        return capturedData == null ? Optional.empty() : Optional.of(capturedData.copy());
    }

    public Optional<String> canonicalSnbt() {
        return Optional.ofNullable(canonicalSnbt);
    }

    public int retainedUtf8Bytes() {
        return canonicalSnbt == null ? 0 : canonicalSnbt.getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean omitted() {
        return status == Status.OMITTED_WRITER_PRESSURE
                || status == Status.OMITTED_OPERATION_BUDGET;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockEntityEvidence evidence
                && status == evidence.status
                && Objects.equals(canonicalSnbt, evidence.canonicalSnbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, canonicalSnbt);
    }

    @Override
    public String toString() {
        return status.storageCode();
    }

    public enum Status {
        ABSENT("absent"),
        CAPTURED("captured"),
        FAILED("failed"),
        TOO_LARGE("too_large"),
        OMITTED_WRITER_PRESSURE("omitted_writer_pressure"),
        OMITTED_OPERATION_BUDGET("omitted_operation_budget");

        private final String storageCode;

        Status(String storageCode) {
            this.storageCode = storageCode;
        }

        public String storageCode() {
            return storageCode;
        }

        public static Optional<Status> fromStorageCode(String value) {
            return Arrays.stream(values())
                    .filter(status -> status.storageCode.equals(value))
                    .findFirst();
        }
    }
}
