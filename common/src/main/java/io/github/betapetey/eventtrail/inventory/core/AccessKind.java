package io.github.betapetey.eventtrail.inventory.core;

/** How the player reached the audited inventory. */
public enum AccessKind {
    PHYSICAL,
    WIRED_NETWORK,
    WIRELESS_NETWORK,
    PORTABLE_MENU,
    DIRECT_INTERACTION
}
