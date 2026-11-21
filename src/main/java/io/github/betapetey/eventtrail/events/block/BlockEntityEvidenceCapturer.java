package io.github.betapetey.eventtrail.events.block;

import io.github.betapetey.eventtrail.util.EventTrailNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.charset.StandardCharsets;

/** Captures one bounded, sanitized block-entity metadata result without retaining live state. */
public final class BlockEntityEvidenceCapturer {
    private BlockEntityEvidenceCapturer() {
    }

    public static BlockEntityEvidence capture(
            Level level, BlockPos position, boolean captureFullMetadata) {
        if (level == null || position == null) return BlockEntityEvidence.failed();
        try {
            BlockEntity blockEntity = level.getBlockEntity(position);
            return blockEntity == null
                    ? BlockEntityEvidence.absent()
                    : capture(blockEntity, captureFullMetadata);
        } catch (RuntimeException | LinkageError failure) {
            EventTrailNbtUtil.reportSerializationFailure(
                    "block entity", worldName(level) + " " + position, failure);
            return BlockEntityEvidence.failed();
        }
    }

    public static BlockEntityEvidence capture(
            BlockEntity blockEntity, boolean captureFullMetadata) {
        return capture(blockEntity, EventTrailNbtUtil::saveBlockEntity, captureFullMetadata);
    }

    static BlockEntityEvidence capture(
            BlockEntity blockEntity,
            BlockEntityEncoder encoder,
            boolean captureFullMetadata) {
        if (blockEntity == null) return BlockEntityEvidence.absent();
        if (!captureFullMetadata) return BlockEntityEvidence.omittedForWriterPressure();
        try {
            CompoundTag encoded = encoder.encode(blockEntity);
            CompoundTag sanitized = BlockEntityTagSanitizer.sanitize(encoded);
            if (sanitized.sizeInBytes() > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES) {
                EventTrailNbtUtil.reportMetadataTooLarge(
                        "block entity", blockEntity.getClass().getName(), sanitized.sizeInBytes());
                return BlockEntityEvidence.tooLarge();
            }
            String canonical = BlockEntityTagSanitizer.canonicalSnbt(sanitized);
            int utf8Bytes = canonical.getBytes(StandardCharsets.UTF_8).length;
            if (canonical.length() > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_CHARS
                    || utf8Bytes > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_UTF8_BYTES) {
                EventTrailNbtUtil.reportMetadataTooLarge(
                        "block entity", blockEntity.getClass().getName(),
                        Math.max(canonical.length(), utf8Bytes));
                return BlockEntityEvidence.tooLarge();
            }
            return BlockEntityEvidence.captured(sanitized, canonical);
        } catch (BlockEntityTagSanitizer.LimitExceededException tooLarge) {
            EventTrailNbtUtil.reportMetadataTooLarge(
                    "block entity", blockEntity.getClass().getName(), tooLarge.observedAmount());
            return BlockEntityEvidence.tooLarge();
        } catch (RuntimeException | LinkageError failure) {
            EventTrailNbtUtil.reportSerializationFailure(
                    "block entity", blockEntity.getClass().getName(), failure);
            return BlockEntityEvidence.failed();
        }
    }

    private static String worldName(Level level) {
        try {
            return level.dimension().location().toString();
        } catch (RuntimeException | LinkageError failure) {
            return "unknown";
        }
    }

    @FunctionalInterface
    interface BlockEntityEncoder {
        CompoundTag encode(BlockEntity blockEntity);
    }
}
