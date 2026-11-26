package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Item 2 F4 option A (KL-3 4a, the listed-bot case): which real player's own {@code Player.attack} is running on this
 * thread, the innermost one, for the synchronous extent of that call (PlayerAttackFrameMixin). Fix 4a's FakePlayer
 * scope opens only for a FakePlayer or an unlisted player; a bot on the player list (a Carpet bot, say) attacks as a
 * real player, and a mod can write another real player into that attack's damage source. Inside a real player's own
 * attack, a source naming any other real player is not that player's act, so it credits nobody.
 *
 * <ul>
 *   <li>{@link #resolveDamage}: the death bridge's rule, right after the retaliation rule (a hit-back inside an attack
 *       stays RETALIATION). Counted per withheld credit.</li>
 *   <li>{@link #namesAnotherPlayer}: the same test, counting nothing, for capture's redaction, the last-hit proof and
 *       the framed-source mark ({@link FramedSources}).</li>
 * </ul>
 *
 * <p>The status counter of DIRECT_PLAYER credits is not measured against this frame: the Owner's Q427 A measures
 * outside the player's own click or key press ({@link PlayerInputFrames#noteCredit}).</p>
 *
 * <p>The frame holds the attacker only while its call runs; {@link Frame#close} always restores the frame it replaced,
 * even after a failed {@code set} (the KL-60 rule). It only withholds and counts: it never grants credit. Nothing is
 * thrown into the game short of a {@link VirtualMachineError}.</p>
 */
public final class PlayerAttackFrames {
    private static final EntityKillAttribution OTHER_PLAYER_NAMED =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.DEVICE_OR_AUTOMATION);

    /** The innermost real player's attack running on this thread; {@code null} outside every one. */
    private static final ThreadLocal<Player> INNERMOST = new ThreadLocal<>();

    private static final Frame NONE = new Frame(null, null);

    private PlayerAttackFrames() {
    }

    /**
     * One attack's frame, made before the call and opened inside its {@code try}; {@link #close} in the
     * {@code finally}. A frame for anyone but a real player does nothing.
     */
    public static final class Frame {
        private final Player attacker;
        private final Player previous;

        private Frame(Player attacker, Player previous) {
            this.attacker = attacker;
            this.previous = previous;
        }

        /** Makes this attacker the innermost one. */
        public void open() {
            if (attacker == null) return;
            try {
                INNERMOST.set(attacker);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // A frame that could not open withholds nothing; close still restores the previous one.
            }
        }

        /** Restores the frame this one replaced, whether or not {@link #open} completed. */
        public void close() {
            if (attacker == null) return;
            try {
                if (previous == null) {
                    INNERMOST.remove();
                } else {
                    INNERMOST.set(previous);
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Closing never interrupts the attack's own result or exception.
            }
        }
    }

    /** The frame for {@code attacker}'s own attack: a real player only (not a FakePlayer). Never throws. */
    public static Frame frame(Player attacker) {
        try {
            if (!FakePlayerAttackGuard.isRealPlayer(attacker)) return NONE;
            return new Frame(attacker, INNERMOST.get());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    /** The innermost real player's attack on this thread, or {@code null}. Package-private for the tests. */
    static Player innermost() {
        return INNERMOST.get();
    }

    /**
     * True inside a real player's own attack when either source entity is another real player. Counts nothing. Any
     * failure inside an attack answers true (an unreadable source cannot be trusted to be the attacker's own).
     */
    public static boolean namesAnotherPlayer(Entity direct, Entity causing) {
        Player attacker = INNERMOST.get();
        if (attacker == null) return false;
        try {
            return other(direct, attacker) || other(causing, attacker);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return true;
        }
    }

    private static boolean other(Entity entity, Player attacker) {
        return entity != null && entity != attacker && FakePlayerAttackGuard.isRealPlayer(entity);
    }

    /**
     * The death bridge's rule, after the retaliation rule: inside one real player's attack, a source naming another
     * real player credits nobody and is counted. Empty otherwise (today's path).
     */
    static Optional<EntityKillAttribution> resolveDamage(Entity direct, Entity causing) {
        if (!namesAnotherPlayer(direct, causing)) return Optional.empty();
        OwnActionCreditGuard.count(OwnActionCreditGuard.Reason.OTHER_PLAYER_IN_ATTACK);
        return Optional.of(OTHER_PLAYER_NAMED);
    }

}
