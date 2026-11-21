package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.util.EventTrailFailure;
import java.util.Objects;

/** Writer admission authority; history readiness and historical loss are separate facts. */
public record WriteAdmissionSnapshot(long version, State state, EventTrailFailure failure, History history) {
    public enum State { UNKNOWN, STARTING, ACCEPTING, BUFFERING, DISABLED, STOPPING, STOPPED }
    public enum History { UNKNOWN, READABLE, UNAVAILABLE }

    public WriteAdmissionSnapshot(long version, State state, EventTrailFailure failure) {
        this(version, state, failure, History.UNKNOWN);
    }

    public WriteAdmissionSnapshot {
        if (version < 0L) throw new IllegalArgumentException("Negative writer observation version");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(history, "history");
    }

    public boolean accepting() {
        return state == State.ACCEPTING || state == State.BUFFERING;
    }

    public static WriteAdmissionSnapshot unknown() {
        return new WriteAdmissionSnapshot(0L, State.UNKNOWN, null);
    }
}
