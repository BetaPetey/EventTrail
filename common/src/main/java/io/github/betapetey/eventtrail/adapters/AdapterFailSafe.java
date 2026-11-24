package io.github.betapetey.eventtrail.adapters;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.adapters.hooks.HookVerdicts;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.WarningReason;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The one central fail-safe that every EventTrail-to-adapter call goes through.
 *
 * <p>Two failure rules (Owner, adapter fail-safe B):</p>
 * <ul>
 *   <li>a class, method or field that the adapter uses is missing: that adapter
 *       is switched OFF until the server restarts, with one warning naming the
 *       mod and the member;</li>
 *   <li>any other error: just that action is skipped and records nothing; the
 *       warning is not repeated per occurrence (de-duplicated per key, failure
 *       class and site, with a rate-limited repeat summary).</li>
 * </ul>
 *
 * <p>After its argument checks nothing here throws to its caller: every
 * {@link Throwable} that adapter work or this class raises, including
 * {@link StackOverflowError} and {@link OutOfMemoryError}, is contained. The
 * argument checks are the {@code wrap*} methods, which throw
 * {@link NullPointerException} for a null callback when the wrapper is made
 * (never when it runs). A failure to initialise this class itself surfaces at
 * the caller; {@code HookGuard} contains it. There is no cascade: a dependent
 * adapter goes OFF only when it meets a missing member itself.</p>
 *
 * <p>A {@link StackOverflowError} or {@link OutOfMemoryError} is only counted
 * where it is caught, in counters made with the ledger, because the stack or
 * the heap is nearly exhausted there. Its warning is deferred: the next guarded
 * call or menu-adapter match writes it (at most once per repeat interval), and
 * server start writes what the ending run still owes. There is no shutdown
 * summary.</p>
 *
 * <p>Last-resort drops: a report, integrity failure or concept-not-found with a
 * null key is dropped without a line, because there is no adapter to name or
 * switch.</p>
 *
 * <p>Failures are sorted by {@link FailureClassifier} (deciding-frame rule).
 * The OFF table here is the one source of OFF: the published compatibility
 * snapshot ({@code OptionalMenuAdapterLoader.compatibilitySnapshot()}) merges
 * it into the status entry of each key, and server start calls
 * {@link #resetAtServerStart()} before it builds a fresh snapshot.</p>
 */
public final class AdapterFailSafe {
    static final int MAX_SKIP_KEYS = 256;
    static final long REPEAT_SUMMARY_INTERVAL_MILLIS = 10L * 60L * 1000L;
    static final String OVERFLOW_CAUSE = "other";
    static final String OVERFLOW_SITE = "further sites";
    /** Prefix of the hook guard's contract-violation reason codes (see {@link #invalid}). */
    public static final String HOOK_CONTRACT_PREFIX = "hook-";
    private static final String INVALID_PREFIX = "invalid:";
    private static final String REFUSED_PREFIX = "refused:";
    private static final String ACCESS_REFUSED_PREFIX = "access-refused:";
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static volatile Ledger ledger = productionLedger();
    /**
     * GF-WARNINGS r3, per key since r4 (review-02 MINOR-A): see {@link #countedOnThisThread}. One slot per key and a
     * last slot for counts without a key.
     */
    private static final int UNKEYED_SLOT = AdapterKey.values().length;
    private static final ThreadLocal<long[]> COUNTED_ON_THIS_THREAD =
            ThreadLocal.withInitial(() -> new long[UNKEYED_SLOT + 1]);

    private AdapterFailSafe() {
    }

    /** Sorting of one failure. */
    public enum FailureKind {
        MISSING_MEMBER,
        OTHER
    }

    /** Which access row a refused access-point row (see {@link #accessRefusedOnce}) was about. */
    public enum AccessPhase {
        /** Nothing was written for the menu: neither its OPEN nor its CLOSE row. */
        OPEN("open"),
        /** The OPEN row was written; its CLOSE row was not. */
        CLOSE("close");

        private final String code;

        AccessPhase(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Why an adapter key is OFF until restart. */
    public record OffState(AdapterKey key, WarningReason reason, String detail, String site) {
        public OffState {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
            detail = safe(detail);
            site = safe(site);
        }
    }

    /**
     * Runs {@code work} for {@code key} at {@code site}. Returns {@code skipped}
     * without running it when the key is OFF or its hook family is stopped, and
     * returns {@code skipped} after any failure.
     */
    public static <T> T call(AdapterKey key, String site, Callable<? extends T> work, T skipped) {
        if (work == null) return skipped;
        try {
            if (!active(key)) return skipped;
            return work.call();
        } catch (Throwable failure) {
            report(key, site, failure);
            return skipped;
        }
    }

    /** As {@link #call}; returns whether the work ran to completion. */
    public static boolean run(AdapterKey key, String site, Runnable work) {
        if (work == null) return false;
        try {
            if (!active(key)) return false;
            work.run();
            return true;
        } catch (Throwable failure) {
            report(key, site, failure);
            return false;
        }
    }

    /**
     * Runs {@code work} even when the key is OFF or its family is stopped, and
     * contains any failure. For cleanup that must always happen (lifecycle
     * clear and logout), never for recording.
     */
    public static boolean contain(AdapterKey key, String site, Runnable work) {
        if (work == null) return false;
        flushDeferred();
        try {
            work.run();
            return true;
        } catch (Throwable failure) {
            report(key, site, failure);
            return false;
        }
    }

    /** A stored callback that is skipped while the key is not active and never throws. */
    public static Runnable wrap(AdapterKey key, String site, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        return () -> run(key, site, callback);
    }

    /** A stored callback that always runs (as {@link #contain}) and never throws. */
    public static Runnable wrapContained(AdapterKey key, String site, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        return () -> contain(key, site, callback);
    }

    /** A stored one-argument callback that is skipped while the key is not active and never throws. */
    public static <T> Consumer<T> wrapConsumer(AdapterKey key, String site, Consumer<? super T> callback) {
        Objects.requireNonNull(callback, "callback");
        return value -> run(key, site, () -> callback.accept(value));
    }

    /**
     * Reports a failure that adapter work raised. A missing member switches the
     * key OFF until restart; anything else skips the action with a
     * de-duplicated warning. Never throws.
     */
    public static void report(AdapterKey key, String site, Throwable failure) {
        try {
            // Design 2.3 row 1 and work map R2-core point 6: at the edge of the stack or of memory nothing is
            // allocated here; a counter made with the ledger is incremented and the next flush writes the warning.
            if (failure instanceof StackOverflowError || failure instanceof OutOfMemoryError) {
                if (key != null) {
                    ledger.resourceError(key, failure instanceof StackOverflowError);
                    // r4 (review-02 NIT-1): this branch counts the action too, so the tracker does not count it again.
                    markCountedOnThisThread(key);
                }
                return;
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (key == null) return;
            markCountedOnThisThread(key);
            Ledger current = ledger;
            FailureClassifier.Classification sorted = FailureClassifier.classify(failure);
            if (sorted.missing()) {
                current.switchOff(new OffState(key, WarningReason.MISSING_MEMBER, sorted.member(), site), failure);
            } else {
                current.skip(key, site, failureName(failure), failure);
            }
        } catch (Throwable ignored) {
            // The fail-safe must never throw into adapter, mod, vanilla or startup code.
        }
    }

    /**
     * Reports an integrity failure that is not an exception (for example a
     * duplicate slot identity). The action records nothing; the adapter stays on.
     *
     * <p>Reason codes that start with {@value #HOOK_CONTRACT_PREFIX} are the hook
     * guard's contract violations (a hook body skipped the mod's call, swallowed
     * the mod's failure, or left the call unfinished). The body may already have
     * recorded rows for that action, so their warning says the rows of that
     * action may be wrong or missing instead of "not recorded".</p>
     */
    public static void invalid(AdapterKey key, String site, String reasonCode) {
        try {
            if (key == null) return;
            markCountedOnThisThread(key);
            ledger.skip(key, site, INVALID_PREFIX + safe(reasonCode), null);
        } catch (Throwable ignored) {
            // Never throws.
        }
    }

    /**
     * Build B F1-4 (Owner, about 11:02 AM ET 2026-11-14): an open that the
     * adapter recognises and refuses (for example a linked Sophisticated
     * storage). The refusal can come at the open or later in an open, after
     * rows of that open were written: those rows stand and the open is not
     * recorded while the check fails. Some causes never pass again in that
     * open (a class that cannot change, a binding or an access guard that
     * latches shut); others are read again at each capture, so a cause that
     * goes away (an upgrade taken out) lets later actions of that open be
     * recorded again. The text says only what holds for both. EventTrail does
     * not close or cancel the player's menu. The adapter stays on. Exactly one
     * warning line per key, reason and site after each server start, with one
     * wording that is true at the open and later: every later refusal is only
     * counted ({@link #skippedActions}), with no repeat summary. Never throws.
     */
    public static void refusedOnce(AdapterKey key, String site, String reasonCode) {
        try {
            if (key == null) return;
            markCountedOnThisThread(key);
            ledger.refuseOnce(key, site, REFUSED_PREFIX + safe(reasonCode));
        } catch (Throwable ignored) {
            // Never throws.
        }
    }

    /**
     * Access rows B65/B66 (Owner decision 4 A, 11-14): an OPEN or CLOSE row of
     * a storage menu that EventTrail did not write, while the menu itself and
     * the recording of item moves in it are untouched. {@code phase} OPEN: no
     * row of that menu was written; CLOSE: its OPEN row was written but not
     * its CLOSE row. Exactly one warning line per key, phase, reason and site
     * after each server start; every later one is only counted (shown per
     * adapter in {@code /et status}). It is not counted as a skipped action
     * ({@link #skippedActions}), because no action was skipped. Past the
     * ledger's capacity it uses its own overflow counter
     * ({@code access-refused:other}), never {@code refused:other} or
     * {@code other}. Never throws; a null key or phase writes nothing.
     */
    public static void accessRefusedOnce(AdapterKey key, String site, AccessPhase phase, String reasonCode) {
        try {
            if (key == null || phase == null) return;
            ledger.accessRefuseOnce(key, site, ACCESS_REFUSED_PREFIX + phase.code() + ":" + safe(reasonCode));
        } catch (Throwable ignored) {
            // Never throws.
        }
    }

    /** Access rows of {@code key} not written since the last server start ({@link #accessRefusedOnce}). */
    public static long accessRefusals(AdapterKey key) {
        return key == null ? 0L : ledger.accessRefused(key);
    }

    /** Every key with access rows not written since the last server start, in key order, with its count. */
    public static Map<AdapterKey, Long> accessRefusals() {
        return ledger.accessRefusals();
    }

    /**
     * Switches {@code key} OFF until restart because none of the members that
     * mark a supported behaviour path was found (old-version proof markers).
     */
    public static void conceptNotFound(AdapterKey key, String site, String lookedFor) {
        try {
            if (key == null) return;
            markCountedOnThisThread(key);
            ledger.switchOff(new OffState(key, WarningReason.CONCEPT_NOT_FOUND, lookedFor, site), null);
        } catch (Throwable ignored) {
            // Never throws.
        }
    }

    /**
     * The key is not OFF and its hook family is running. It can throw only if
     * {@code HookVerdicts} cannot initialise; {@link #call}, {@link #run} and
     * {@code HookGuard} contain that. {@link #call}, {@link #run} and
     * {@code HookGuard} pass here first ({@link #contain} flushes itself), so
     * this is also where a deferred stack or memory warning is written.
     */
    public static boolean active(AdapterKey key) {
        flushDeferred();
        return key != null && !ledger.isOff(key) && HookVerdicts.familyRunning(key);
    }

    /** The key was switched OFF until restart (independent of the hook family gate). */
    public static boolean switchedOff(AdapterKey key) {
        return key != null && ledger.isOff(key);
    }

    public static Optional<OffState> offState(AdapterKey key) {
        return key == null ? Optional.empty() : ledger.offState(key);
    }

    /** Every OFF key, in key order. */
    public static Map<AdapterKey, OffState> offStates() {
        return ledger.offStates();
    }

    /** Actions of {@code key} skipped since the last server start. */
    public static long skippedActions(AdapterKey key) {
        return key == null ? 0L : ledger.skipped(key);
    }

    /**
     * GF-WARNINGS r3 (MINOR-3): a mark of how many failures, skips, refusals and switch-offs this thread has counted
     * here (keyed reports, {@link #invalid}, {@link #refusedOnce}, {@link #conceptNotFound}) or through
     * {@link RecordingGaps}, for {@code key} only (null: the counts made without a key). Only a change between two
     * reads on the same thread means something; the value is not a count of actions. The tracker reads the slot of
     * its session's key, so a failed menu action already counted under that key is not counted again, and a count
     * for another key or fact during the action never hides it (r4, review-02 MINOR-A).
     */
    public static long countedOnThisThread(AdapterKey key) {
        try {
            return COUNTED_ON_THIS_THREAD.get()[slot(key)];
        } catch (RuntimeException | LinkageError unreadable) {
            return 0L;
        }
    }

    /** GF-WARNINGS r3: see {@link #countedOnThisThread}. Never throws. */
    static void markCountedOnThisThread(AdapterKey key) {
        try {
            COUNTED_ON_THIS_THREAD.get()[slot(key)]++;
        } catch (RuntimeException | LinkageError ignored) {
            // A mark never reaches the action it describes.
        }
    }

    private static int slot(AdapterKey key) {
        return key == null ? UNKEYED_SLOT : key.ordinal();
    }

    /**
     * Starts a fresh OFF table and skip ledger for a new server run, so OFF
     * lasts until the next server start even when the JVM is reused. Hook
     * verdicts are per process and are not reset.
     */
    public static void resetAtServerStart() {
        // SOPH-LABELS (review-01 NIT-3): container-name fallbacks restart too; a naming helper switched off is on again.
        ContainerLabelFallbacks.resetAtServerStart();
        Ledger previous = ledger;
        ledger = productionLedger();
        try {
            previous.flushResourceErrors(true);
        } catch (Throwable ignored) {
            // A deferred warning of the previous run is dropped rather than thrown into server start.
        }
    }

    /** Test seam for the batch C tests: a fresh ledger with injected sinks and clock. */
    static void resetForTests(
            Consumer<String> warnings, BiConsumer<String, Throwable> debug, LongSupplier clock) {
        ledger = new Ledger(MAX_SKIP_KEYS, REPEAT_SUMMARY_INTERVAL_MILLIS, warnings, debug, clock);
    }

    private static Ledger productionLedger() {
        return new Ledger(
                MAX_SKIP_KEYS,
                REPEAT_SUMMARY_INTERVAL_MILLIS,
                LOGGER::warn,
                LOGGER::debug,
                System::currentTimeMillis);
    }

    /**
     * Writes a deferred stack or memory warning when one is due. Never throws. {@link #active}, {@link #contain}
     * and the menu-adapter decorator's {@code match} (c-3 r7, Advisor r6 M-2) call it; never call it inside a catch.
     */
    public static void flushDeferred() {
        try {
            ledger.flushResourceErrors(false);
        } catch (Throwable ignored) {
            // Still at the edge of the stack or of memory: the totals stay counted and a later warning shows them.
        }
    }

    private static String failureName(Throwable failure) {
        return failure == null ? "UnknownFailure" : failure.getClass().getName();
    }

    private static String safe(String value) {
        return EventTrailDiagnostics.sanitizeLogValue(value, MissingAdapterMemberException.MAX_MEMBER_TEXT)
                .replace('§', '?');
    }

    record SkipKey(AdapterKey key, String cause, String site) {
    }

    static final class SkipCounter {
        private final AtomicLong total = new AtomicLong();
        private final AtomicBoolean announced = new AtomicBoolean();
        private final RateLimitedDiagnosticGate gate;

        SkipCounter(long intervalMillis) {
            this.gate = new RateLimitedDiagnosticGate(intervalMillis);
        }
    }

    static final class Ledger {
        private final int capacity;
        private final long intervalMillis;
        private final Consumer<String> warnings;
        private final BiConsumer<String, Throwable> debug;
        private final LongSupplier clock;
        private final ConcurrentHashMap<AdapterKey, OffState> off = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<SkipKey, SkipCounter> skips = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AdapterKey, AtomicLong> skippedByKey = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<AdapterKey, AtomicLong> accessRefusedByKey = new ConcurrentHashMap<>();
        // Made with the ledger, so counting a StackOverflowError or OutOfMemoryError allocates nothing (design
        // 2.3 row 1 and "Why nothing is re-thrown"; work map R2-core point 6).
        private final AtomicLong stackErrors = new AtomicLong();
        private final AtomicLong memoryErrors = new AtomicLong();
        private final AtomicLong stackReported = new AtomicLong();
        private final AtomicLong memoryReported = new AtomicLong();
        private final AtomicLong[] resourceErrorsByKey = counters(AdapterKey.values().length);
        private final RateLimitedDiagnosticGate resourceGate;

        Ledger(
                int capacity,
                long intervalMillis,
                Consumer<String> warnings,
                BiConsumer<String, Throwable> debug,
                LongSupplier clock) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            this.intervalMillis = intervalMillis;
            this.warnings = Objects.requireNonNull(warnings, "warnings");
            this.debug = Objects.requireNonNull(debug, "debug");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.resourceGate = new RateLimitedDiagnosticGate(intervalMillis);
        }

        private static AtomicLong[] counters(int size) {
            AtomicLong[] counters = new AtomicLong[size];
            for (int index = 0; index < size; index++) counters[index] = new AtomicLong();
            return counters;
        }

        boolean isOff(AdapterKey key) {
            return off.containsKey(key);
        }

        Optional<OffState> offState(AdapterKey key) {
            return Optional.ofNullable(off.get(key));
        }

        Map<AdapterKey, OffState> offStates() {
            if (off.isEmpty()) return Map.of();
            return Collections.unmodifiableMap(new EnumMap<>(off));
        }

        long skipped(AdapterKey key) {
            AtomicLong count = skippedByKey.get(key);
            return (count == null ? 0L : count.get()) + resourceErrorsByKey[key.ordinal()].get();
        }

        long accessRefused(AdapterKey key) {
            AtomicLong count = accessRefusedByKey.get(key);
            return count == null ? 0L : count.get();
        }

        Map<AdapterKey, Long> accessRefusals() {
            if (accessRefusedByKey.isEmpty()) return Map.of();
            EnumMap<AdapterKey, Long> counts = new EnumMap<>(AdapterKey.class);
            accessRefusedByKey.forEach((key, count) -> {
                long value = count.get();
                if (value > 0L) counts.put(key, value);
            });
            return Collections.unmodifiableMap(counts);
        }

        /** Counts one action skipped at the edge of the stack or of memory; allocates nothing and writes no line. */
        void resourceError(AdapterKey key, boolean stack) {
            (stack ? stackErrors : memoryErrors).incrementAndGet();
            resourceErrorsByKey[key.ordinal()].incrementAndGet();
        }

        /**
         * Writes the deferred stack and memory warnings that are due: at most once per repeat interval, or at
         * once when {@code force} (server start, for the run that ends). Each count is claimed before its line
         * is written, so no line is written twice.
         */
        void flushResourceErrors(boolean force) {
            long stack = stackErrors.get();
            long memory = memoryErrors.get();
            long stackSeen = stackReported.get();
            long memorySeen = memoryReported.get();
            if (stack == stackSeen && memory == memorySeen) return;
            if (!force && resourceGate.acquire(Math.max(0L, clock.getAsLong())).isEmpty()) return;
            if (stack != stackSeen && stackReported.compareAndSet(stackSeen, stack)) {
                warnings.accept(resourceMessage("stack", stack - stackSeen, stack));
            }
            if (memory != memorySeen && memoryReported.compareAndSet(memorySeen, memory)) {
                warnings.accept(resourceMessage("memory", memory - memorySeen, memory));
            }
        }

        private String resourceMessage(String resource, long sinceLast, long total) {
            StringBuilder affected = new StringBuilder();
            for (AdapterKey key : AdapterKey.values()) {
                long count = resourceErrorsByKey[key.ordinal()].get();
                if (count == 0L) continue;
                if (!affected.isEmpty()) affected.append(", ");
                affected.append(name(key)).append(' ').append(count);
            }
            return OptionalIntegrationCompatibility.LOG_MARKER + " EventTrail adapter work ran out of " + resource
                    + " " + sinceLast + (sinceLast == 1L ? " time" : " times") + " since the last report ("
                    + total + " since start). Those actions were not recorded; the adapters stay on."
                    + " Stack and memory errors since start per adapter: " + affected + ".";
        }

        void switchOff(OffState state, Throwable failure) {
            if (off.putIfAbsent(state.key(), state) != null) return;
            warnings.accept(offMessage(state));
            if (failure != null) debug.accept(offMessage(state), failure);
        }

        void skip(AdapterKey key, String site, String cause, Throwable failure) {
            skippedByKey.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            SkipKey wanted = new SkipKey(key, safe(cause), safe(site));
            SkipCounter counter = skips.get(wanted);
            SkipKey used = wanted;
            if (counter == null) {
                if (skips.size() >= capacity) used = new SkipKey(key, OVERFLOW_CAUSE, OVERFLOW_SITE);
                counter = skips.computeIfAbsent(used, ignored -> new SkipCounter(intervalMillis));
            }
            long total = counter.total.incrementAndGet();
            // Only the holder of a gate emission writes a line, so the first warning and a repeat summary
            // cannot race (review-01 NIT 1). A negative clock reads as 0 so the first warning is never lost.
            Optional<RateLimitedDiagnosticGate.Emission> emission =
                    counter.gate.acquire(Math.max(0L, clock.getAsLong()));
            if (emission.isEmpty()) return;
            if (counter.announced.compareAndSet(false, true)) {
                String message = firstSkipMessage(used, failure);
                warnings.accept(message);
                if (failure != null) debug.accept(message, failure);
                return;
            }
            warnings.accept(repeatMessage(used, emission.get().suppressedSinceLastEmission() + 1L, total));
        }

        /**
         * Build B F1-4: counts one refused open; only the first refusal per key, cause and site after the ledger was
         * made (server start) writes a line. No gate, so no repeat summary is ever written for it. Past the ledger's
         * capacity a refusal uses its own overflow counter ({@code refused:other}), never the one {@link #skip} uses
         * (r3, re-check R-n1).
         */
        void refuseOnce(AdapterKey key, String site, String cause) {
            skippedByKey.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            SkipKey wanted = new SkipKey(key, safe(cause), safe(site));
            SkipCounter counter = skips.get(wanted);
            SkipKey used = wanted;
            if (counter == null) {
                if (skips.size() >= capacity) used = new SkipKey(key, REFUSED_PREFIX + OVERFLOW_CAUSE, OVERFLOW_SITE);
                counter = skips.computeIfAbsent(used, ignored -> new SkipCounter(intervalMillis));
            }
            counter.total.incrementAndGet();
            if (counter.announced.compareAndSet(false, true)) warnings.accept(refusedMessage(used));
        }

        /**
         * Access rows B65/B66: counts one access row not written; only the first per key, cause and site after the
         * ledger was made (server start) writes a line, with no gate and no repeat summary. It never counts a skipped
         * action. Past the ledger's capacity it uses its own overflow counter ({@code access-refused:other}).
         */
        void accessRefuseOnce(AdapterKey key, String site, String cause) {
            accessRefusedByKey.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            SkipKey wanted = new SkipKey(key, safe(cause), safe(site));
            SkipCounter counter = skips.get(wanted);
            SkipKey used = wanted;
            if (counter == null) {
                if (skips.size() >= capacity) {
                    used = new SkipKey(key, ACCESS_REFUSED_PREFIX + OVERFLOW_CAUSE, OVERFLOW_SITE);
                }
                counter = skips.computeIfAbsent(used, ignored -> new SkipCounter(intervalMillis));
            }
            counter.total.incrementAndGet();
            if (counter.announced.compareAndSet(false, true)) warnings.accept(accessRefusedMessage(used));
        }

        static String accessRefusedMessage(SkipKey key) {
            String prefix = OptionalIntegrationCompatibility.LOG_MARKER + " " + name(key.key()) + ": ";
            String cause = key.cause();
            String openPrefix = ACCESS_REFUSED_PREFIX + AccessPhase.OPEN.code() + ":";
            String closePrefix = ACCESS_REFUSED_PREFIX + AccessPhase.CLOSE.code() + ":";
            if (cause.startsWith(openPrefix)) {
                return prefix + "EventTrail did not write the OPEN or CLOSE row for this storage menu (reason="
                        + cause.substring(openPrefix.length()) + ") at " + key.site() + ". The menu opened normally"
                        + " for the player. This line is only about the open/close rows; it does not change whether"
                        + " item moves in that menu are recorded. Further such opens are counted until the server"
                        + " restarts, without another line.";
            }
            if (cause.startsWith(closePrefix)) {
                String reason = cause.substring(closePrefix.length());
                return prefix + "EventTrail recorded the OPEN row for this storage menu but did not write its CLOSE"
                        + " row (reason=" + reason + ") at " + key.site() + ", because " + closeCause(reason)
                        + ". The menu closed normally for the player. This line is only about the open/close rows;"
                        + " it does not change whether item moves in that menu are recorded. Further such cases are"
                        + " counted until the server restarts, without another line.";
            }
            return prefix + "EventTrail did not write some OPEN or CLOSE rows for this adapter's storage menus"
                    + " (further reasons and sites are counted together). The menus opened and closed normally for"
                    + " the players. This line is only about the open/close rows; it does not change whether item"
                    + " moves are recorded. Further such cases are counted until the server restarts, without"
                    + " another line.";
        }

        private static String closeCause(String reason) {
            return switch (reason) {
                case "terminal-gone" -> "when the menu closed, EventTrail could not confirm that the terminal was"
                        + " still the block or part that was opened (for example it was broken, replaced, unloaded or"
                        + " rebuilt, or the check failed or could not run)";
                case "cell-gone" -> "when the menu closed, EventTrail could not confirm that the portable cell was"
                        + " still the item stack that was opened (for example it was moved, removed or replaced, or"
                        + " the check failed or could not run)";
                case "not-stationary" -> "when the menu closed, EventTrail could not confirm that the terminal was"
                        + " still at a fixed, current world position (for example it is on a moving frame)";
                case "session-lost" -> "when the menu closed, EventTrail could not confirm that the player was still"
                        + " the same live player in the same world (for example after death, respawn or a change of"
                        + " world)";
                case "logout" -> "the player left the server with the menu open";
                default -> "when the menu closed, EventTrail could not confirm that what was opened was still the"
                        + " same (or the check failed or could not run)";
            };
        }

        private static String refusedMessage(SkipKey key) {
            String reason = key.cause().startsWith(REFUSED_PREFIX)
                    ? key.cause().substring(REFUSED_PREFIX.length()) : key.cause();
            return OptionalIntegrationCompatibility.LOG_MARKER + " " + name(key.key())
                    + ": EventTrail did not record a storage open, or the rest of one (reason=" + reason + ") at "
                    + key.site() + ". Any rows already written for that open stand; the open is not recorded while"
                    + " this check fails. The storage opened normally for the player and EventTrail does not close it,"
                    + " and the adapter stays on. Further such refusals are counted until the server restarts, without"
                    + " another line.";
        }

        private static String offMessage(OffState state) {
            String because = state.reason() == WarningReason.CONCEPT_NOT_FOUND
                    ? "the behaviour it records was not found (looked for: " + state.detail() + ")"
                    : state.detail() + " is missing";
            return OptionalIntegrationCompatibility.LOG_MARKER + " " + name(state.key())
                    + ": EventTrail's adapter is off until the server restarts because " + because
                    + " (at " + state.site() + "). Its actions are not recorded; no generic fallback is used for them.";
        }

        private static String firstSkipMessage(SkipKey key, Throwable failure) {
            String scope = OVERFLOW_SITE.equals(key.site()) && OVERFLOW_CAUSE.equals(key.cause())
                    ? " Further distinct errors of this adapter are counted together." : "";
            if (hookContract(key)) {
                // The hook body completed or ran past the mod's call: it may already have recorded rows
                // (review-01 m5), so this warning must not claim that nothing was recorded.
                return OptionalIntegrationCompatibility.LOG_MARKER + " " + name(key.key())
                        + ": the hook body broke the guard contract (reason="
                        + key.cause().substring(INVALID_PREFIX.length()) + ") at " + key.site()
                        + ". The mod's own call was not repeated; rows of that action may be wrong or missing;"
                        + " the adapter stays on. Repeats are counted and summarized." + scope;
            }
            String what = key.cause().startsWith(INVALID_PREFIX)
                    ? "integrity check failed (reason=" + key.cause().substring(INVALID_PREFIX.length()) + ")"
                    : "error (" + (failure == null
                            ? safe(key.cause())
                            : EventTrailDiagnostics.safeFailureSummary(failure)) + ")";
            return OptionalIntegrationCompatibility.LOG_MARKER + " " + name(key.key())
                    + ": adapter " + what + " at " + key.site()
                    + ". That action was not recorded; the adapter stays on. Repeats are counted and summarized."
                    + scope;
        }

        private static String repeatMessage(SkipKey key, long sinceSummary, long total) {
            String outcome = hookContract(key)
                    ? " Rows of those actions may be wrong or missing."
                    : " Those actions were not recorded.";
            return OptionalIntegrationCompatibility.LOG_MARKER + " " + name(key.key()) + ": "
                    + sinceSummary + " more adapter errors at " + key.site() + " (" + key.cause()
                    + ") since the last summary; " + total + " since start." + outcome;
        }

        private static boolean hookContract(SkipKey key) {
            return key.cause().startsWith(INVALID_PREFIX + HOOK_CONTRACT_PREFIX);
        }

        private static String name(AdapterKey key) {
            return key.displayName() + " (" + key.modId() + ")";
        }
    }
}
