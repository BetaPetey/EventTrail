package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Item 2 F1 (KL-265, KL-177, KL-176): positive proof for the "last hit by &lt;player&gt;" note. The game remembers a
 * victim's last attacker from any damage whose causing entity is a living entity: a Thorns strike names the wearer, an
 * arrow a machine fired with a player as owner names that player, and so does an arrow a command made. The note may
 * name a player only when that player's own hit set the remembered attacker, so the proof is taken where the game sets
 * it, at the hurt step (LastHitProofMixin), and read at the death:
 * <ul>
 *   <li>every {@code setLastHurtByMob}, whoever calls it, first clears the proof ({@link #clear});</li>
 *   <li>right after the hurt step's own {@code setLastHurtByMob} returns, the proof is stored only when every rule the
 *       death row itself asks would credit that real player for that hit ({@link #observe}).</li>
 * </ul>
 * The note then needs the proof to name its hitter at the remembered tick. A victim without the carrier gets no note.
 * Nothing here is counted or thrown into the game short of a {@link VirtualMachineError}; any failure leaves no proof.
 */
public final class LastHitProof {
    private LastHitProof() {
    }

    /** The head of every {@code setLastHurtByMob}: any new last-attacker assignment first drops the old proof. */
    public static void clear(LivingEntity victim) {
        if (!(victim instanceof LastHitProofCarrier carrier)) return;
        try {
            carrier.eventtrail$setProvenLastHit(null, 0);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Observation never interrupts the setter.
        }
    }

    /**
     * After the hurt step's own {@code setLastHurtByMob(attacker)} returned: store the proof when the hit is that real
     * player's own act, as the bridge's {@code provenOwnHit} decides it.
     */
    public static void observe(LivingEntity victim, LivingEntity attacker, DamageSource source) {
        // Only a player's death row carries the note, so a mob victim's hit is never checked (no cost per mob hit).
        if (!(victim instanceof Player) || !(victim instanceof LastHitProofCarrier carrier)
                || attacker == null || source == null) {
            return;
        }
        try {
            if (victim.getLastHurtByMob() != attacker) return;
            if (!NeoForgeEntityDeathAuditBridge.provenOwnHit(victim, attacker, source, typeId(source))) return;
            carrier.eventtrail$setProvenLastHit(attacker.getUUID(), victim.getLastHurtByMobTimestamp());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            // An unreadable hit proves nothing: the proof the setter cleared stays cleared.
            clear(victim);
        }
    }

    /** The damage type's registry id, or null when it has none (an unregistered type is never Thorns). */
    private static ResourceLocation typeId(DamageSource source) {
        return source.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
    }
}
