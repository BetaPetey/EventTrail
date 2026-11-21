package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAttribution;

import java.util.Objects;

/** Maps platform-captured actor provenance into the grouped audit model. */
public final class ActorAttributionAdapter {
    private ActorAttributionAdapter() {}

    public static AuditAttribution indexedActor(ActorProvenance actor) {
        Objects.requireNonNull(actor, "actor");
        AuditAttribution.Causality causality = switch (actor.causality()) {
            case DIRECT -> AuditAttribution.Causality.DIRECT;
            case IMMEDIATE_SECONDARY -> AuditAttribution.Causality.IMMEDIATE_SECONDARY;
            case UNATTRIBUTED -> AuditAttribution.Causality.UNATTRIBUTED;
        };
        return switch (actor.actorKind()) {
            case PLAYER -> AuditAttribution.actor(
                    actor.playerId(), actor.displayName(), false, causality, actor.detail());
            case SYNTHETIC_PLAYER -> AuditAttribution.actor(
                    actor.playerId(), actor.displayName(), true, causality, actor.detail());
            case ENVIRONMENT -> AuditAttribution.environment(actor.displayName(), actor.detail());
        };
    }
}
