package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Command credit guard (B64 credit-guard slice, review-01 1(a), 1(b), F2, F3): no player is credited with a kill, and
 * no death row names a player or his weapon, for damage a command built. Vanilla keeps no author for a command block,
 * function or script, and a command can write any player into a damage source ({@code /damage ... by <player>}) or
 * finish a victim a player hit a moment before ({@code /kill}).
 *
 * <p>The command scope is presence-only: a per-thread depth count around {@code Commands.executeCommandInContext},
 * which runs every queued command (nested functions and forks included) before it returns. No source, root, entity,
 * timestamp or history is kept. Inside the scope every real-player kill attribution is withheld; outside it, an
 * entityless death's engine credit is withheld when the victim's latest last-attacker assignment named a real player
 * inside a command (the per-victim bit set at the head of every {@code setLastHurtByMob}, cleared by any later
 * assignment outside a scope). Commands a player typed are included: the scope does not tell them from commands a mod
 * or a quest ran as that player, so an op's own {@code /kill} credit is withheld too (KL). Damage that lands after
 * the command returns is outside the scope (KL).</p>
 *
 * <p>Every withheld credit is counted per reason (status output), and the first of each reason after a server start
 * writes one WARN naming the reason. It only withholds: it never grants credit and never names anyone. Nothing is
 * thrown into the game short of a {@link VirtualMachineError}.</p>
 */
