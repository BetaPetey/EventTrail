package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidenceCapturer;
import io.github.betapetey.eventtrail.events.block.CanonicalBlockStateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Immutable, bounded block evidence captured before a destructive operation.
 * The source BlockEntity is serialized immediately and is never retained.
 */
public record BlockAuditPreimage(
        long timestamp,
        ResourceLocation dimensionId,
        BlockPos position,
        BlockState blockState,
        CanonicalBlockStateSnapshot canonicalBlockState,
        BlockEntityEvidence blockEntityEvidence) {

    public BlockAuditPreimage {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        blockState = Objects.requireNonNull(blockState, "blockState");
        canonicalBlockState = Objects.requireNonNull(canonicalBlockState, "canonicalBlockState");
        blockEntityEvidence = Objects.requireNonNull(blockEntityEvidence, "blockEntityEvidence");
    }

    public static BlockAuditPreimage capture(
            long timestamp,
            Level level,
            BlockPos position,
            BlockState blockState,
            boolean captureFullMetadata) {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockState, "blockState");

        ResourceLocation dimensionId = Objects.requireNonNull(
                level.dimension().location(), "dimensionId");
        return new BlockAuditPreimage(
                timestamp,
                dimensionId,
                position,
                blockState,
                CanonicalBlockStateSnapshot.capture(blockState),
                BlockEntityEvidenceCapturer.capture(level, position, captureFullMetadata));
    }

    public boolean differsFrom(BlockState finalState) {
        return finalState != null && !blockState.equals(finalState);
    }
}
