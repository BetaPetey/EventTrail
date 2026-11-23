package io.github.betapetey.eventtrail.inventory.adapters.rftools;

import java.util.UUID;

/** Runtime-only native proxy identity and reload epoch, never a persisted channel registry. */
public interface RftoolsScannerCarrier {
    UUID eventtrail$rftoolsRuntimeIdentity();
    long eventtrail$rftoolsLoadEpoch();
}
