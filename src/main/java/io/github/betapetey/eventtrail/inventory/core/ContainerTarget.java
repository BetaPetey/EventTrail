package io.github.betapetey.eventtrail.inventory.core;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * Stable access and backing identity for one inventory session.
 *
 * <p>The context key is internal evidence state and is never rendered into a
 * EventTrail row. Coordinates identify the access point, which may differ from the
 * backing store for player-bound, network, or proxy inventories.</p>
 */
public record ContainerTarget(
        String dimensionId,
        Integer x,
        Integer y,
        Integer z,
        String label,
        AccessKind accessKind,
        BackingContextKind backingContextKind,
        String stableContextKey) {

    public ContainerTarget {
        dimensionId = requireText(dimensionId, "dimensionId");
        // One text rule for every container label (clean, no lone surrogate, at most 256 UTF-8 bytes).
        label = ContainerLabelText.containerLabel(requireText(label, "label"));
        accessKind = Objects.requireNonNull(accessKind, "accessKind");
        backingContextKind = Objects.requireNonNull(backingContextKind, "backingContextKind");
        stableContextKey = requireText(stableContextKey, "stableContextKey");

        boolean anyCoordinate = x != null || y != null || z != null;
        boolean allCoordinates = x != null && y != null && z != null;
        if (anyCoordinate && !allCoordinates) {
            throw new IllegalArgumentException("Access coordinates must be all present or all absent");
        }
        if (backingContextKind == BackingContextKind.UNKNOWN) {
            throw new IllegalArgumentException("Unknown backing identity cannot produce audit evidence");
        }
    }

    public static ContainerTarget block(
            String dimensionId,
            BlockPos pos,
            String label,
            BackingContextKind backingContextKind,
            String stableContextKey) {
        Objects.requireNonNull(pos, "pos");
        return new ContainerTarget(
                dimensionId,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                label,
                AccessKind.PHYSICAL,
                backingContextKind,
                stableContextKey);
    }

    public boolean hasAccessPosition() {
        return x != null;
    }

    public BlockPos accessPosition() {
        if (!hasAccessPosition()) {
            throw new IllegalStateException("Target does not have a physical access position");
        }
        return new BlockPos(x, y, z);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
