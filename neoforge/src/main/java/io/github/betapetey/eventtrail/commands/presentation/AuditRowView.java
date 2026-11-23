package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/** Immutable presentation facts for one audit row. */
public record AuditRowView(
        long ordinal,
        String relativeAge,
        String indexedParty,
        String actionLabel,
        String detail,
        RowLocation location) {

    public AuditRowView {
        if (ordinal < 1L) throw new IllegalArgumentException("ordinal must be positive");
        relativeAge = required(relativeAge, "relativeAge");
        indexedParty = required(indexedParty, "indexedParty");
        actionLabel = required(actionLabel, "actionLabel");
        detail = required(detail, "detail");
        location = Objects.requireNonNull(location, "location");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Dimension and coordinates are one identity; only navigationCommand is interactive. */
    public record RowLocation(
            ResourceLocation dimensionId,
            BlockPos position,
            boolean showDimension,
            Optional<String> navigationCommand) {
        public RowLocation {
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            navigationCommand = Objects.requireNonNull(navigationCommand, "navigationCommand")
                    .filter(command -> !command.isBlank());
        }
    }
}
