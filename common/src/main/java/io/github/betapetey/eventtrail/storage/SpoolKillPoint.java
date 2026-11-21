package io.github.betapetey.eventtrail.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * writer-spool design-02 13.3 (S-14): named points where a kill test parks the process so the parent can kill it
 * there. Off unless the test system property {@value #PROPERTY} is set (never in production): its value
 * {@code <point>@<n>|<signal file>} parks the thread on the n-th arrival at that point, after writing the point's
 * name to the signal file. A parked thread never returns; the test kills the JVM.
 */
final class SpoolKillPoint {
    static final String PROPERTY = "eventtrail.spool.testKillPoint";
    static final String INSERT_BATCH = "insert-batch";
    static final String BETWEEN_BATCHES = "between-batches";
    static final String REPLAY_TRANSACTION = "replay-transaction";
    static final String AFTER_TARGET_COMMIT = "after-target-commit";
    static final String DELETE = "delete";
    static final String SHRINK = "shrink";

    private static final String POINT;
    private static final int ARRIVAL;
    private static final Path SIGNAL;
    private static final AtomicInteger ARRIVALS = new AtomicInteger();

    static {
        String point = null;
        int arrival = 0;
        Path signal = null;
        String raw;
        try {
            raw = System.getProperty(PROPERTY);
        } catch (SecurityException denied) {
            raw = null;
        }
        if (raw != null) {
            int bar = raw.indexOf('|');
            int at = raw.indexOf('@');
            if (bar > 0 && at > 0 && at < bar) {
                try {
                    arrival = Integer.parseInt(raw.substring(at + 1, bar));
                    point = raw.substring(0, at);
                    signal = Path.of(raw.substring(bar + 1));
                } catch (RuntimeException malformed) {
                    point = null;
                }
            }
        }
        POINT = point;
        ARRIVAL = arrival;
        SIGNAL = signal;
        if (raw != null) {
            // NIT-1 (r2): a set property parks a spool or writer thread for good; say so once.
            org.apache.logging.log4j.LogManager.getLogger("eventtrail").warn("EventTrail test property {} is set{}; a "
                    + "spool or database writer thread stops for good at that point. Remove it outside kill tests.",
                    PROPERTY, point == null ? " but malformed (ignored)" : " (" + point + " at arrival " + arrival + ")");
        }
    }

    private SpoolKillPoint() {
    }

    static void reach(String point) {
        if (POINT == null || !POINT.equals(point)) return;
        if (ARRIVALS.incrementAndGet() != ARRIVAL) return;
        try {
            Files.writeString(SIGNAL, point, StandardCharsets.UTF_8);
        } catch (java.io.IOException unwritable) {
            return;
        }
        while (true) {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException ignored) {
                // Parked for good: only the parent's kill ends this thread.
            }
        }
    }
}
