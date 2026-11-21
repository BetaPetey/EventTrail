package io.github.betapetey.eventtrail.inventory.access;

import java.util.Locale;
import java.util.Optional;

/** Public phase carried by one {@code CONTAINER_ACCESS} audit row. */
public enum ContainerAccessPhase {
    OPEN,
    CLOSE;

    public static Optional<ContainerAccessPhase> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
