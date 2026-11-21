package io.github.betapetey.eventtrail.inventory.core;

/** What owns the inventory contents independently of the access point. */
public enum BackingContextKind {
    WORLD_BLOCK,
    COMPOUND_BLOCK,
    ENTITY_BOUND,
    ITEM_BOUND,
    NESTED_ITEM,
    PLACED_BLOCK,
    PLAYER_BOUND,
    OTHER_PLAYER_BOUND,
    NETWORK,
    SHARED_CHANNEL,
    PROXY,
    MOVING_STORE,
    /**
     * GF-TRASHTARGET (Question 15): a mod's trash or void slot, located at its access point (the trash block, or the
     * player's block position for a per-player or per-screen trash). It proves no block ownership: lookups show it as
     * a trash, never as a block's container at that position. Built only by {@code TrashTargets}.
     */
    TRASH,
    UNKNOWN
}
