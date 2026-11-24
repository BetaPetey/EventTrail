package io.github.betapetey.eventtrail.inventory.graves;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * The grave base's status counts (graves design r6, 1.3 to 1.7): each names a case the base saw and did not record,
 * or recorded only in part. A count is not a row and names nobody; it holds only how often a case happened since the
 * process started. The owner-read reasons of 1.2 are counted by {@code GraveOwners} through the adapter fail-safe, not
 * here.
 */
public final class GraveCounts {

    /** The counted cases, with the design's names. */
    public enum Reason {
        /** A take inside a command: the mod's method ran, nothing was read or recorded (1.7). */
        GRAVE_IN_COMMAND("grave-in-command"),
        /** A take with neither the taker's own scope at the grave nor a listed path open (1.3 step 0b). */
        GRAVE_TAKE_NO_SCOPE("grave-take-no-scope"),
        /** {@code left - taken - spilledGrave - lost} was not 0 for an identity (1.3-5): never credited. */
        GRAVE_UNACCOUNTED("grave-unaccounted"),
        /** The taker's Curios items could not be read (absent, missing or unreadable subtree): Curios gain 0 (1.5). */
        GRAVE_CURIOS_UNREAD("grave-curios-unread"),
        /** A spill site that hands the helper a copy: its units are tagged unknown and never credited (1.3-3). */
        GRAVE_SPILL_COPIED("grave-spill-copied"),
        /** An item compound in a grave's contents list that did not read as an item stack (1.1-3): left out. */
        GRAVE_CONTENTS_UNREAD("grave-contents-unread"),
        /** An adapter's owner portions did not sum to the grave's count of an identity: that identity is owner unknown. */
        GRAVE_PORTIONS_MISMATCH("grave-portions-mismatch"),
        /** A take with destroyed items whose loss pair could not be built: none of the take's rows was written. */
        GRAVE_LOSS_UNRECORDED("grave-loss-unrecorded"),
        /** A take whose rows were not written (a failure, or storage refused the batch). */
        GRAVE_ROWS_NOT_RECORDED("grave-rows-not-recorded"),
        /** A spawn helper's or a window's {@code addFreshEntity} join was cancelled (1.6.4). */
        ORIGIN_JOIN_CANCELLED("origin-join-cancelled"),
        /** A pending spill and a window were open at once: the pending spill won (1.6.4 rule 4). */
        ORIGIN_WINDOW_OVERLAP("origin-window-overlap"),
        /** A label's identity no longer matched its entity's stack: the label was removed (1.6.2). */
        ORIGIN_IDENTITY_CHANGED("origin-identity-changed"),
        /** More than 99 buckets: the smallest were folded into {@code unknown} (1.6.1). */
        ORIGIN_BUCKETS_FOLDED("origin-buckets-folded"),
        /** A label whose kind or structure did not read: ignored (1.6.1, 3.2 item 7). */
        ORIGIN_UNREADABLE("origin-unreadable"),
        /** A label could not be written on a spilled entity (a failure while it was built or stored). */
        ORIGIN_NOT_WRITTEN("origin-not-written");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final Map<Reason, LongAdder> COUNTS = new EnumMap<>(Reason.class);

    static {
        for (Reason reason : Reason.values()) COUNTS.put(reason, new LongAdder());
    }

    private GraveCounts() {
    }

    /** Counts one case. Never throws. */
    public static void count(Reason reason) {
        try {
            if (reason != null) COUNTS.get(reason).increment();
        } catch (Throwable ignored) {
            // A count never reaches the mod or vanilla code it describes.
        }
    }

    /** How often {@code reason} was counted since the process started. */
    public static long total(Reason reason) {
        return reason == null ? 0L : COUNTS.get(reason).sum();
    }
}
