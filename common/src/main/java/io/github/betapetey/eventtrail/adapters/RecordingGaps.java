package io.github.betapetey.eventtrail.adapters;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * GF-WARNINGS (refusal sweep B45, B46, B48 and the GF-QUICKMOVE close carries): a place where EventTrail stops
 * recording one container action gets a count and a warning instead of silence.
 *
 * <p>Nothing here writes, drops or changes a row, and nothing here switches an adapter OFF or changes what is
 * recorded: the caller has already decided that the action records nothing. For an adapter with a fail-safe key the
 * drop goes to {@link AdapterFailSafe#invalid} (a skip, de-duplicated per key, site and reason, never OFF, never
 * {@link AdapterFailSafe#report}), unless that adapter is already off, when it is counted only; a binding shape
 * refusal goes to {@link AdapterFailSafe#refusedOnce}. A session
 * without a key (EventTrail's own vanilla adapters) gets its own warning: the first drop of each gap after a server
 * start writes one WARN at once, later ones a rate-limited WARN with the count suppressed since. Every gap is also
 * counted per server run for the status command.</p>
 *
 * <p>The ledger restarts with {@link AdapterFamilyMisses#reset()} (server start and server stop). Nothing is thrown
 * short of a {@link VirtualMachineError}, which propagates unchanged.</p>
 */
public final class RecordingGaps {
    /** The interval of the rate-limited repeat WARN for a session without a fail-safe key. */
    static final long REPEAT_INTERVAL_MILLIS = 60_000L;
    /** B48: the reason of a binding shape refusal (the plan's text). */
    public static final String BINDING_SHAPE_REASON = "menu did not pass the binding shape check";
    /** r2 MINOR-1: the detail of a drop whose adapter is disabled at run time (counted only, see {@link #drop}). */
    public static final String ADAPTER_DISABLED = "adapter-disabled";
    private static final int MAX_DETAIL = 48;
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static final LongSupplier SYSTEM_CLOCK = System::currentTimeMillis;
    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile LongSupplier clock = SYSTEM_CLOCK;
    private static volatile boolean enabled = true;
    private static volatile Ledger ledger = new Ledger();

    private RecordingGaps() {
    }

    /** Why one action was not recorded. The site and code are a bounded set; the text is what the operator reads. */
    public enum Gap {
        /** B45: the physical correlator could not match the items that moved to the menu's slots. */
        ACTION_NOT_ATTRIBUTABLE("menu action correlation", "action-not-attributable",
                "items moved in a container menu could not be matched to its slots"),
        /** B45: the slot roles of the items that moved could not be told apart. */
        ACTION_ROLE_AMBIGUOUS("menu action role", "action-role-ambiguous",
                "the container slots the items moved through could not be told apart"),
        /** B45: items moved in a slot the click could not have touched. */
        ACTION_OUTSIDE_CLICK_PATH("menu action role", "action-outside-click-path",
                "items moved in a container slot the click could not have touched"),
        /**
         * B45: an authoritative adapter's accounting check failed (the operation and the inventory differ); r2
         * MINOR-3: or its proof of the native calls was refused (detail operation-unproven).
         */
        CONSERVATION_FAILED("menu authoritative conservation", "conservation-failed",
                "the mod's storage operation and the player's inventory could not be proven to account for the same"
                        + " items"),
        /**
         * B46 (KLL R5): the player, menu or session changed inside one exact action (its transfer); r2 MINOR-3: or
         * the terminal, network, storage or target behind the open menu changed (detail terminal-changed). r2 NIT-9:
         * the text also names the off-thread and not-in-action details.
         */
        AUTHORITATIVE_CONTEXT_CHANGED("menu authoritative transfer", "authoritative-context-changed",
                "the player, the open menu, the storage behind it or the tracking session changed during the mod's"
                        + " storage operation, or the operation ran off the server thread or outside the container"
                        + " action"),
        /**
         * B46 (KLL R5): the player, menu or session changed before the action completed (a close inside it); r2: or
         * the adapter's action guard found the storage behind the open menu no longer current (detail action-guard).
         */
        ACTION_CONTEXT_CHANGED("menu action completion", "action-context-changed",
                "the player, the open menu, the storage behind it or the tracking session changed before the container"
                        + " action completed"),
        /** GF-QUICKMOVE carry: items picked up from the device were on the cursor at the close and are untraced. */
        CLOSE_CURSOR_UNTRACED("menu close", "close-cursor-untraced",
                "items picked up from a container were on the cursor when the menu closed, and where they went could"
                        + " not be proven"),
        /** B48: a menu its adapter answered SUPPORTED for did not pass the binding shape check. */
        BINDING_SHAPE("menu binding shape", "binding-shape", BINDING_SHAPE_REASON),
        /**
         * r3 MINOR-3 (lead ruling 1): a tracked container action ended through the failure path
         * ({@code UniversalContainerTracker.menuActionFailed}): the game's or the mod's call threw, or the adapter
         * did not accept the operation it watched. Its pending evidence is discarded and no row is written. Counted
         * only when nothing else counted that action on its thread.
         */
        MENU_ACTION_FAILED("menu action failure", "menu-action-failed",
                "the container action failed or was not accepted by its adapter before it completed");

        private final String site;
        private final String code;
        private final String text;

        Gap(String site, String code, String text) {
            this.site = site;
            this.code = code;
            this.text = text;
        }

        /** The fail-safe site of this gap. */
        public String site() {
            return site;
        }

        /** The reason code of this gap. */
        public String code() {
            return code;
        }

        /** What the operator reads. */
        public String text() {
            return text;
        }
    }

    /**
     * Counts one action that was not recorded and warns about it. {@code key} is the session adapter's fail-safe key,
     * or null for EventTrail's own vanilla adapters. {@code detail} names the failed check (a short fixed text) or is
     * null. Writes no row and never switches anything OFF.
     */
    public static void drop(AdapterKey key, Gap gap, String detail) {
        try {
            if (!enabled || gap == null) return;
            Ledger current = ledger;
            current.count(gap);
            AdapterFailSafe.markCountedOnThisThread(key);
            String reason = detail == null ? gap.code() : gap.code() + "/" + clean(detail);
            if (key != null) {
                // r2 MINOR-1: an adapter that is off (switched off, or disabled at run time) already has its "off
                // until the server restarts" line; the fail-safe's skip line would say "the adapter stays on", so
                // such a drop is counted here only (the status command's gap line shows it).
                if (ADAPTER_DISABLED.equals(detail) || AdapterFailSafe.switchedOff(key)) return;
                AdapterFailSafe.invalid(key, gap.site(), reason);
                return;
            }
            current.warnUnkeyed(gap, reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A warning never reaches the action it describes.
        }
    }

    /**
     * B48: a menu that its adapter answered SUPPORTED for did not pass the binding shape check, so no binding is made
     * and nothing of that menu is recorded. One warning per key, site and reason after each server start through
     * {@link AdapterFailSafe#refusedOnce}; every later one is counted. The key stays ON.
     */
    public static void bindingShapeFailed(AdapterKey key, String site) {
        try {
            if (!enabled || key == null) return;
            ledger.count(Gap.BINDING_SHAPE);
            AdapterFailSafe.refusedOnce(key, site, BINDING_SHAPE_REASON);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A warning never reaches the binding it describes.
        }
    }

    /** The gaps counted since the server started (zero counts left out), in gap order. */
    public static Map<Gap, Long> counts() {
        return ledger.counts();
    }

    /** A fresh ledger (server start and stop, through {@link AdapterFamilyMisses#reset()}). */
    public static void reset() {
        ledger = new Ledger();
    }

    /** Test seam: a fresh ledger whose unkeyed WARN lines go to {@code sink} and whose clock is {@code time}. */
    static void useForTests(Consumer<String> sink, LongSupplier time) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        clock = time == null ? SYSTEM_CLOCK : time;
        enabled = true;
        reset();
    }

    /** Test seam for the guard test: with {@code false} every call above does nothing at all. */
    static void setEnabledForTests(boolean on) {
        enabled = on;
    }

    /** Test seam: back to the rest state (log sink, system clock, enabled, a fresh ledger). */
    static void restoreForTests() {
        useForTests(null, null);
    }

    static String message(Gap gap, String reason, long suppressed) {
        return "EventTrail did not record a container action: " + gap.text() + " (reason=" + reason + ") at "
                + gap.site() + ". No row was written for it and no player was credited with it. Repeats are counted"
                + " in the status command's output; suppressed " + suppressed + " such action(s) since the last"
                + " warning.";
    }

    private static String clean(String detail) {
        return EventTrailDiagnostics.sanitizeLogValue(detail, MAX_DETAIL).replace('§', '?');
    }

    private static final class Ledger {
        private final AtomicLongArray counts = new AtomicLongArray(Gap.values().length);
        private final Map<Gap, RateLimitedDiagnosticGate> gates = new EnumMap<>(Gap.class);

        Ledger() {
            for (Gap gap : Gap.values()) gates.put(gap, new RateLimitedDiagnosticGate(REPEAT_INTERVAL_MILLIS));
        }

        void count(Gap gap) {
            counts.incrementAndGet(gap.ordinal());
        }

        void warnUnkeyed(Gap gap, String reason) {
            gates.get(gap).acquire(clock.getAsLong())
                    .ifPresent(emission -> warnings.accept(message(gap, reason, emission.suppressedSinceLastEmission())));
        }

        Map<Gap, Long> counts() {
            Map<Gap, Long> copy = new EnumMap<>(Gap.class);
            for (Gap gap : Gap.values()) {
                long total = counts.get(gap.ordinal());
                if (total > 0L) copy.put(gap, total);
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
