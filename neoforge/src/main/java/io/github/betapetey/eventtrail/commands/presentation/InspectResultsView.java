package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable presentation of one inspect-owned block-history page: "N results · Query: N ms" (N is the whole history,
 * the ms are the first query's), "@ x y z · dimension" with its teleport command, and the page's rows.
 */
public record InspectResultsView(
        ResourceLocation dimensionId,
        BlockPos position,
        List<AuditRowView> rows,
        NavigationView navigation,
        long resultCount,
        long queryMillis,
        Optional<String> positionCommand) {
    public InspectResultsView {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        rows = List.copyOf(rows);
        navigation = Objects.requireNonNull(navigation, "navigation");
        if (resultCount < 0L) throw new IllegalArgumentException("resultCount cannot be negative");
        if (queryMillis < 0L) throw new IllegalArgumentException("queryMillis cannot be negative");
        positionCommand = Objects.requireNonNull(positionCommand, "positionCommand")
                .filter(command -> !command.isBlank());
    }

    /** The earlier form: the page's row count as the result count, 0 ms, no teleport command. */
    public InspectResultsView(
            ResourceLocation dimensionId,
            BlockPos position,
            List<AuditRowView> rows,
            NavigationView navigation) {
        this(dimensionId, position, rows, navigation, rows.size(), 0L, Optional.empty());
    }

    /** The same page with the "@ x y z" teleport command attached (made by the command layer, which owns links). */
    public InspectResultsView withPositionCommand(Optional<String> command) {
        return new InspectResultsView(dimensionId, position, rows, navigation, resultCount, queryMillis, command);
    }

    /** "1 result" or "N results". */
    public String resultText() {
        return resultCount + (resultCount == 1L ? " result" : " results");
    }

    /** "x y z", without the "@". */
    public String coordinates() {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
