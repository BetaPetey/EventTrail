package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

/** Native storage-local conflict witness, separate from channel owner and actor identities. */
public interface EnderStorageCarrier {
    long eventtrail$externalCapabilityEpoch();
    void eventtrail$externalCapabilityMutation();
    /** Sticky: the external epoch stays {@code Long.MAX_VALUE}, so every captured and every new action guard refuses. */
    void eventtrail$externalCapabilityInvalidated();
    long eventtrail$manualActionEpoch();
    long eventtrail$beginManualAction();
}
