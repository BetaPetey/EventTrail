package io.github.betapetey.eventtrail.inventory.core;

/**
 * Canonical semantic role of a menu inventory slot.
 *
 * <p>Only roles that hold a real item on the device side may contribute to a
 * container transaction. Unknown custom-menu slots deliberately fail closed.</p>
 */
public enum InventorySlotRole {
    PLAYER(false),
    CURSOR(false),
    STORAGE(true),
    MACHINE_INPUT(true),
    MACHINE_OUTPUT(true),
    MACHINE_BYPRODUCT(true),
    MACHINE_BUFFER(true),
    MACHINE_FUEL(true),
    MACHINE_CATALYST(true),
    REAL_MODULE(true),
    REAL_UPGRADE(true),
    REAL_TOOL(true),
    REAL_FLUID_CONTAINER(true),
    CRAFTING_INPUT(false),
    CRAFTING_OUTPUT(false),
    GHOST_FILTER(false),
    CONFIGURATION(false),
    SECURITY(false),
    DISPLAY_ONLY(false),
    FLUID_PROXY(false),
    UNKNOWN(false);

    private final boolean realDeviceCustody;

    InventorySlotRole(boolean realDeviceCustody) {
        this.realDeviceCustody = realDeviceCustody;
    }

    public boolean isRealDeviceCustody() {
        return realDeviceCustody;
    }
}
