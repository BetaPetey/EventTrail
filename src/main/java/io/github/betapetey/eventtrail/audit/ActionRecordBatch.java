package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * All rows of one recorded action, built completely in memory and handed to
 * storage once, so a failure never leaves a half-written record.
 *
 * <p>Producers add every row of the action first; nothing is handed over until
 * {@link #publish()}. Any failure before {@code publish()} discards the whole
 * batch. {@code publish()} hands the complete list to the sink in one call and
 * returns the sink's answer. The sink is injected
 * ({@code Predicate<List<AuditRecord>>}); in production it is the storage's
 * all-or-none admission ({@code tryLogAll}), wired by the producers. This class
 * names no storage class.</p>
 *
 * <p>Order guarantee for producers: every adapter call of the action happens
 * before {@code publish()}; no adapter code runs after it.</p>
 *
 * <p>Two refusals never reach storage's own warnings, so this class warns for
 * them: an action above {@link #MAX_ROWS_PER_ACTION} (refused before the sink
 * is called) and a sink that threw. Each reason has one rate-limited WARN
 * (first occurrence, then at most one summary per interval with the count
 * since the last warning); a sink failure's stack goes to DEBUG. A sink that
 * answers false is counted only: storage warns for its own refusals.</p>
 *
 * <p>A batch belongs to one action on one thread and is not thread-safe. The
 * refusal counters and warnings are process-wide and thread-safe.</p>
 */
public final class ActionRecordBatch {
    /**
     * Largest action, in rows, that storage admits as one unit (writer seam,
     * item 3). A larger action is refused whole before the sink is called.
     * Equal to the writer's own limit ({@code AbstractJdbcStorage.MAX_ROWS_PER_ACTION},
     * package-private there); {@code ActionRecordBatchTest} checks that the two
     * stay equal.
     */
    public static final int MAX_ROWS_PER_ACTION = 1024;

    /** Shortest time between two warnings for the same refusal reason. */
    static final long REFUSAL_WARNING_INTERVAL_MILLIS = 10L * 60L * 1000L;

    /** Why an action was not handed to storage, or not admitted by it. */
    public enum Refusal {
        /** A failure while the action was built, or an explicit discard. */
        DISCARDED,
        /** More rows than {@link #MAX_ROWS_PER_ACTION}. */
        TOO_MANY_ROWS,
        /** The sink answered false: storage admitted none of the rows. */
        STORAGE_REFUSED,
        /** The sink threw; treated as refused. */
        SINK_FAILED
    }

    private enum State {
        OPEN,
        PUBLISHED,
        DISCARDED
    }

    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final RefusalCounts PROCESS_REFUSALS = new RefusalCounts();
    private static final RefusalWarnings PROCESS_WARNINGS =
            new RefusalWarnings(LOGGER::warn, LOGGER::debug, System::currentTimeMillis);

    private final Predicate<List<AuditRecord>> sink;
    private final RefusalCounts refusals;
    private final RefusalWarnings warnings;
    private final List<AuditRecord> records = new ArrayList<>();
    private State state = State.OPEN;

    public ActionRecordBatch(Predicate<List<AuditRecord>> sink) {
        this(sink, PROCESS_REFUSALS, PROCESS_WARNINGS);
    }

    ActionRecordBatch(Predicate<List<AuditRecord>> sink, RefusalCounts refusals, RefusalWarnings warnings) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.refusals = Objects.requireNonNull(refusals, "refusals");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /**
     * Builds one action with {@code builder} and publishes it only if the
     * builder completed. If the builder throws, the batch is discarded (nothing
     * is handed over) and the same exception propagates to the caller's guard.
     * The builder must not publish or discard the batch itself.
     */
    public static boolean collect(
            Predicate<List<AuditRecord>> sink, Consumer<? super ActionRecordBatch> builder) {
        Objects.requireNonNull(builder, "builder");
        ActionRecordBatch batch = new ActionRecordBatch(sink);
        return batch.buildAndPublish(builder);
    }

    boolean buildAndPublish(Consumer<? super ActionRecordBatch> builder) {
        try {
            builder.accept(this);
        } catch (Throwable failure) {
            discard();
            throw sneaky(failure);
        }
        return publish();
    }

    /** Adds one row. A null row discards the whole batch. */
    public void add(AuditRecord record) {
        requireOpen();
        if (record == null) {
            discard();
            throw new NullPointerException("record");
        }
        records.add(record);
    }

    /**
     * Adds every row of {@code batchRecords}, in order, or none: a null list or
     * a null row discards the whole batch.
     */
    public void addAll(Collection<? extends AuditRecord> batchRecords) {
        requireOpen();
        if (batchRecords == null) {
            discard();
            throw new NullPointerException("records");
        }
        List<AuditRecord> checked = new ArrayList<>(batchRecords.size());
        for (AuditRecord record : batchRecords) {
            if (record == null) {
                discard();
                throw new NullPointerException("record");
            }
            checked.add(record);
        }
        records.addAll(checked);
    }

    /** Rows added so far. */
    public int size() {
        return records.size();
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    /** Drops every row; nothing of this action is handed over. Idempotent. */
    public void discard() {
        if (state != State.OPEN) return;
        state = State.DISCARDED;
        records.clear();
        refusals.increment(Refusal.DISCARDED);
    }

    /**
     * Hands every row to the sink in one call and returns whether storage took
     * the whole action. An empty action stores nothing, returns true and does
     * not call the sink. A discarded batch returns false without calling the
     * sink. An action above {@link #MAX_ROWS_PER_ACTION} rows, or a sink that
     * throws, returns false with a rate-limited warning. Publishing twice is a
     * programming error.
     */
    public boolean publish() {
        if (state == State.PUBLISHED) throw new IllegalStateException("Action batch already published");
        if (state == State.DISCARDED) return false;
        state = State.PUBLISHED;
        if (records.isEmpty()) return true;
        int rowCount = records.size();
        if (rowCount > MAX_ROWS_PER_ACTION) {
            records.clear();
            refusals.increment(Refusal.TOO_MANY_ROWS);
            warnings.tooManyRows(rowCount);
            return false;
        }
        List<AuditRecord> action = List.copyOf(records);
        records.clear();
        boolean stored;
        try {
            stored = sink.test(action);
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            refusals.increment(Refusal.SINK_FAILED);
            warnings.sinkFailed(rowCount, failure);
            return false;
        }
        if (!stored) refusals.increment(Refusal.STORAGE_REFUSED);
        return stored;
    }

    /** Process-wide count of actions not stored for {@code reason}. */
    public static long refusals(Refusal reason) {
        return PROCESS_REFUSALS.count(reason);
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Action batch is " + state.name().toLowerCase(Locale.ROOT));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Thread-safe per-reason refusal counts. */
    static final class RefusalCounts {
        private final Map<Refusal, LongAdder> counts = new EnumMap<>(Refusal.class);

        RefusalCounts() {
            for (Refusal reason : Refusal.values()) counts.put(reason, new LongAdder());
        }

        void increment(Refusal reason) {
            counts.get(reason).increment();
        }

        long count(Refusal reason) {
            return counts.get(Objects.requireNonNull(reason, "reason")).sum();
        }
    }

    /**
     * Thread-safe, rate-limited operator warnings for {@link Refusal#TOO_MANY_ROWS}
     * and {@link Refusal#SINK_FAILED}, one gate per reason. Never throws: a
     * failing warning sink cannot change what {@link #publish()} returns.
     */
    static final class RefusalWarnings {
        private final Consumer<String> warnings;
        private final BiConsumer<String, Throwable> debug;
        private final LongSupplier clock;
        private final RateLimitedDiagnosticGate tooManyRowsGate =
                new RateLimitedDiagnosticGate(REFUSAL_WARNING_INTERVAL_MILLIS);
        private final RateLimitedDiagnosticGate sinkFailedGate =
                new RateLimitedDiagnosticGate(REFUSAL_WARNING_INTERVAL_MILLIS);

        RefusalWarnings(Consumer<String> warnings, BiConsumer<String, Throwable> debug, LongSupplier clock) {
            this.warnings = Objects.requireNonNull(warnings, "warnings");
            this.debug = Objects.requireNonNull(debug, "debug");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        void tooManyRows(int rowCount) {
            try {
                Optional<RateLimitedDiagnosticGate.Emission> emission = tooManyRowsGate.acquire(now());
                if (emission.isEmpty()) return;
                warnings.accept("EventTrail action recording: an action of " + rowCount
                        + " rows was not recorded, because one action may have at most " + MAX_ROWS_PER_ACTION
                        + " rows; the whole action was refused before storage and none of its rows was stored."
                        + repeats(emission.get()));
            } catch (Throwable ignored) {
                // A warning must never change the publish result.
            }
        }

        void sinkFailed(int rowCount, Throwable failure) {
            try {
                Optional<RateLimitedDiagnosticGate.Emission> emission = sinkFailedGate.acquire(now());
                if (emission.isEmpty()) return;
                String message = "EventTrail action recording: storage failed while admitting an action of "
                        + rowCount + " rows (" + EventTrailDiagnostics.safeFailureSummary(failure)
                        + "); the action is treated as not recorded. This is a storage defect; the stack trace"
                        + " is logged at DEBUG." + repeats(emission.get());
                warnings.accept(message);
                debug.accept(message, failure);
            } catch (Throwable ignored) {
                // A warning must never change the publish result.
            }
        }

        private long now() {
            return Math.max(0L, clock.getAsLong());
        }

        private static String repeats(RateLimitedDiagnosticGate.Emission emission) {
            long earlier = emission.suppressedSinceLastEmission();
            String limit = " Repeats are counted and summarized at most every "
                    + REFUSAL_WARNING_INTERVAL_MILLIS / 60_000L + " minutes.";
            return earlier == 0L ? limit : " " + earlier + " more such actions since the last warning." + limit;
        }
    }
}
