package io.github.betapetey.eventtrail.inventory.onplayer;

import java.util.Objects;

/**
 * What kind of thing changed a player's inventory when the player is not credited (GF-ONPLAYER design 3.10): one value
 * per kind of cause. The InventoryChange payload stores the value's {@link #name()} as a bounded token, and a name this
 * build does not know is refused on decode, never guessed. Each later user lane adds its own value.
 *
 * <p>There is no magnet value. Under the Owner's magnet ruling every carried or worn magnet is the player's own pickup,
 * and a placed magnet writes nothing, so no magnet is an on-player source.</p>
 *
 * <p>{@link #timing()} is for the flood cap and the coalescer only. It is not stored in the payload.</p>
 */
public enum OnPlayerSource {
    /** The SecurityCraft Inventory Scanner's seizure: one bracket, never held by the coalescer (design 3.14). */
    INVENTORY_SCANNER(Timing.ONE_SHOT),
    /** A refill upgrade topping up the player's inventory on its tick. */
    REFILL_TICK(Timing.TICK),
    /** A feeding upgrade feeding the player on its tick. */
    FEEDING(Timing.TICK),
    /** A pump upgrade filling or draining a fluid container the player holds (decision 23 = A). */
    PUMP(Timing.TICK),
    /** The ME restock, which follows the player's own use. */
    ME_RESTOCK(Timing.ONE_SHOT),
    /** A restock card or auto-stock armour (advanced_ae#1, ae2importexportcard#0/#1), driven by its own tick. */
    CARD(Timing.TICK),
    /** The Create toolbox's top-up. */
    TOOLBOX(Timing.TICK),
    /** A network insert upgrade moving the player's items into a network. */
    NETWORK_INSERT(Timing.TICK),
    /** A network export upgrade moving items from a network to the player. */
    NETWORK_EXPORT(Timing.TICK),
    /** A creature fetching items for the player, such as the Occultism Greedy Familiar (design 3.11, H-OCC-FAM). */
    FAMILIAR(Timing.TICK),
    /** A starter kit's loss-only bracket: one per use. */
    STARTER_KIT(Timing.ONE_SHOT);

    /** Whether a source is driven by a tick or by one event (design 3.10). The cap and the coalescer use it. */
    public enum Timing {
        TICK,
        ONE_SHOT
    }

    private final Timing timing;

    OnPlayerSource(Timing timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public Timing timing() {
        return timing;
    }
}
