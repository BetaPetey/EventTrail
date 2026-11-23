package io.github.betapetey.eventtrail.events.platform;

/**
 * Runtime-only command origin attached to the exact victim's last-attacker field (command credit guard, review-01 F2).
 * Set when a command named a real player as the attacker; never stored in NBT or copied to another entity.
 */
public interface CommandEngineCreditCarrier {
    boolean eventtrail$commandEngineCreditExcluded();

    void eventtrail$setCommandEngineCreditExcluded(boolean excluded);
}
