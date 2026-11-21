package io.github.betapetey.eventtrail.events.evidence;

/**
 * Explosion split (Owner decision B, explosion-split-design-02; Advisor review section 6): the numbers and choices the
 * Owner still has to confirm, each ONE constant here so that a single edit changes it. Values are the Advisor's
 * recommendations. Nothing else in the mod repeats these numbers.
 */
public final class ExplosionSplitLimits {
    private ExplosionSplitLimits() {
    }

    // ---- Owner decision 1: the caps --------------------------------------------------------------------------

    /**
     * Writer-estimated bytes (largest tag included) one explosion may hold while it is examined; rows beyond it are
     * left out and counted as not listed (design-02 2.2: 48 MiB).
     */
    public static final long MAX_EXPLOSION_RETAINED_BYTES = 48L * 1024L * 1024L;

    /** Rows the backlog of parts waiting for writer room may hold over all explosions (design-02 2.1: 32,768). */
    public static final int BACKLOG_MAX_ROWS = 32_768;

    /** Writer-estimated bytes the backlog may hold over all explosions (design-02 2.1: 96 MiB). */
    public static final long BACKLOG_MAX_BYTES = 96L * 1024L * 1024L;

    /**
     * Age bound of an explosion in the backlog, counted in server ticks (drain passes), never wall-clock time
     * (Advisor M-4): 120 s at 20 ticks per second. Its untried parts are dropped only when a NO_ROOM answer comes at
     * or past it.
     */
    public static final long BACKLOG_MAX_AGE_TICKS = 2_400L;

    /** Row permits a part must leave free in the writer when admitted (Advisor recommendation: 2,048). */
    public static final int HEADROOM_ROWS = 2_048;

    /** Pending-payload bytes a part must leave free in the writer when admitted (Advisor recommendation: 16 MiB). */
    public static final long HEADROOM_BYTES = 16L * 1024L * 1024L;

    // ---- Owner decision 2: server stop ----------------------------------------------------------------------

    /**
     * At server stop, how long the stop pass keeps offering parts while the writer has no room, before every part
     * still waiting is dropped and counted (Advisor recommendation: 10 s; 0 = the lead's one pass that does not
     * wait).
     */
    public static final long STOP_WAIT_MILLIS = 10_000L;

    // ---- Owner decision 3: "part 1 of 1" ---------------------------------------------------------------------

    /**
     * Whether a truncated explosion that fits one action is stored in parts ("part 1 of 1", design-02 1.1, Advisor
     * recommendation: yes). False restores the n6-3 form for it: one action with the truncation row last.
     */
    public static final boolean STORE_TRUNCATED_SINGLE_GROUP_IN_PARTS = true;

    // ---- Fixed (not an Owner decision) ----------------------------------------------------------------------

    /**
     * The writer's single-row limit ({@code AbstractJdbcStorage.MAX_SINGLE_ENTRY_RETAINED_BYTES}, package-private
     * there), the third limit Advisor M-1 counts the tag against; ExplosionActionWriterLimitsTest pins that they stay
     * equal.
     */
    public static final long MAX_ROW_RETAINED_BYTES = 256L * 1024L;
}
