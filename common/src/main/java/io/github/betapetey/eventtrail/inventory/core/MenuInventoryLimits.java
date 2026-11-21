package io.github.betapetey.eventtrail.inventory.core;

/** Resource ceilings, never evidence that an otherwise unsupported menu is compatible. */
public final class MenuInventoryLimits {
    /** Complete menu/index space, including players, controls and exact adapter additions. */
    public static final int MAX_MENU_DESCRIPTORS = 2048;
    /** Unique device cells read for a custody snapshot; aliases count once, players never count. */
    public static final int MAX_DEVICE_CUSTODY_CELLS = 1024;
    /** Independent owner-derived backing-object budget, preserved from the original contract. */
    public static final int MAX_BACKING_OBJECTS = 300;
    /** Independent physical/portable ownership-member budget; not a multi-block expansion. */
    public static final int MAX_OWNERSHIP_MEMBERS = 300;

    private MenuInventoryLimits() {}
}
