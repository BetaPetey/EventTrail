package io.github.betapetey.eventtrail.events.platform;

import java.util.UUID;

/**
 * Item 2 F1: a live victim's proof that its last attacker's latest hit was that real player's own act (see
 * {@link LastHitProof}). One UUID and one tick per live victim, in memory only; never copied into NBT or another entity.
 */
public interface LastHitProofCarrier {
    /** The player whose own hit made them the last attacker, or null when no such hit is proven. */
    UUID eventtrail$provenLastHitPlayer();

    /** The victim's last-attacker tick that hit set; meaningful only while the player is not null. */
    int eventtrail$provenLastHitTick();

    void eventtrail$setProvenLastHit(UUID player, int tick);
}
