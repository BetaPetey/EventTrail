package io.github.betapetey.eventtrail.commands.investigation;

import java.util.Objects;

/** One immutable inspect-mode and page-owner value for a connected player. */
public record PlayerInvestigationState<I, L>(
        long generation,
        InspectMode inspectMode,
        PageState<I, L> pageState) {

    public enum InspectMode {
        DISABLED,
        ENABLED;

        InspectMode toggled() {
            return this == ENABLED ? DISABLED : ENABLED;
        }
    }

    public PlayerInvestigationState {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Investigation generations must be positive");
        }
        Objects.requireNonNull(inspectMode, "inspectMode");
        Objects.requireNonNull(pageState, "pageState");
    }

    public boolean inspecting() {
        return inspectMode == InspectMode.ENABLED;
    }
}
