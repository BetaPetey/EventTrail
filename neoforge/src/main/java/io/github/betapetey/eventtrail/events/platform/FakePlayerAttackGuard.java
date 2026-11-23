package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Fix 4a (Owner decision 2026-11-14): a kill made inside a FakePlayer's own {@code Player.attack} is a device action,
 * even when the mod wrote a real player into the damage source (the Advanced Peripherals Smart Chestplate names its
 * wearer as attacker and direct entity).
 *
 * <p>The attack scope is presence-only: a per-thread depth count. No actor, source, entity, timestamp or history is
 * kept, so the scope cannot name anyone; it only withholds. The per-victim exclusion bit (set at the head of every
 * {@code setLastHurtByMob}) stops the same hit from becoming the real player's engine kill credit later, in the style
 * of {@code ArsEngineCreditGuard}. A FakePlayer's own credit (the FakePlayer itself as attacker) keeps today's
 * synthetic-player path; only a real player named inside a FakePlayer attack is affected.</p>
 */
public final class FakePlayerAttackGuard {
    private static final EntityKillAttribution DEVICE_KILL =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.DEVICE_OR_AUTOMATION);

    /** FakePlayer attacks open on this thread; {@code null} outside every one. */
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();

    private FakePlayerAttackGuard() {
    }

    /**
     * Called first in the wrapped {@code Player.attack}. True only for a FakePlayer attacker; the caller must then
     * call {@link #exit()} in a {@code finally}. Never throws: an unreadable attacker opens no scope.
     */
    public static boolean enter(Player attacker) {
        try {
            if (attacker == null) return false;
            if (enterIfSynthetic(attacker.getClass())) return true;
            // GF-FAKEPLAYER-ID (B51): a ServerPlayer that is not a FakePlayer but is not the PlayerList's own object for
            // its UUID (a mod's fake) attacks as a device too. The scope only withholds credit; isRealPlayer below
            // stays type-only, so a source naming any non-FakePlayer inside a device attack is still withheld.
            return attacker instanceof ServerPlayer && !NeoForgePlayerProvenance.isAdmittedPlayer(attacker)
                    && openScope();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return false;
        }
    }

    static boolean enterIfSynthetic(Class<?> attackerType) {
        if (!NeoForgePlayerProvenance.isSyntheticType(attackerType)) return false;
        return openScope();
    }

    private static boolean openScope() {
        int[] depth = DEPTH.get();
        if (depth == null) {
            depth = new int[1];
            DEPTH.set(depth);
        }
        depth[0]++;
        return true;
    }

    /** Closes one scope opened by a true {@link #enter}; the last close leaves nothing behind on the thread. */
    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth == null) return;
        depth[0]--;
        if (depth[0] <= 0) DEPTH.remove();
    }

    static boolean active() {
        int[] depth = DEPTH.get();
        return depth != null && depth[0] > 0;
    }

    /**
     * For the death bridge, before every other source rule: inside a FakePlayer attack, a source whose direct or
     * causing entity is a real player is a device kill that names nobody. Empty otherwise (today's path).
     */
    public static Optional<EntityKillAttribution> resolveDamage(Entity direct, Entity causing) {
        if (!active()) return Optional.empty();
        try {
            return isRealPlayer(direct) || isRealPlayer(causing) ? Optional.of(DEVICE_KILL) : Optional.empty();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            // Inside a FakePlayer attack an unreadable source cannot be trusted to name a player.
            return Optional.of(DEVICE_KILL);
        }
    }

    /**
     * False while a FakePlayer attack is open on this thread, or when the victim's latest last-attacker assignment
     * named a real player inside one. A victim without the carrier keeps today's contract.
     */
    public static boolean permitsEngineCredit(LivingEntity victim) {
        if (active()) return false;
        return !(victim instanceof FakePlayerEngineCreditCarrier carrier) || permitsCarrierCredit(carrier);
    }

    /**
     * Called at the head of every {@code setLastHurtByMob}, including null and same-attacker updates: excluded exactly
     * when a FakePlayer attack is assigning a real player. Any assignment outside one clears the bit.
     */
    public static void observeCreditAssignment(FakePlayerEngineCreditCarrier carrier, LivingEntity attacker) {
        boolean excluded = true;
        try {
            excluded = active() && isRealPlayer(attacker);
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // Observation cannot interrupt the setter; an unreadable assignment cannot make credit trustworthy.
        }
        observeClassification(carrier, excluded);
    }

    static boolean isRealPlayer(Entity entity) {
        return entity instanceof Player player && !NeoForgePlayerProvenance.isSynthetic(player);
    }

    static boolean permitsCarrierCredit(FakePlayerEngineCreditCarrier carrier) {
        return !carrier.eventtrail$fakePlayerEngineCreditExcluded();
    }

    static void observeClassification(FakePlayerEngineCreditCarrier carrier, boolean excluded) {
        carrier.eventtrail$setFakePlayerEngineCreditExcluded(excluded);
    }
}
