package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

/** One bounded native menu owns its opening provenance; no global channel/session registry. */
public interface EnderMenuCarrier {
    EnderStorageBinding eventtrail$enderBinding();
    void eventtrail$enderBinding(EnderStorageBinding binding);
}
