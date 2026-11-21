package io.github.betapetey.eventtrail.audit;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Exact immutable dimension and position of one audit fact. */
public record AuditLocation(ResourceLocation dimensionId, BlockPos position) {
    public AuditLocation {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
    }

    public static AuditLocation of(String dimensionId, int x, int y, int z) {
        ResourceLocation parsed = ResourceLocation.tryParse(dimensionId);
        if (parsed == null) {
            throw new IllegalArgumentException("dimensionId must be an exact resource location");
        }
        return new AuditLocation(parsed, new BlockPos(x, y, z));
    }
}
