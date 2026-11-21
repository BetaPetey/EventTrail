package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.resources.ResourceLocation;

/**
 * Fix 4c (Owner decision 2026-11-14): the Actually Additions Atomic Reconstructor's Death lens kills with an
 * entityless damage source of its own type. Minecraft's kill credit can still name the last player who hit the mob,
 * which the death bridge would record as ENGINE_KILL_CREDIT. A death whose final damage is that exact type keeps no
 * player credit.
 *
 * <p>Keyed on the one registry id only: every other damage type, including every vanilla entityless type (lava,
 * fall, fire, drowning, the void, cactus, magic, wither), keeps today's engine credit. No Actually Additions class
 * is named or loaded, so no hook is needed: the final damage source is already in hand at the bridge. Like the
 * Iron's and Ars guards it only withholds credit and keeps no actor, source, timestamp or history.</p>
 */
public final class AtomicReconstructorCreditGuard {
    static final ResourceLocation ATOMIC_RECONSTRUCTOR =
            ResourceLocation.fromNamespaceAndPath("actuallyadditions", "atomicreconstructor");

    private AtomicReconstructorCreditGuard() {
    }

    /** False only when the death's final damage type is exactly {@code actuallyadditions:atomicreconstructor}. */
    public static boolean permitsEngineCredit(ResourceLocation finalDamageTypeId) {
        return !ATOMIC_RECONSTRUCTOR.equals(finalDamageTypeId);
    }
}
