package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Immutable presentation of one inspect-owned block-history page. */
public record InspectResultsView(
        ResourceLocation dimensionId,
        BlockPos position,
        List<AuditRowView> rows,
        NavigationView navigation) {
    public InspectResultsView {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        rows = List.copyOf(rows);
        navigation = Objects.requireNonNull(navigation, "navigation");
    }
}
