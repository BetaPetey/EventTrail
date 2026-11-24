package io.github.betapetey.eventtrail.adapters;

import io.github.betapetey.eventtrail.EventTrailIds;
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
 * Tracker catch-all (Owner decision A, 11-14): a menu that an optional adapter family recognized but whose item moves
 * EventTrail did not record (its access OPEN row may still have been written). Nothing here changes the open or what
 * is recorded; the miss is counted per family and per path. GF-WARNINGS (B47): the first miss of each family, path
 * and cause after a server start writes one WARN naming that cause (at most {@value #MAX_WARNED_CAUSES_PER_FAMILY}
 * causes per family; past that a new cause is only counted). Later misses of the same family, path and cause are only
 * counted (status output) until the next server start or stop. Nothing is thrown short of a
 * {@link VirtualMachineError}, which propagates unchanged.
 */
public final class AdapterFamilyMisses {
    /** B47: the causes of one family that may each write one WARN per server run. */
    static final int MAX_WARNED_CAUSES_PER_FAMILY = 16;
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private AdapterFamilyMisses() {
    }

    /** How a recognized menu's item moves went unrecorded. */
    public enum Path {
        /** The registry refused the selection: AMBIGUOUS, UNSUPPORTED_VERSION, OFF, a match failure, two matches. */
        REFUSED("selection refused"),
        /** The family's adapter was selected (SUPPORTED), but the open ended with no transaction session. */
        UNRESOLVED("selected, then not resolved");

        private final String label;

        Path(String label) {
            this.label = label;
        }
    }

    /** One family's misses since the server started. */
    public record Counts(long refused, long unresolved) {
    }

    /** Counts one miss of {@code key}; its first miss with this path and cause since the server started also warns. */
    public static void miss(AdapterKey key, Path path, String reason) {
        try {
            if (key != null && path != null) ledger.miss(key, path, reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The catch-all only counts and warns; short of a VirtualMachineError it never reaches into the open.
        }
    }

    /** The families with at least one miss since the server started, in key order. */
    public static Map<AdapterKey, Counts> counts() {
        return ledger.counts();
    }

    /**
     * A fresh ledger (server start and stop): the counts restart at zero and each family and cause may warn once
     * again. GF-WARNINGS: the recording-gap ledger ({@link RecordingGaps}) restarts with it, at the same two points.
     */
    public static void reset() {
        ledger = new Ledger();
        RecordingGaps.reset();
    }

    /**
     * Test seam: a fresh ledger, and the WARN lines of this and every later ledger go to {@code sink} instead of the
     * log ({@code null} restores the log). Other test packages reach it through {@code AdapterFamilyMissesTestSupport}.
     */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        reset();
    }

    static String message(AdapterKey key, Path path, String reason) {
        return OptionalIntegrationCompatibility.LOG_MARKER + " " + key.displayName() + " (" + key.modId()
                + "): items moved in a menu its adapter recognized are not recorded (" + path.label + ": "
                + EventTrailDiagnostics.sanitizeLogValue(reason, MissingAdapterMemberException.MAX_MEMBER_TEXT)
                        .replace('§', '?')
                + "). The menu still works. Further misses of this adapter with this cause are only counted, in the"
                + " status command's output, until the server restarts.";
    }

    private static final class Ledger {
        private final Map<AdapterKey, AtomicLongArray> counts = new ConcurrentHashMap<>();
        private final Map<AdapterKey, Set<String>> warned = new ConcurrentHashMap<>();

        void miss(AdapterKey key, Path path, String reason) {
            counts.computeIfAbsent(key, ignored -> new AtomicLongArray(Path.values().length))
                    .incrementAndGet(path.ordinal());
            Set<String> causes = warned.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
            String cause = path.name() + "|" + reason;
            if (causes.size() < MAX_WARNED_CAUSES_PER_FAMILY && causes.add(cause)) {
                warnings.accept(message(key, path, reason));
            }
        }

        Map<AdapterKey, Counts> counts() {
            Map<AdapterKey, Counts> copy = new EnumMap<>(AdapterKey.class);
            counts.forEach((key, total) -> copy.put(key, new Counts(
                    total.get(Path.REFUSED.ordinal()), total.get(Path.UNRESOLVED.ordinal()))));
            return Collections.unmodifiableMap(copy);
        }
    }
}
