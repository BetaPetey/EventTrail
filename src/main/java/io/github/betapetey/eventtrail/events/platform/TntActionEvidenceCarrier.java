package io.github.betapetey.eventtrail.events.platform;

import java.util.UUID;

/** Runtime-only proof attached to TNT created by an exact player use-on-block action. */
public interface TntActionEvidenceCarrier {
    UUID eventtrail$getActionIgniterId();

    void eventtrail$setActionIgniterId(UUID igniterId);
}
