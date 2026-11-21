package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.storage.WriteAdmissionSnapshot;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, message-free recording state. Never inferred from a successful history read. */
public record RecordingSnapshot(
        UUID serverRunId,
        long generation,
        long revision,
        State state,
        EventTrailFailure.Backend backend,
        boolean historyReady,
        EventTrailFailure originalFailure,
        EventTrailFailure currentFailure,
        List<EventTrailFailure> cleanupFailures,
        long disabledSinceMillis,
        long lastRecoveryMillis) {
    public enum State { STARTING, RECORDING, BUFFERING, DISABLED, STOPPING, STOPPED }

    public RecordingSnapshot {
        Objects.requireNonNull(serverRunId, "serverRunId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(backend, "backend");
        cleanupFailures = List.copyOf(Objects.requireNonNull(cleanupFailures, "cleanupFailures"));
        if (cleanupFailures.size() > 8) throw new IllegalArgumentException("Too many cleanup facts");
        if (generation < 0L || revision < 0L || disabledSinceMillis < 0L || lastRecoveryMillis < 0L) {
            throw new IllegalArgumentException("Invalid recording identity or time");
        }
    }

    public static RecordingSnapshot stopped(UUID runId) {
        return new RecordingSnapshot(runId, 0L, 0L, State.STOPPED,
                EventTrailFailure.Backend.STORAGE, false, null, null, List.of(), 0L, 0L);
    }

    public RecordingSnapshot starting(long nextGeneration, EventTrailFailure.Backend nextBackend) {
        boolean continuingFailure = state == State.DISABLED;
        return new RecordingSnapshot(
                state == State.STOPPED ? UUID.randomUUID() : serverRunId,
                nextGeneration, revision + 1L,
                continuingFailure ? State.DISABLED : State.STARTING,
                nextBackend, false,
                continuingFailure ? originalFailure : null,
                continuingFailure ? currentFailure : null,
                continuingFailure ? cleanupFailures : List.of(),
                continuingFailure ? disabledSinceMillis : 0L,
                state == State.STOPPED ? 0L : lastRecoveryMillis);
    }

    public RecordingSnapshot disabled(
            EventTrailFailure cause, List<EventTrailFailure> cleanup, boolean readable, long now) {
        Objects.requireNonNull(cause, "cause");
        boolean continuing = state == State.DISABLED;
        return new RecordingSnapshot(serverRunId, generation, revision + 1L, State.DISABLED,
                backend, readable, continuing && originalFailure != null ? originalFailure : cause,
                cause, cleanup, continuing ? disabledSinceMillis : Math.max(0L, now), lastRecoveryMillis);
    }

    /** Only the lifecycle owner may call this after current-generation startup succeeds. */
    public RecordingSnapshot admitted(WriteAdmissionSnapshot writer, boolean readable, long now) {
        Objects.requireNonNull(writer, "writer");
        readable = switch (writer.history()) {
            case READABLE -> true;
            case UNAVAILABLE -> false;
            case UNKNOWN -> readable;
        };
        if (!writer.accepting()) {
            EventTrailFailure cause = writer.failure() != null ? writer.failure()
                    : new EventTrailFailure(EventTrailFailure.Code.AUDIT_WRITER_STOPPED,
                            EventTrailFailure.Phase.WRITER, backend, EventTrailFailure.FileRole.UNKNOWN);
            return disabled(cause, cleanupFailures, readable, now);
        }
        return new RecordingSnapshot(serverRunId, generation, revision + 1L,
                writer.state() == WriteAdmissionSnapshot.State.BUFFERING ? State.BUFFERING : State.RECORDING,
                backend, readable, null, null, List.of(), 0L,
                state == State.DISABLED ? Math.max(0L, now) : lastRecoveryMillis);
    }

    public RecordingSnapshot stopping(boolean stopped) {
        return new RecordingSnapshot(serverRunId, generation, revision + 1L,
                stopped ? State.STOPPED : State.STOPPING, backend, false,
                originalFailure, currentFailure, cleanupFailures, disabledSinceMillis, lastRecoveryMillis);
    }

    public boolean recordingAvailable() {
        return state == State.RECORDING || state == State.BUFFERING;
    }
}
