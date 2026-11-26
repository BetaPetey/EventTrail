package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailIds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Kill credit A and B (design-05c section 5): every kill credit withheld because the kill was not the player's own
 * action is counted here per reason, so no withholding is silent. Withholding is normal, so no log line is written,
 * except SCOPE_LEAK's one warning per run: left-over tracking is a bug, not a withholding.
 *
 * <p>A reason is counted only when the credit would have gone to a real player (a zombie wearing Thorns armour counts
 * nothing). Later parts of the lane add their reasons here. Nothing is thrown into the game short of a
 * {@link VirtualMachineError}.</p>
 */
public final class OwnActionCreditGuard {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;

    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private OwnActionCreditGuard() {
    }

    /** Why a kill credit was not given. */
    public enum Reason {
        /** Thorns or other hit-back (design-05c section 2, all three signals): the wearer did not act. */
        RETALIATION("kills by Thorns or other hit-back"),
        /**
         * A new credit check threw, or could not be made because a hit-back frame push was refused at its cap (lead
         * ruling 1 of fold 01): the credit was withheld by a failure, not by a rule.
         */
        PROOF_FAILURE("kill credit checks that failed"),
        /** A per-thread tracking stack was found not empty, closed out of order, or refused a push at its cap. */
        SCOPE_LEAK("credit checks reset after left-over tracking"),
        /**
         * Item 2 F4 A (KL-3 4a): inside one real player's own attack (a listed bot's, say), the damage named another
         * real player, who did not act. Label: DRAFT TEXT for the Owner.
         */
        OTHER_PLAYER_IN_ATTACK("kills inside one player's attack whose damage named another player"),
        /**
         * Item 2 F4 A (KL-4): the killing damage source was first used under a hit-back, another real player's
         * attack, a device or a command, or where that check could not be read, and was used again after that ended
         * (a revive or downed mod, say). Label: the Owner's Q429 (a) text.
         */
        REDELIVERED_SOURCE("kills by damage first used under a hit-back, another player's attack, a device, a command,"
                + " or a check that could not be read");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        /** Short status text. */
        public String label() {
            return label;
        }
    }

    /** Counts one withheld credit; never throws into the death path. */
    public static void count(Reason reason) {
        try {
            if (reason != null) ledger.count(reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Counting never changes the withheld result.
        }
    }

    /**
     * Counts one SCOPE_LEAK for the named stack; the first one in a run also writes one warning line naming the stack
     * (design-05c 3.2.5 rule 4, section 5).
     */
    static void scopeLeak(String stackName) {
        try {
            Ledger current = ledger;
            current.count(Reason.SCOPE_LEAK);
            if (current.leakWarned.compareAndSet(false, true)) {
                warnings.accept("EventTrail cleared left-over kill-credit tracking (" + stackName + "). Kill credits"
                        + " under it were withheld, never given. Further clears are only counted, in the status"
                        + " command's output, until the server restarts.");
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Counting never interrupts the game.
        }
    }

    /** The reasons with at least one withheld credit since the server started, in reason order. */
    public static Map<Reason, Long> counts() {
        return ledger.counts();
    }

    /**
     * Item 2 F4 A (the Owner's Q427 A): one DIRECT_PLAYER kill credit given outside that player's own click or key
     * press. Not a withholding: the credit is kept. The Owner's figure for the option B decision. Never throws.
     */
    static void countDirectPlayerOutsideOwnInput() {
        try {
            ledger.directOutsideOwnInput.incrementAndGet();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Counting never interrupts the game.
        }
    }

    /** DIRECT_PLAYER kill credits given outside that player's own click or key press since the server started. */
    public static long directPlayerCreditsOutsideOwnInput() {
        return ledger.directOutsideOwnInput.get();
    }

    /** A fresh ledger (server start): the counts restart at zero and SCOPE_LEAK may warn once again. */
    public static void resetAtServerStart() {
        ledger = new Ledger();
    }

    /** Test seam: a fresh ledger, and the warning goes to {@code sink} instead of the log ({@code null} restores it). */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        resetAtServerStart();
    }

    private static final class Ledger {
        private final AtomicLongArray counts = new AtomicLongArray(Reason.values().length);
        private final AtomicBoolean leakWarned = new AtomicBoolean();
        private final AtomicLong directOutsideOwnInput = new AtomicLong();

        void count(Reason reason) {
            counts.incrementAndGet(reason.ordinal());
        }

        Map<Reason, Long> counts() {
            Map<Reason, Long> copy = new EnumMap<>(Reason.class);
            for (Reason reason : Reason.values()) {
                long total = counts.get(reason.ordinal());
                if (total > 0L) copy.put(reason, total);
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
