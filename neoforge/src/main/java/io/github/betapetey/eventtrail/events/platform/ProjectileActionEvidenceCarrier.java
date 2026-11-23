package io.github.betapetey.eventtrail.events.platform;

import java.util.UUID;

/** Runtime-only evidence attached to a projectile by the exact player packet boundary. */
public interface ProjectileActionEvidenceCarrier {
    UUID eventtrail$getActionLauncherId();

    void eventtrail$setActionLauncherId(UUID launcherId);
}
