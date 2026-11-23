package io.github.betapetey.eventtrail.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * The spool's replay position in the target database (writer-spool design-01 7.2, design-02 6): one row
 * {@code component = 'spool:' + 32 hex spool id} in {@code eventtrail_schema_version}, version = the last sequence
 * resolved, advanced by compare-and-set in the same transaction that inserts the rows. Standard SQL, the same text on
 * all three backends; no DDL. Also the two pure classifications: the start table (6.2) and the resync after a failure
 * (6.5, M-3).
 */
final class SpoolPosition {
    static final String COMPONENT_PREFIX = "spool:";
    static final String READ_SQL = "SELECT version FROM " + AbstractJdbcStorage.SCHEMA_VERSION_TABLE + " WHERE component = ?";
    static final String ADVANCE_SQL = "UPDATE " + AbstractJdbcStorage.SCHEMA_VERSION_TABLE
            + " SET version = ?, updated_at = ? WHERE component = ? AND version = ?";
    static final String TOUCH_SQL = "UPDATE " + AbstractJdbcStorage.SCHEMA_VERSION_TABLE
            + " SET updated_at = ? WHERE component = ?";

    private SpoolPosition() {}

    static String component(String spoolIdHex) {
        return COMPONENT_PREFIX + spoolIdHex;
    }

    static boolean isSpoolComponent(String component) {
        return component != null && component.startsWith(COMPONENT_PREFIX);
    }

    /** The position, or null when the row is absent. */
    static Long read(Connection connection, String component) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(READ_SQL)) {
            read.setString(1, component);
            try (ResultSet rs = read.executeQuery()) {
                if (!rs.next()) return null;
                long version = rs.getLong(1);
                if (rs.wasNull()) throw new SQLException("EventTrail spool position is NULL", "ETSP5");
                return version;
            }
        }
    }

    /** The existing per-backend upsert ("greatest version wins"); value = the first waiting sequence - 1. */
    static void create(Connection connection, String upsertSql, String component, long value, long now)
            throws SQLException {
        try (PreparedStatement create = connection.prepareStatement(upsertSql)) {
            create.setString(1, component);
            create.setInt(2, Math.toIntExact(value));
            create.setLong(3, now);
            create.executeUpdate();
        }
    }

    /** design-01 7.2 start-up check: an UPDATE that keeps the version (judged by not being refused, then a read). */
    static void touch(Connection connection, String component, long now) throws SQLException {
        try (PreparedStatement touch = connection.prepareStatement(TOUCH_SQL)) {
            touch.setLong(1, now);
            touch.setString(2, component);
            touch.executeUpdate();
        }
    }

    /** Advance-only compare-and-set; returns the update count (exactly 1 when applied). */
    static int advance(Connection connection, String component, long from, long to, long now) throws SQLException {
        try (PreparedStatement advance = connection.prepareStatement(ADVANCE_SQL)) {
            bindAdvance(advance, component, from, to, now);
            return advance.executeUpdate();
        }
    }

    static void bindAdvance(PreparedStatement advance, String component, long from, long to, long now)
            throws SQLException {
        advance.setInt(1, Math.toIntExact(to));
        advance.setLong(2, now);
        advance.setString(3, component);
        advance.setInt(4, Math.toIntExact(from));
    }

    /** design-02 6.2: what the start does, from P (null = row absent), R, H, the m-5 flag and the waiting count. */
    enum StartCase {
        /** R <= P <= H: delete through P (NORMAL: after a checkpoint); replay P+1..H. */
        NORMAL,
        /** P < R: the database lost R - P confirmed actions (M-5, exact WARN); advance-only CAS P -> R. */
        BEHIND,
        /** P > H: skip all, next = P + 1; counted as skipped (n-5), no WARN. */
        AHEAD,
        /** Row absent, flag 0: create the row at R, set the flag (after the row exists, m-2). */
        CREATE,
        /** Row absent, flag 1, actions waiting: the database was deleted or swapped (m-5): hold, fresh spool. */
        HOLD,
        /** Row absent, flag 1, nothing waiting: create the row at R (INFO). */
        RECREATE
    }

    static StartCase classifyStart(Long position, long replayedThrough, long highWater, boolean positionCreated,
                                   long waitingFrames) {
        if (position == null) {
            if (!positionCreated) return StartCase.CREATE;
            return waitingFrames > 0 ? StartCase.HOLD : StartCase.RECREATE;
        }
        long p = position;
        if (p > highWater) return StartCase.AHEAD;
        if (p < replayedThrough) return StartCase.BEHIND;
        return StartCase.NORMAL;
    }

    /** design-02 6.5 (M-3): after a failed attempt or a 0-row advance, classify the re-read position P. */
    enum Resync {
        /** P = m: not applied; retry exactly the same frame range. */
        RETRY_SAME_RANGE,
        /** P = the end of one of this writer's own attempts not proven rolled back: its own commit landed. */
        OWN_COMMIT_LANDED,
        /** Anything else: someone else moved the position; replay stops for the run. */
        OUT_OF_STEP
    }

    static Resync classifyResync(long position, long known, Set<Long> ownAttemptEnds) {
        if (position == known) return Resync.RETRY_SAME_RANGE;
        if (position > known && ownAttemptEnds.contains(position)) return Resync.OWN_COMMIT_LANDED;
        return Resync.OUT_OF_STEP;
    }

    /**
     * R-1 (r9): the writer's re-read of the position after a failure, in three states. PRESENT carries P; ABSENT is a
     * read that succeeded and found no row (a failover to a lagging copy, a restore, the table cleared): neither P = m
     * nor an own end, so out of step (6.5); READ_FAILED is a read that did not answer, retried like any outage.
     */
    record Reread(RereadKind kind, long position) {
        static final Reread ABSENT = new Reread(RereadKind.ABSENT, 0L);
        static final Reread READ_FAILED = new Reread(RereadKind.READ_FAILED, 0L);

        static Reread present(long position) {
            return new Reread(RereadKind.PRESENT, position);
        }
    }

    /** R-1 (r9): the three states of {@link Reread}. */
    enum RereadKind { PRESENT, ABSENT, READ_FAILED }
}
