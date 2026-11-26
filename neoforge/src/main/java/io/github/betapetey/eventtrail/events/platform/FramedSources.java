package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime;
import io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Item 2 F4 option A (KL-4): a damage source first used under a hit-back, a device or a command frame credits nobody
 * when a mod uses the same source object again after that frame ended (a revive or downed mod that stores the killing
 * source and applies it later, say). Without this, the later death outside every frame would read the stored source
 * as the named player's own hit.
 *
 * <ul>
 *   <li>At the head of every {@code LivingEntity.hurt} (FramedSourceHurtMixin), a source naming a real player is marked
 *       when a death by it right there would be withheld by a frame rule: the FakePlayer device scope, a command, a
 *       real player's attack naming another player ({@link PlayerAttackFrames}), or a hit-back signal or refused
 *       frame ({@link RetaliationScope#match}).</li>
 *   <li>At a death outside a command, a marked source credits nobody and its row stores no player and no weapon
 *       ({@link #resolveDeath}, after the frame rules, so a death inside its own frame keeps that frame's answer).
 *       Inside a command the command screen answers, as for any other source.</li>
 * </ul>
 *
 * <p>The mark is one bit on the source object; nothing else is kept. Vanilla makes a new source for every attack, arrow
 * and explosion, and its shared sources name no entity, so they are never marked. A mod that reuses one source object
 * for a player's later own hits loses those credits once the object was marked (a missing row, never a wrong one).
 * Nothing is thrown into the game short of a {@link VirtualMachineError}.</p>
 */
public final class FramedSources {
    private static final EntityKillAttribution REDELIVERED =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY);

    private FramedSources() {
    }

    /** The head of every {@code LivingEntity.hurt}: marks {@code source} when a frame rule holds for it now. */
    public static void observeHurt(LivingEntity victim, DamageSource source) {
        if (!(source instanceof FramedSourceCarrier carrier)) return;
        try {
            if (carrier.eventtrail$framedSource()) return;
            if (framedNow(victim, source)) carrier.eventtrail$markFramedSource();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            // Observation never interrupts the hurt step; an unreadable source is left unmarked.
        }
    }

    /**
     * Whether a death of {@code victim} by {@code source} right now would be withheld by a frame rule because the
     * source names a real player the frame says did not act. Counts nothing. Package-private for the tests.
     */
    static boolean framedNow(LivingEntity victim, DamageSource source) {
        Entity direct = source.getDirectEntity();
        Entity causing = source.getEntity();
        if (!FakePlayerAttackGuard.isRealPlayer(direct) && !FakePlayerAttackGuard.isRealPlayer(causing)) return false;
        if (FakePlayerAttackGuard.resolveDamage(direct, causing).isPresent()) return true;
        if (CommandCreditGuard.redactsSource(direct, causing)) return true;
        if (PlayerAttackFrames.namesAnotherPlayer(direct, causing)) return true;
        BooleanSupplier spellSource = () -> IronsSpellAuditRuntime.isIronSource(source)
                || ArsSpellAuditRuntime.isArsDamage(victim, source);
        return RetaliationScope.match(victim, direct, causing, false, false, typeId(source), spellSource).isPresent();
    }

    /** Whether {@code source} carries the mark. Counts nothing; false for an unreadable or unmarked source. */
    public static boolean marked(DamageSource source) {
        try {
            return source instanceof FramedSourceCarrier carrier && carrier.eventtrail$framedSource();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * The death bridge's rule, after the frame rules: outside a command, a marked source credits nobody and is counted
     * once. Empty otherwise (inside a command the command screen decides).
     */
    static Optional<EntityKillAttribution> resolveDeath(DamageSource source) {
        if (CommandCreditGuard.active() || !marked(source)) return Optional.empty();
        OwnActionCreditGuard.count(OwnActionCreditGuard.Reason.REDELIVERED_SOURCE);
        return Optional.of(REDELIVERED);
    }

    /**
     * Whether {@code attribution} is {@link #resolveDeath}'s withheld value, compared by identity: its row stores no
     * player identity, no player owner and no weapon (as RetaliationScope's two withheld values). Package-private.
     */
    static boolean withheld(EntityKillAttribution attribution) {
        return attribution == REDELIVERED;
    }

    /** The damage type's registry id, or null when it has none. */
    private static ResourceLocation typeId(DamageSource source) {
        return source.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
    }
}
