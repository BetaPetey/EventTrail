package io.github.betapetey.eventtrail.events.spells;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import io.github.betapetey.eventtrail.events.platform.NeoForgeProjectileActionAuditBridge;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import net.minecraft.world.entity.player.Player;

/**
 * Withholds historical engine credit after exact Ars damage/status observations.
 * This bit grants no authority and retains no actor, source, timestamp or history.
 * Iron's exclusion remains a separate, unchanged predicate.
 */
public final class ArsEngineCreditGuard {
    private ArsEngineCreditGuard() {
    }

    public static boolean permitsEngineCredit(LivingEntity victim) {
        return !(victim instanceof ArsEngineCreditCarrier carrier)
                || permitsCarrierCredit(carrier);
    }

    /** Every actual last-attacker replacement resets this mod's exclusion only. */
    public static void clear(ArsEngineCreditCarrier carrier) {
        carrier.eventtrail$setArsEngineCreditExcluded(false);
    }

    /** Observes the source after the exact hurt -> last-attacker assignment. */
    public static void observeCreditSource(ArsEngineCreditCarrier carrier, DamageSource source) {
        boolean excluded = true;
        try {
            excluded = unprovenFirework(source) || (carrier instanceof LivingEntity victim
                    ? ArsSpellAuditRuntime.isArsDamage(victim, source)
                    : ArsSpellAuditRuntime.isArsSource(source));
        } catch (Throwable failure) {
            // Observation cannot interrupt damage; unreadable source evidence
            // cannot make historical player credit trustworthy. Any throwable, an
            // Error included, leaves the exclusion on (R2-inner: fail-conservative)
            // and is reported to the fail-safe.
            AdapterFailSafe.report(AdapterKey.ARS_CAST, "bridge:ArsEngineCreditGuard.observeCreditSource", failure);
        }
        observeClassification(carrier, excluded);
    }

    // Ars Firework becomes a vanilla entity, including after save/load. Generic
    // attribution already rejects an unproven projectile; historical credit must
    // not later turn that same owner-only hit into a human action.
    static boolean unprovenFirework(DamageSource source) {
        return source != null && source.getDirectEntity() instanceof FireworkRocketEntity firework
                && (!(source.getEntity() instanceof Player player) || NeoForgePlayerProvenance.isSynthetic(player)
                || !NeoForgeProjectileActionAuditBridge.wasLaunchedByPlayerAction(firework, player.getUUID()));
    }

    /**
     * For an exact successful Ars status/fire application which performs no hurt
     * call of its own. Callers must prove that native mutation before invoking it.
     */
    public static void excludeHistoricalCredit(LivingEntity victim) {
        if (victim instanceof ArsEngineCreditCarrier carrier) excludeCarrierCredit(carrier);
    }

    public static boolean changedPositiveFire(int before, int after) {
        return after > 0 && before != after;
    }

    public static boolean isDamageStatus(MobEffectInstance effect) {
        return effect != null && (effect.getEffect() == MobEffects.WITHER || effect.getEffect() == MobEffects.POISON);
    }

    /** Accepted unowned native Ars damage must not leave older human credit reusable. */
    public static void excludeAfterAcceptedDamage(Entity victim, boolean accepted) {
        if (!accepted || !(victim instanceof LivingEntity living)) return;
        try { excludeHistoricalCredit(living); }
        catch (RuntimeException | LinkageError | StackOverflowError failure) {
            AdapterFailSafe.report(AdapterKey.ARS_CAST, "bridge:ArsEngineCreditGuard.excludeAfterAcceptedDamage", failure);
        }
    }

    static boolean permitsCarrierCredit(ArsEngineCreditCarrier carrier) {
        return !carrier.eventtrail$arsEngineCreditExcluded();
    }

    static void observeClassification(ArsEngineCreditCarrier carrier, boolean arsSource) {
        carrier.eventtrail$setArsEngineCreditExcluded(arsSource);
    }

    static void excludeCarrierCredit(ArsEngineCreditCarrier carrier) {
        carrier.eventtrail$setArsEngineCreditExcluded(true);
    }
}
