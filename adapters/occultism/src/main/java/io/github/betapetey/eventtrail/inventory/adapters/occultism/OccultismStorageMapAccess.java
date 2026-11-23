package io.github.betapetey.eventtrail.inventory.adapters.occultism;

/** Constant-size mutation witness; no storage mirror or global handler registry. */
public interface OccultismStorageMapAccess {
    OccultismStorageMutationWitness eventtrail$occultismMutations();
}
