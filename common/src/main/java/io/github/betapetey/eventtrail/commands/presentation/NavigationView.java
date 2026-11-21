package io.github.betapetey.eventtrail.commands.presentation;

import java.util.Optional;

/** Previous/current/next lookup-page controls with absent directions omitted. */
public record NavigationView(
        int page,
        int totalPages,
        Optional<String> previousCommand,
        Optional<String> nextCommand) {

    public NavigationView {
        if (page < 1 || totalPages < 1 || page > totalPages) {
            throw new IllegalArgumentException("Invalid page boundary");
        }
        previousCommand = (previousCommand == null ? Optional.<String>empty() : previousCommand)
                .filter(value -> !value.isBlank());
        nextCommand = (nextCommand == null ? Optional.<String>empty() : nextCommand)
                .filter(value -> !value.isBlank());
        if ((page > 1) != previousCommand.isPresent()) {
            throw new IllegalArgumentException("Previous-page command does not match page boundary");
        }
        if ((page < totalPages) != nextCommand.isPresent()) {
            throw new IllegalArgumentException("Next-page command does not match page boundary");
        }
    }
}
