package io.github.betapetey.eventtrail.compat;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SOPH-LABELS (review-01 MAJOR-2): container lines whose name could not be read the usual way. Nothing here changes
 * what is recorded: every such row IS written, under the block's own name (with its custom name when that was
 * readable). Counted since the server started, in two kinds: a name that fell back on its own, and a name read while a
 * naming helper was switched off (until the server restarts). The first case of each site and kind after a server
 * start writes one WARN; later ones are only counted, and the status command shows the counts while they are above
 * zero. Holds no optional-mod types, so it loads on every server.
 *
 * <p>Never throws. A {@link StackOverflowError} or {@link OutOfMemoryError} is only counted: nothing is allocated for
 * it and no line is written (as {@code AdapterFailSafe.report} does).</p>
 */
public final class ContainerLabelFallbacks {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static final AtomicLong SERVER_RUN = new AtomicLong();
    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private ContainerLabelFallbacks() {
    }

    /** Why a container line was recorded with the block's own name. */
    public enum Kind {
        /** Reading this one name failed (or gave nothing usable); later names are read again. */
        ONE_NAME,
        /** The naming helper was switched off (a missing class or member) until the server restarts. */
        SWITCHED_OFF
    }

    /** The counts since the server started. */
    public record Counts(long oneName, long switchedOff) {
        public long total() {
            return oneName + switchedOff;
        }
    }

    /** One name of {@code owner} read at {@code site} fell back to the block's own name ({@code failure} may be null). */
    public static void fellBack(String owner, String site, Throwable failure) {
        record(Kind.ONE_NAME, owner, site, failure);
    }

    /**
     * One name of {@code owner} read at {@code site} while its naming helper is switched off. The call that switches it
     * off passes the failure; the later ones pass null.
     */
    public static void switchedOff(String owner, String site, Throwable failure) {
        record(Kind.SWITCHED_OFF, owner, site, failure);
    }

    private static void record(Kind kind, String owner, String site, Throwable failure) {
        try {
            Ledger current = ledger;
            current.count(kind);
            if (failure instanceof StackOverflowError || failure instanceof OutOfMemoryError) return;
            if (current.firstOf(kind, owner + "|" + site)) warnings.accept(message(kind, owner, site, failure));
        } catch (Throwable ignored) {
            // Counting and warning never reach into the open, the click or the row.
        }
    }

    /** The counts since the server started. */
    public static Counts counts() {
        return ledger.counts();
    }

    /** A number that changes at every server start: a helper switched off in an earlier run is on again. */
    public static long serverRun() {
        return SERVER_RUN.get();
    }

    /** Server start: the counts restart at zero, each site may warn once again, and every helper is on again. */
    public static void resetAtServerStart() {
        SERVER_RUN.incrementAndGet();
        ledger = new Ledger();
    }

    /** Test seam: a fresh ledger, and every later WARN goes to {@code sink} instead of the log (null restores it). */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        ledger = new Ledger();
    }

    /** The status command's line, only while some line was recorded with the block's own name. */
    public static List<String> statusLines() {
        return statusLines(counts());
    }

    static List<String> statusLines(Counts counts) {
        if (counts == null || counts.total() <= 0L) return List.of();
        return List.of("§7Container lines recorded with the block's own name since server start: §f"
                + counts.oneName() + " fell back for one name, " + counts.switchedOff()
                + " while a naming helper was switched off");
    }

    static String message(Kind kind, String owner, String site, Throwable failure) {
        String who = EventTrailDiagnostics.sanitizeLogValue(owner, 96).replace('§', '?');
        String where = EventTrailDiagnostics.sanitizeLogValue(site, 160).replace('§', '?');
        String why = failure == null ? "no usable name" : failureText(failure);
        if (kind == Kind.SWITCHED_OFF) {
            return OptionalIntegrationCompatibility.LOG_MARKER + " " + who + ": container naming at " + where
                    + " is switched off until the server restarts (" + why + "). Every row is still recorded, with the"
                    + " block's own name. Further cases are only counted, in the status command's output.";
        }
        return OptionalIntegrationCompatibility.LOG_MARKER + " " + who + ": a container name could not be read at "
                + where + " (" + why + "); the row was recorded with the block's own name. Further cases are only"
                + " counted, in the status command's output, until the server restarts.";
    }

    private static String failureText(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String text = type == null || type.isBlank() ? "UnknownFailure" : type;
        // A missing class or member names only that member; other messages are never copied (they may hold paths).
        if (failure instanceof LinkageError && failure.getMessage() != null) {
            text = text + ": " + failure.getMessage();
        }
        return EventTrailDiagnostics.sanitizeLogValue(text, MissingAdapterMemberException.MAX_MEMBER_TEXT)
                .replace('§', '?');
    }

    private static final class Ledger {
        private final AtomicLong oneName = new AtomicLong();
        private final AtomicLong switchedOff = new AtomicLong();
        private final Set<String> warnedOneName = ConcurrentHashMap.newKeySet();
        private final Set<String> warnedSwitchedOff = ConcurrentHashMap.newKeySet();

        void count(Kind kind) {
            if (kind == Kind.SWITCHED_OFF) switchedOff.incrementAndGet();
            else oneName.incrementAndGet();
        }

        boolean firstOf(Kind kind, String site) {
            return (kind == Kind.SWITCHED_OFF ? warnedSwitchedOff : warnedOneName).add(site);
        }

        Counts counts() {
            return new Counts(oneName.get(), switchedOff.get());
        }
    }
}
