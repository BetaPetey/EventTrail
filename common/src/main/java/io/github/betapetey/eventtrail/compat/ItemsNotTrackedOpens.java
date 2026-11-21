package io.github.betapetey.eventtrail.compat;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Own-slot screens "opened" line (Owner decision A, 11-14): a screen that no adapter and no vanilla handling records
 * gets OPEN and CLOSE access rows marked "items not tracked" (naming the clicked block), but only when it opens on the
 * same server tick as that player's use-click on that block, after that block's own use started (r5), and only when
 * the screen or the clicked block holds items (Owner Q16 and decision 32 A: a screen with no slot, or only the player's
 * own inventory, gets no line when the clicked block exposes no container or item handler). This ledger counts the
 * lines written and each open of a screen no adapter records that it refused (block, entity, item or command screens),
 * by reason; the first refusal of each reason after a server start writes one WARN. Later refusals of that reason are
 * only counted (status output) until the next server start or stop. It also counts the lines kept because the clicked
 * block's item handler could not be read, with one WARN per server start. Nothing here changes the open;
 * nothing is thrown short of a {@link VirtualMachineError}, which propagates unchanged.
 */
public final class ItemsNotTrackedOpens {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Consumer<String> LOG_WARNINGS = LOGGER::warn;
    private static final int MAX_DETAIL_CHARS = 160;
    private static volatile Consumer<String> warnings = LOG_WARNINGS;
    private static volatile Ledger ledger = new Ledger();

    private ItemsNotTrackedOpens() {
    }

    /**
     * Why a screen no adapter records got no "opened" line. The order is the status output's order. Every such screen
     * is counted, not only block screens: NO_BLOCK_CLICK also counts entity screens (villager trades, horse and llama
     * inventories), item screens and command-opened menus (review-01 MINOR-1).
     */
    public enum Refusal {
        NO_BLOCK_CLICK("no use-click on a block by this player came before the screen (entity, item and command"
                + " screens included)"),
        CHECK_FAILED("the click or the check could not be read"),
        LATER_TICK("the screen opened on a later server tick than the click"),
        /** r5 (Advisor M-1): the click was never armed by the start of the clicked block's own use. */
        NO_BLOCK_USE("the clicked block's own use did not start before the screen opened (the held item or another"
                + " mod's handler could have opened it)"),
        ITEM_USE("an item or another mod's click handler could have opened the screen (an off-hand use, an item use"
                + " after the click, or a later click)"),
        NO_BLOCK_ENTITY("the clicked block has no block entity"),
        BLOCK_CHANGED("the clicked block changed before the screen opened"),
        OTHER_BLOCK("the screen's slots belong to another block, or are too many to check"),
        /**
         * Owner Q16: "Opening a screen that stores no items writes no OPEN or CLOSE row." Decision 32 A: only when the
         * clicked block also exposes no container and no item handler.
         */
        NO_ITEM_SLOTS("the screen shows only the player's own inventory, or no slot, and the clicked block exposes no"
                + " container or item handler"),
        NOT_BOUND("the clicked block's position could not be bound");

        private final String label;

