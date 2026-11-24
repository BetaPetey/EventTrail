package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Shown causes on rows written (GF-ONPLAYER design 4.5, 4.8): the shared counters for rows that WERE written with a
 * marked or unresolved shown cause, for callers inside and outside an on-player bracket. Nothing here is a refusal
 * count: a gap or an unbalanced step stays on its own lane's refusal counter, never both.
 *
 * <p>The pattern is {@code AdapterFamilyMisses}: per-family counts in a ledger; the first call of each kind for a family
 * after a server start writes one WARN naming the hook site, and later calls are only counted (status output) until
 * {@link #reset()} at the next server start or stop. Nothing is thrown short of a {@link VirtualMachineError}, which
 * propagates unchanged; nothing here changes what was written. A call without a family is not counted.</p>
 */
public final class ShownCauseCounters {
    /** The longest hook site a WARN names. */
    static final int MAX_SITE_TEXT = 160;
    private static final int MARKED_STEPS = 0;
    private static final int MARKED_ROWS = 1;
    private static final int UNRESOLVED_ROWS = 2;
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private ShownCauseCounters() {
    }

    /** One family's shown-cause counts since the server started. */
    public record Counts(long markedSteps, long markedRows, long unresolvedRows) {
    }

    /**
     * One published step whose rows carry {@link ShownCause#unidentifiedUpgrade()}. Call exactly once per step, after
     * the writer admitted the step's rows; {@code rows} = the step's logical rows (before any part split; a negative
     * value counts as 0). The family's first call since server start writes one WARN naming {@code site}, the hook's
     * HookGuard site name.
     */
    public static void markedStepWritten(AdapterKey family, String site, int rows) {
        try {
            if (family != null) ledger.markedStep(family, site, rows);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The counters only count and warn; short of a VirtualMachineError they never reach into the write.
        }
    }

    /**
     * Rows written with a ShownCause whose name did not resolve ({@code resolved() == false}): "Cause name not
     * resolved, written with the id". The family's first call since server start writes one WARN naming {@code site}.
     */
    public static void nameUnresolvedWritten(AdapterKey family, String site, int rows) {
        try {
            if (family != null) ledger.unresolved(family, site, rows);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // As above: counting never changes what was written.
        }
    }

    /** The families with at least one count since the server started, in key order; a read-only copy. */
    public static Map<AdapterKey, Counts> counts() {
        return ledger.counts();
    }

    /** A fresh ledger (server start and stop): the counts restart at zero and each family may warn once again. */
    public static void reset() {
        ledger = new Ledger();
    }

    /**
     * Test seam: a fresh ledger, and the WARN lines of this and every later ledger go to {@code sink} instead of the
     * log ({@code null} restores the log).
     */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        reset();
    }

    static String markedMessage(AdapterKey family, String site) {
        return OptionalIntegrationCompatibility.LOG_MARKER + " " + family.displayName() + " (" + family.modId()
                + "): rows of the player's own action were written with the cause \"" + ShownCause.UNIDENTIFIED_TEXT
                + "\", because the upgrade's name was not proven (" + siteText(site) + "). Further such steps of this"
                + " adapter are only counted, in the status command's output, until the server restarts.";
    }

    static String unresolvedMessage(AdapterKey family, String site) {
        return OptionalIntegrationCompatibility.LOG_MARKER + " " + family.displayName() + " (" + family.modId()
                + "): rows were written with a cause whose name did not resolve, shown with its id (" + siteText(site)
                + "). Further such rows of this adapter are only counted, in the status command's output, until the"
                + " server restarts.";
    }

    private static String siteText(String site) {
        return EventTrailDiagnostics.sanitizeLogValue(site, MAX_SITE_TEXT).replace('§', '?');
    }

    private static final class Ledger {
        private final Map<AdapterKey, AtomicLongArray> counts = new ConcurrentHashMap<>();
        private final Set<AdapterKey> warnedMarked = ConcurrentHashMap.newKeySet();
        private final Set<AdapterKey> warnedUnresolved = ConcurrentHashMap.newKeySet();

        void markedStep(AdapterKey family, String site, int rows) {
            AtomicLongArray total = row(family);
            total.incrementAndGet(MARKED_STEPS);
            total.addAndGet(MARKED_ROWS, Math.max(0, rows));
            if (warnedMarked.add(family)) warnings.accept(markedMessage(family, site));
        }

        void unresolved(AdapterKey family, String site, int rows) {
            row(family).addAndGet(UNRESOLVED_ROWS, Math.max(0, rows));
            if (warnedUnresolved.add(family)) warnings.accept(unresolvedMessage(family, site));
        }

        private AtomicLongArray row(AdapterKey family) {
            return counts.computeIfAbsent(family, ignored -> new AtomicLongArray(3));
        }

        Map<AdapterKey, Counts> counts() {
            Map<AdapterKey, Counts> copy = new EnumMap<>(AdapterKey.class);
            counts.forEach((family, total) -> copy.put(family, new Counts(
                    total.get(MARKED_STEPS), total.get(MARKED_ROWS), total.get(UNRESOLVED_ROWS))));
            return Collections.unmodifiableMap(copy);
        }
    }
}
