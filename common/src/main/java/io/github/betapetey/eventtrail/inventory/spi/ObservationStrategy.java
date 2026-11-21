package io.github.betapetey.eventtrail.inventory.spi;

/** Inventory evidence strategy selected for one owned menu session. */
public enum ObservationStrategy {
    ACTION_SCOPED_PHYSICAL(true),
    AUTHORITATIVE_OPERATION(true);

    private final boolean actionScoped;

    ObservationStrategy(boolean actionScoped) {
        this.actionScoped = actionScoped;
    }

    public boolean isActionScoped() {
        return actionScoped;
    }
}