public final class CommandCreditGuard {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static final EntityKillAttribution COMMAND_KILL =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.DEVICE_OR_AUTOMATION);
    private static final EntityKillAttribution ENTITYLESS_DEATH =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.ENVIRONMENT);

    /** Command executions open on this thread; {@code null} outside every one. */
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>();

    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private CommandCreditGuard() {
    }

    /** Why a kill credit was withheld. */
    public enum Reason {
        /**
         * Inside a command, a DIRECT_PLAYER credit whose killing damage names one real player as both its direct and
         * its causing entity, from no spell rule and not Thorns: {@code /damage ... by <player>}, or a command that
         * made the player attack. Synthetic actors are never counted here (or anywhere).
         */
        COMMAND_NAMED_PLAYER("a command's damage named the player as the attacker",
                "inside a command, the killing damage named that player as both its direct and its causing attacker"
                        + " (for example /damage ... by <player> in a command block, a function or a script, or a"
                        + " command that made the player attack)"),
        /** Inside a command, an entityless death would have taken the engine credit of an earlier hit. */
        COMMAND_ENTITYLESS_DEATH("an entityless death inside a command after a hit",
                "the victim died of damage with no attacker inside a command (for example /kill, or /damage without"
                        + " \"by\") within 5 seconds of a hit credited to that player, so that hit did not kill it"),
        /** Outside a command, an entityless death after a hit credited to a real player inside a command. */
        LATER_DEATH_AFTER_COMMAND_HIT("a later entityless death after a hit inside a command",
                "the victim's last hit credited to that player happened inside a command (for example /damage ... by"
                        + " <player>), and it later died of damage with no attacker, such as fire, lava or a fall"),
        /**
         * Inside a command, every other real-player credit: a projectile or TNT credit, a DIRECT_PLAYER from an Iron's
         * or Ars spell rule or from Thorns, and a credit whose screening failed. Thorns outside a command is the
         * retaliation rule's (RetaliationScope, kill credit B), which credits nobody.
         */
        OTHER_COMMAND_CREDIT("other player credit inside a command",
                "the kill happened while a command was running, and its damage did not name that player as both"
                        + " direct and causing attacker (for example a projectile, TNT, a spell or Thorns damage"
                        + " resolved inside the command); damage made while a command runs is not credited to a"
                        + " player");

        private final String label;
        private final String cause;

        Reason(String label, String cause) {
            this.label = label;
            this.cause = cause;
        }

        /** Short status text. */
        public String label() {
            return label;
        }
    }

    /**
     * Opens one command scope on this thread. True only when it opened one; the caller must then call {@link #exit()}
     * in a {@code finally}, and must not call it after a false return.
     */
    public static boolean enter() {
        try {
            int[] depth = DEPTH.get();
            if (depth == null) {
                depth = new int[1];
                DEPTH.set(depth);
            }
            depth[0]++;
            return true;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A scope that could not open withholds nothing; the command itself runs unchanged.
            return false;
        }
    }

    /** Closes one scope; the last close leaves nothing behind on the thread. A stray close cannot go negative. */
    public static void exit() {
        try {
            int[] depth = DEPTH.get();
            if (depth == null) return;
            depth[0]--;
            if (depth[0] <= 0) DEPTH.remove();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Closing never interrupts the command's own result or exception.
        }
    }

    static boolean active() {
        int[] depth = DEPTH.get();
        return depth != null && depth[0] > 0;
    }

    /**
     * For capture() (review-01 F3): inside a command scope, a source naming a real player as direct or causing entity
     * is redacted with the Fix 4a form and claims no weapon. False outside every scope (today's path).
     */
    public static boolean redactsSource(Entity direct, Entity causing) {
        try {
            if (!active()) return false;
            return FakePlayerAttackGuard.isRealPlayer(direct) || FakePlayerAttackGuard.isRealPlayer(causing);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            // A failure redacts: an unreadable source cannot be trusted to name a player.
            return safeActive();
        }
    }

    /**
     * For the death bridge: true when the killing damage names one real player as both its direct and its causing
     * entity and is not Thorns damage (Thorns names the wearer it answered for, not an attacker). The bridge adds that
     * no Iron's or Ars spell rule claimed the source.
     */
    public static boolean namesPlayerAsAttacker(Entity direct, Entity causing, ResourceLocation damageTypeId) {
        return direct != null
                && direct == causing
                && FakePlayerAttackGuard.isRealPlayer(direct)
                && !DamageTypes.THORNS.location().equals(damageTypeId);
    }

    /**
     * The last step of the death bridge's attribution: returns {@code resolved} unchanged unless it credits a real
     * player for damage made while a command runs (or for an entityless death after a hit credited inside one), and
     * then a withheld attribution that names nobody, counted per reason. A synthetic actor's credit is returned
     * unchanged and uncounted: the normalizer already writes no kill row for it. {@code commandNamedAttacker} is read
     * only for an in-scope DIRECT_PLAYER and tells COMMAND_NAMED_PLAYER from OTHER_COMMAND_CREDIT.
     */
    public static EntityKillAttribution screen(
            LivingEntity victim, EntityKillAttribution resolved, BooleanSupplier commandNamedAttacker) {
        try {
            if (resolved == null || !resolved.provenance().accountable()) return resolved;
            if (resolved.accountablePlayer().actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER) return resolved;
            boolean engineCredit = resolved.provenance() == EntityKillAttribution.ProvenanceKind.ENGINE_KILL_CREDIT;
            if (active()) {
                if (engineCredit) return withhold(Reason.COMMAND_ENTITYLESS_DEATH, ENTITYLESS_DEATH);
                boolean named = resolved.provenance() == EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER
                        && readNamed(commandNamedAttacker);
                return withhold(named ? Reason.COMMAND_NAMED_PLAYER : Reason.OTHER_COMMAND_CREDIT, COMMAND_KILL);
            }
            if (engineCredit
                    && victim instanceof CommandEngineCreditCarrier carrier
                    && carrier.eventtrail$commandEngineCreditExcluded()) {
                return withhold(Reason.LATER_DEATH_AFTER_COMMAND_HIT, ENTITYLESS_DEATH);
            }
            return resolved;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            // An unreadable decision: an unaccountable result stays; inside a command a credit is withheld and counted;
            // outside one today's result is kept.
            if (resolved == null || !resolved.provenance().accountable()) return resolved;
            return safeActive() ? withhold(Reason.OTHER_COMMAND_CREDIT, COMMAND_KILL) : resolved;
        }
    }

    private static boolean readNamed(BooleanSupplier commandNamedAttacker) {
        try {
            return commandNamedAttacker != null && commandNamedAttacker.getAsBoolean();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            // Not known to be named: the credit is still withheld, under the wider reason.
            return false;
        }
    }

    /**
     * Called at the head of every {@code setLastHurtByMob}, including null and same-attacker updates: excluded exactly
     * when a command scope is open and the attacker is a real player. Any assignment outside a scope clears the bit.
     */
    public static void observeCreditAssignment(CommandEngineCreditCarrier carrier, LivingEntity attacker) {
        if (carrier == null) return;
        boolean excluded;
        try {
            excluded = active() && FakePlayerAttackGuard.isRealPlayer(attacker);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // An unreadable assignment inside a command cannot make credit trustworthy.
            excluded = safeActive();
        }
        try {
            carrier.eventtrail$setCommandEngineCreditExcluded(excluded);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Observation never interrupts the setter.
        }
    }

    /** The reasons with at least one withheld credit since the server started, in reason order. */
    public static Map<Reason, Long> counts() {
        return ledger.counts();
    }

    /** A fresh ledger (server start): the counts restart at zero and each reason may warn once again. */
    public static void resetAtServerStart() {
        ledger = new Ledger();
    }

    /** Test seam: a fresh ledger, and WARN lines go to {@code sink} instead of the log ({@code null} restores it). */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        resetAtServerStart();
    }

    static String message(Reason reason) {
        return "EventTrail did not credit a player with a kill: " + reason.cause + ". The victim's death row, if it"
                + " has one, names no player and no weapon for that damage. Commands a player typed are included,"
                + " because EventTrail does not tell them apart from commands a mod, a quest or a function ran as that"
                + " player. Further credits withheld for this reason are only counted, in the status command's"
                + " output, until the server restarts.";
    }

    private static EntityKillAttribution withhold(Reason reason, EntityKillAttribution withheld) {
        try {
            ledger.withheld(reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Counting never changes the withheld result.
        }
        return withheld;
    }

    private static boolean safeActive() {
        try {
            return active();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static final class Ledger {
        private final AtomicLongArray counts = new AtomicLongArray(Reason.values().length);
        private final AtomicIntegerArray warned = new AtomicIntegerArray(Reason.values().length);

        void withheld(Reason reason) {
            counts.incrementAndGet(reason.ordinal());
            if (warned.compareAndSet(reason.ordinal(), 0, 1)) warnings.accept(message(reason));
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
