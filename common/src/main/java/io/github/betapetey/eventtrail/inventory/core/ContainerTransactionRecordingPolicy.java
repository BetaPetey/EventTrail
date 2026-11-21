package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.EventTrailConfig;

/** Persistence policy only: observers must still settle disabled-direction custody. */
public final class ContainerTransactionRecordingPolicy {
    private ContainerTransactionRecordingPolicy() {}

    public static boolean enabled(TransactionDirection direction) {
        return switch (java.util.Objects.requireNonNull(direction, "direction")) {
            case DEPOSIT -> EventTrailConfig.TRACK_CONTAINER_DEPOSITS.get();
            case WITHDRAWAL -> EventTrailConfig.TRACK_CONTAINER_WITHDRAWALS.get();
        };
    }
}
