package io.github.betapetey.eventtrail.inventory.access;

import java.util.Objects;
import java.util.UUID;

/** Immutable positive evidence for one accepted menu-session lifecycle phase. */
public record ContainerAccessEvidence(
        long timestamp,
        long openedAt,
        UUID sessionId,
        ContainerAccessPhase phase,
        ContainerAccessActor actor,
        ContainerAccessTargetSnapshot target) {

    public ContainerAccessEvidence {
        if (timestamp < 0L) throw new IllegalArgumentException("timestamp must not be negative");
        if (openedAt < 0L || openedAt > timestamp) {
            throw new IllegalArgumentException("openedAt must describe this session");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        phase = Objects.requireNonNull(phase, "phase");
        actor = Objects.requireNonNull(actor, "actor");
        target = Objects.requireNonNull(target, "target");
    }
}
