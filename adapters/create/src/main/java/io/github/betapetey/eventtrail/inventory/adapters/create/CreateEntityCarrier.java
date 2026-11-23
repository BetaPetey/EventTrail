package io.github.betapetey.eventtrail.inventory.adapters.create;

import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;

/** Exact native owner context changes are distinct from ordinary physical motion. */
public interface CreateEntityCarrier extends MovingStorageMotion {
    long eventtrail$createContextEpoch();
    void eventtrail$createContextChanged();
}