        Refusal(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Since the server started: the opened lines written, the refusals by reason (only reasons with a count), and how
     * many of the written lines were kept because the clicked block's item handler could not be read (decision 32 A).
     */
    public record Counts(long written, Map<Refusal, Long> refused, long blockItemsUnread) {
        public Counts {
            EnumMap<Refusal, Long> copy = new EnumMap<>(Refusal.class);
            if (refused != null) copy.putAll(refused);
            refused = Collections.unmodifiableMap(copy);
        }

        /** No line was kept for an unreadable item handler. */
        public Counts(long written, Map<Refusal, Long> refused) {
            this(written, refused, 0L);
        }

        public boolean isEmpty() {
            return written == 0L && refused.isEmpty() && blockItemsUnread == 0L;
        }
    }

    /** Counts one "opened" line written. */
    public static void written() {
        try {
            ledger.written();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Only a count; short of a VirtualMachineError it never reaches into the open.
        }
    }

    /** Counts one refusal; the reason's first refusal since the server started also writes one WARN. */
    public static void refused(Refusal reason, String blockId, String screenId) {
        try {
            if (reason != null) ledger.refused(reason, blockId, screenId);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Only a count and a WARN; short of a VirtualMachineError it never reaches into the open.
        }
    }

    /**
     * Owner decision 32 A: the clicked block's item handler could not be read for a screen whose slots show only the
     * player's own inventory, so its "opened" line is kept (the true "items not tracked" row) rather than refused.
     * Counted; the first since the server started also writes one WARN.
     */
    public static void blockItemsUnread(String blockId, String screenId, Throwable failure) {
        try {
            ledger.blockItemsUnread(blockId, screenId, failure);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Only a count and a WARN; short of a VirtualMachineError it never reaches into the open.
        }
    }

    /** The counts since the server started. */
    public static Counts counts() {
        return ledger.counts();
    }

    /** A fresh ledger (server start and stop): the counts restart at zero and each reason may warn once again. */
    public static void reset() {
        ledger = new Ledger();
    }

    /**
     * Test seam: a fresh ledger, and the WARN lines of this and every later ledger go to {@code sink} instead of the
     * log ({@code null} restores the log). Other test packages reach it through {@code ItemsNotTrackedOpensTestSupport}.
     */
    static void useWarningSinkForTests(Consumer<String> sink) {
        warnings = sink == null ? LOG_WARNINGS : sink;
        reset();
    }

    static String message(Refusal reason, String blockId, String screenId) {
        return OptionalIntegrationCompatibility.LOG_MARKER + " A screen that no adapter records opened, and"
                + " EventTrail wrote no \"opened\" line for it (" + reason.label() + "; block " + detail(blockId, "none")
                + ", screen " + detail(screenId, "unknown") + "). The menu still works. Further cases for this reason"
                + " are only counted, in the status command's output, until the server restarts.";
    }

    static String unreadMessage(String blockId, String screenId, Throwable failure) {
        return OptionalIntegrationCompatibility.LOG_MARKER + " A screen that no adapter records opened showing only the"
                + " player's own inventory, and the clicked block's item handler could not be read ("
                + EventTrailDiagnostics.safeFailureSummary(failure) + "; block " + detail(blockId, "none") + ", screen "
                + detail(screenId, "unknown") + "). EventTrail kept its \"opened\" line, marked items not tracked."
                + " Further cases are only counted, in the status command's output, until the server restarts.";
    }

    private static String detail(String value, String absent) {
        if (value == null || value.isBlank()) return absent;
        return EventTrailDiagnostics.sanitizeLogValue(value, MAX_DETAIL_CHARS).replace('§', '?');
    }

    private static final class Ledger {
        private final AtomicLong written = new AtomicLong();
        private final AtomicLongArray refused = new AtomicLongArray(Refusal.values().length);
        private final Set<Refusal> warned = ConcurrentHashMap.newKeySet();
        private final AtomicLong blockItemsUnread = new AtomicLong();

        void written() {
            written.incrementAndGet();
        }

        void blockItemsUnread(String blockId, String screenId, Throwable failure) {
            if (blockItemsUnread.getAndIncrement() == 0L) warnings.accept(unreadMessage(blockId, screenId, failure));
        }

        void refused(Refusal reason, String blockId, String screenId) {
            refused.incrementAndGet(reason.ordinal());
            if (warned.add(reason)) warnings.accept(message(reason, blockId, screenId));
        }

        Counts counts() {
            Map<Refusal, Long> copy = new EnumMap<>(Refusal.class);
            for (Refusal reason : Refusal.values()) {
                long count = refused.get(reason.ordinal());
                if (count > 0L) copy.put(reason, count);
            }
            return new Counts(written.get(), copy, blockItemsUnread.get());
        }
    }
}
