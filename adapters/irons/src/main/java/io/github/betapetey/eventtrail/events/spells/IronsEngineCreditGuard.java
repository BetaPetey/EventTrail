package io.github.betapetey.eventtrail.events.spells;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Prevents an Iron's hit from later acquiring player attribution through
 * Minecraft's entityless last-attacker fallback. This is exclusion evidence,
 * never cast authority: it contains no player identity, timestamp or history.
 */
public final class IronsEngineCreditGuard {
    private IronsEngineCreditGuard() {
    }

    public static boolean permitsEngineCredit(LivingEntity victim) {
        // The carrier is installed only when Iron's is present. Its absence
        // leaves the existing vanilla engine-credit contract unchanged.
        return !(victim instanceof IronsEngineCreditCarrier carrier)
                || permitsCarrierCredit(carrier);
    }

    /** Called at every actual update, including null and same-player updates. */
    public static void clear(IronsEngineCreditCarrier carrier) {
        carrier.eventtrail$setIronsEngineCreditExcluded(false);
    }

    /** Called after the exact hurt -> last-attacker assignment has completed. */
    public static void observeCreditSource(
            IronsEngineCreditCarrier carrier, DamageSource source) {
        boolean excluded = true;
        try {
            excluded = IronsSpellAuditRuntime.isIronSource(source);
        } catch (Throwable failure) {
            // An unavailable classification cannot make historical credit safe.
            // Observation failure must not interrupt Minecraft's hurt method. Any
            // throwable, an Error included, leaves the exclusion on (R2-inner:
            // fail-conservative) and is reported to the fail-safe.
            AdapterFailSafe.report(AdapterKey.IRONS, "bridge:IronsEngineCreditGuard.observeCreditSource", failure);
        }
        observeClassification(carrier, excluded);
    }

    static boolean permitsCarrierCredit(IronsEngineCreditCarrier carrier) {
        return !carrier.eventtrail$ironsEngineCreditExcluded();
    }

    static void observeClassification(IronsEngineCreditCarrier carrier, boolean ironSource) {
        carrier.eventtrail$setIronsEngineCreditExcluded(ironSource);
    }
}
