package io.github.betapetey.eventtrail.inventory.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Objects;
import java.util.Optional;

/** Exact dimension and position owned by one physical menu's block entity. */
public record MenuPhysicalBlockTarget(ResourceKey<Level> dimension, BlockPos position) {
    public MenuPhysicalBlockTarget {
        dimension = Objects.requireNonNull(dimension, "dimension");
        position = Objects.requireNonNull(position, "position").immutable();
    }

    /**
     * Resolves only a block entity attached to the invoking player's actual
     * level. Remote or detached block entities are never accepted as local
     * click attribution.
     */
    public static Optional<MenuPhysicalBlockTarget> fromBlockEntity(
            BlockEntity blockEntity,
            Level expectedLevel,
            ResourceKey<Level> expectedDimension) {
        if (blockEntity == null
                || expectedLevel == null
                || expectedDimension == null
                || blockEntity.getLevel() != expectedLevel) {
            return Optional.empty();
        }

        BlockPos position = blockEntity.getBlockPos().immutable();
        if (!isCurrentBlockEntity(
                expectedDimension.equals(expectedLevel.dimension()),
                blockEntity.isRemoved(),
                expectedLevel.getBlockEntity(position) == blockEntity)) {
            return Optional.empty();
        }
        return Optional.of(new MenuPhysicalBlockTarget(
                expectedDimension,
                position));
    }

    static boolean isCurrentBlockEntity(
            boolean expectedDimension,
            boolean removed,
            boolean exactLoadedInstance) {
        return expectedDimension && !removed && exactLoadedInstance;
    }
}
