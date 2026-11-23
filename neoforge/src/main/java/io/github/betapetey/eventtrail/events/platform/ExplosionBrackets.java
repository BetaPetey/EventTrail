package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.BlockIdentity;
import io.github.betapetey.eventtrail.events.evidence.ExplosionAuditSession;
import io.github.betapetey.eventtrail.events.evidence.ExplosionParts;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * GF-EXPLOSIONSESSION: the server-thread core of {@link ExplosionRemovalAudit}, written against two small ports
 * ({@link BlockWorld}, {@link Outlet}) so it runs without a live level. Everything that was private in ExplosionMixin
 * lives here once: the capture before a block call and the commit after it, the close with its one publish and its
 * failure rules, and the three warnings. The vanilla explosion and every mod explosion bracket run this same code.
 *
 * <p>Open brackets are kept per thread, innermost first, and are used only to close them, to find the innermost one
 * while a body runs and to answer {@link #removingBlocksNow}; nothing is kept across calls. Every bracket of one
 * thread shares the outermost bracket's set of positions already accounted for, so a position is listed once even
 * when brackets nest ("inner bracket wins").</p>
 */
final class ExplosionBrackets {
    static final Logger LOGGER = LogManager.getLogger("eventtrail");

    /**
     * A2-44: rate-limits the truncation WARN (now also every explosion above the row cap) and the not-recorded WARN
     * (which also covers an explosion storage refused whole).
     */
    static final RateLimitedDiagnosticGate TRUNCATION_WARNINGS = new RateLimitedDiagnosticGate(60_000);

    static final RateLimitedDiagnosticGate NOT_RECORDED_WARNINGS = new RateLimitedDiagnosticGate(60_000);

    /** GF-BLOCKIDENTITY MINOR-1: a block that mod code removed during a wind burst (no row, counted). */
    static final RateLimitedDiagnosticGate WIND_BURST_WARNINGS = new RateLimitedDiagnosticGate(60_000);

    /** A bracket or flag left from an earlier server tick (never expected: every bracket closes in a finally). */
    static final RateLimitedDiagnosticGate STALE_WARNINGS = new RateLimitedDiagnosticGate(60_000);

    /** Blocks that mod code removed during a wind burst since this process started; never written as rows. */
    private static final LongAdder WIND_BURST_REMOVALS = new LongAdder();

    private static final ThreadLocal<ArrayDeque<Frame>> OPEN = new ThreadLocal<>();

    private ExplosionBrackets() {
    }

    /** What the per-block code reads from the world. Production reads the live level (ExplosionRemovalAudit). */
    interface BlockWorld {
        ResourceLocation dimensionId();

        BlockState state(BlockPos position);

        /** The physical location frame at {@code position}, or null when none can be captured. */
        LocationFrame frame(BlockPos position);

        BlockAuditPreimage preimage(BlockPos position, BlockState liveState);
    }

    /** The part of a captured location frame the commit needs. */
    @FunctionalInterface
    interface LocationFrame {
        Optional<AuditLocation> currentLocation();
    }

    /** Where a closed explosion goes. Production: storage's tryLogAll and the explosion part publisher. */
    interface Outlet {
        /** A whole explosion, handed to storage as ONE action ({@link ExplosionAuditSession#publish}). */
        boolean publish(ExplosionAuditSession session);

        long newExplosionId();

        void submit(ExplosionParts parts);

        void countUnbuilt(long listedRows);
    }

    enum Kind {
        /** ExplosionMixin's wrap of Explosion.finalizeExplosion; its session stays in the mixin's own field. */
        VANILLA,
        /** A mod explosion bracket (ExplosionRemovalAudit.run / runDeferred); its session is held here. */
        HELPER
    }

    /** What a bracket opened with: a session, or none, and whether to watch a wind burst's blocks. */
    record Opening(ExplosionAuditSession session, boolean windBurstWatch) {
        static final Opening NONE = new Opening(null, false);
        /** GF-BLOCKIDENTITY MINOR-1: no session only because the explosion is a wind burst (TRIGGER_BLOCK). */
        static final Opening WATCH = new Opening(null, true);

        static Opening of(ExplosionAuditSession session) {
            return session == null ? NONE : new Opening(session, false);
        }
    }

    @FunctionalInterface
    interface Opener {
        Opening open();
    }

    record PositionKey(ResourceLocation dimensionId, BlockPos position) {
    }

    /** One open bracket on this thread. */
    static final class Frame {
        final Kind kind;
        final long tick;
        /**
         * GF-EXPLOSIONSESSION review-01 MINOR-5: the dimension of the level the bracket was opened for, or null when it
         * is not known (then the flag answers for every level, the side that masks rather than credits).
         */
        final ResourceLocation dimension;
        /**
         * The outermost bracket's positions already listed and the kind of bracket that listed each, shared by every
         * bracket nested in it. A mod bracket skips any listed position; the vanilla bracket skips only a position a
         * mod bracket listed, so vanilla explosions alone (even nested in each other) list exactly what they did.
         */
        final Map<PositionKey, Kind> accounted;
        ExplosionAuditSession session;
        Explosion explosion;
        boolean deferred;
        boolean deferredOpened;
        boolean windBurstWatch;
        int openTickets;
        boolean closed;

        private Frame(Kind kind, long tick, ResourceLocation dimension, Map<PositionKey, Kind> accounted) {
            this.kind = kind;
            this.tick = tick;
            this.dimension = dimension;
            this.accounted = accounted;
        }
    }

    // ----- brackets -------------------------------------------------------------------------------------------------

    /**
     * Opens a bracket for this thread in server tick {@code tick}. Frames left from an earlier tick are closed first
     * (a helper session among them is cut short and published with its failure row) and logged.
     */
    static Frame push(Kind kind, long tick, Outlet outlet) {
        return push(kind, tick, null, outlet);
    }

    /** {@link #push(Kind, long, Outlet)} for the level of {@code dimension} (MINOR-5; null when not known). */
    static Frame push(Kind kind, long tick, ResourceLocation dimension, Outlet outlet) {
        ArrayDeque<Frame> open = OPEN.get();
        if (open == null) {
            open = new ArrayDeque<>();
            OPEN.set(open);
        }
        if (!open.isEmpty() && hasStale(open, tick)) {
            while (!open.isEmpty()) abandon(open.pollFirst(), outlet);
        }
        Frame outermost = open.peekLast();
        Frame frame = new Frame(kind, tick, dimension, outermost == null ? new HashMap<>() : outermost.accounted);
        open.push(frame);
        return frame;
    }

    /**
     * A mod explosion bracket: raises the flag, opens the session (or, deferred, waits for the first block that names
     * its Explosion), runs {@code body} exactly once and closes and publishes in a finally block. A throw from the
     * body is passed on unchanged after the session is closed with its failure row; nothing of EventTrail's own is
     * ever thrown into the body's caller.
     */
    static <T> T bracket(
            long tick,
            Explosion explosion,
            boolean deferred,
            Opener opener,
            ExplosionRemovalAudit.Body<T> body,
            Outlet outlet) {
        return bracket(tick, null, explosion, deferred, opener, body, outlet);
    }

    /** {@link #bracket(long, Explosion, boolean, Opener, ExplosionRemovalAudit.Body, Outlet)} for one level (MINOR-5). */
    static <T> T bracket(
            long tick,
            ResourceLocation dimension,
            Explosion explosion,
            boolean deferred,
            Opener opener,
            ExplosionRemovalAudit.Body<T> body,
            Outlet outlet) {
        Frame frame = push(Kind.HELPER, tick, dimension, outlet);
        frame.explosion = explosion;
        frame.deferred = deferred;
        if (!deferred) {
            try {
                Opening opening = opener.open();
                frame.session = opening.session();
                frame.windBurstWatch = opening.windBurstWatch();
            } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                LOGGER.error("Could not open an EventTrail explosion bracket ({})",
                        EventTrailDiagnostics.safeFailureSummary(failure));
            }
        }
        Throwable bodyFailure = null;
        try {
            return body.run();
        } catch (Throwable thrown) {
            bodyFailure = thrown;
            throw thrown;
        } finally {
            close(frame, frame.session, bodyFailure, outlet, true);
        }
    }

    /**
     * Closes one bracket: the explosion (normally, or cut short by the game's or the mod's throw {@code gameFailure})
     * is finished and published once, then the bracket is taken off this thread. {@code guardNormalEnd} (mod
     * brackets) keeps every EventTrail throwable out of the mod's code also on the normal path; the vanilla path
     * keeps its own rule (only the cut-short path is guarded, review-01 MINOR-3).
     */
    static void close(Frame frame, ExplosionAuditSession session, Throwable gameFailure, Outlet outlet,
                      boolean guardNormalEnd) {
        try {
            if (session != null) {
                if (frame != null && frame.kind == Kind.HELPER && frame.openTickets > 0) {
                    // A begun block whose commit never ran (the mod's removal sequence did not end normally) may
                    // have been destroyed without a row: the count is no longer exact (review-01 MAJOR-1). The
                    // vanilla close keeps today's rule unchanged.
                    session.unlisted();
                }
                if (gameFailure == null && !guardNormalEnd) {
                    finish(session, null, outlet);
                } else {
                    try {
                        finish(session, gameFailure, outlet);
                    } catch (Throwable ignored) {
                        // Review-01 MINOR-3: EventTrail never replaces the game's own throwable.
                        // Advisor M-1: finish warns only RuntimeException and LinkageError itself; any other
                        // EventTrail Error here drops the explosion, so warn it too, best effort and guarded.
                        try {
                            notRecorded(ignored);
                        } catch (Throwable alsoIgnored) {
                            // The warning is best effort; the game's throwable still propagates unchanged.
                        }
                    }
                }
            }
        } finally {
            if (frame != null) pop(frame, outlet);
        }
    }

    private static void pop(Frame frame, Outlet outlet) {
        frame.closed = true;
        ArrayDeque<Frame> open = OPEN.get();
        if (open == null) return;
        if (open.peekFirst() == frame) {
            open.pollFirst();
        } else if (open.contains(frame)) {
            // Brackets opened inside this one and never closed: close them too.
            while (!open.isEmpty()) {
                Frame top = open.pollFirst();
                if (top == frame) break;
                abandon(top, outlet);
            }
        }
        if (open.isEmpty()) OPEN.remove();
    }

    private static boolean hasStale(ArrayDeque<Frame> open, long tick) {
        for (Frame frame : open) {
            if (frame.tick != tick) return true;
        }
        return false;
    }

    /** A bracket left open (never expected): its helper session is cut short and published with its failure row. */
    private static void abandon(Frame frame, Outlet outlet) {
        if (frame.closed) return;
        frame.closed = true;
        try {
            if (frame.kind == Kind.HELPER && frame.session != null) {
                frame.session.unlisted();
                finish(frame.session, new IllegalStateException("explosion bracket left open"), outlet);
            }
        } catch (RuntimeException | LinkageError failure) {
            notRecorded(failure);
        }
        warnStale("an explosion bracket was left open and has been closed");
    }

    /** The innermost open bracket of this thread, or null. */
    static Frame innermost() {
        ArrayDeque<Frame> open = OPEN.get();
        return open == null ? null : open.peekFirst();
    }

    /** The innermost bracket if it is the vanilla bracket of {@code explosion}, or null. */
    static Frame vanillaFrame(Explosion explosion) {
        Frame frame = innermost();
        return frame != null && frame.kind == Kind.VANILLA && frame.explosion == explosion && !frame.closed
                ? frame
                : null;
    }

    /**
     * The "explosion is removing blocks now" flag: true while any bracket body (vanilla or mod) runs on this thread
     * in server tick {@code tick}, whether or not it holds a session. A bracket from an earlier tick is ignored and
     * logged.
     */
    static boolean removingBlocksNow(long tick) {
        return removingBlocksNow(tick, null);
    }

    /**
     * MINOR-5: the flag for the level of {@code dimension}. A bracket of this tick opened for another level does not
     * raise it; a bracket or a question whose dimension is not known (null) matches every level.
     */
    static boolean removingBlocksNow(long tick, ResourceLocation dimension) {
        ArrayDeque<Frame> open = OPEN.get();
        if (open == null || open.isEmpty()) return false;
        boolean current = false;
        for (Frame frame : open) {
            if (frame.tick != tick || frame.closed) continue;
            current = true;
            if (frame.dimension == null || dimension == null || frame.dimension.equals(dimension)) return true;
        }
        if (!current) warnStale("an explosion flag from an earlier server tick was ignored");
        return false;
    }

    static long windBurstRemovedBlocks() {
        return WIND_BURST_REMOVALS.sum();
    }

    // ----- per-block ------------------------------------------------------------------------------------------------

    /**
     * Mod per-block entry: the innermost bracket must be a mod bracket that has (or, deferred, now opens) a session.
     * Outside a bracket, after the session stopped, or with a different Explosion than the bracket's, nothing in the
     * world is touched and an empty ticket comes back.
     */
    static ExplosionRemovalAudit.Ticket before(
            BlockWorld world,
            BlockPos position,
            Explosion explosion,
            Function<Explosion, Opening> deferredOpener,
            BooleanSupplier writeRoom) {
        Frame frame = innermost();
        if (frame == null || frame.kind != Kind.HELPER || frame.closed || position == null) {
            return ExplosionRemovalAudit.Ticket.EMPTY;
        }
        if (frame.deferred && !frame.deferredOpened) {
            if (explosion == null) return ExplosionRemovalAudit.Ticket.EMPTY;
            frame.deferredOpened = true;
            frame.explosion = explosion;
            try {
                Opening opening = deferredOpener.apply(explosion);
                frame.session = opening.session();
                frame.windBurstWatch = opening.windBurstWatch();
            } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                LOGGER.error("Could not open an EventTrail explosion bracket ({})",
                        EventTrailDiagnostics.safeFailureSummary(failure));
                return ExplosionRemovalAudit.Ticket.EMPTY;
            }
        } else if (explosion != null && frame.explosion != null && explosion != frame.explosion) {
            // Another Explosion inside this bracket cannot be separated into trustworthy operations.
            if (frame.session != null) frame.session.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            return ExplosionRemovalAudit.Ticket.EMPTY;
        }
        if (frame.session != null) return capture(frame, frame.session, world, position, null, writeRoom);
        if (frame.windBurstWatch) return watch(world, position);
        return ExplosionRemovalAudit.Ticket.EMPTY;
    }

    /**
     * The capture before one block call (formerly ExplosionMixin.java:135-165): copy the position, read the live
     * state, begin the block in the session, capture its location frame and its preimage. {@code operationState} is
     * the state the game's own call works on (vanilla), or null for a mod call, which works on the live state.
     */
    static ExplosionRemovalAudit.Ticket capture(
            Frame frame,
            ExplosionAuditSession session,
            BlockWorld world,
            BlockPos position,
            BlockState operationState,
            BooleanSupplier writeRoom) {
        BlockPos copy = position.immutable();
        ResourceLocation dimension = null;
        ExplosionAuditSession.Candidate candidate = null;
        LocationFrame location = null;
        BlockAuditPreimage preimage = null;
        try {
            BlockState liveState = world.state(copy);
            dimension = world.dimensionId();
            candidate = session.begin(
                            dimension,
                            copy,
                            liveState,
                            operationState == null ? liveState : operationState,
                            writeRoom.getAsBoolean())
                    .orElse(null);
            if (candidate != null) {
                location = world.frame(copy);
                if (location != null) preimage = world.preimage(copy, liveState);
            }
        } catch (RuntimeException | LinkageError failure) {
            session.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            LOGGER.error(
                    "Could not capture EventTrail explosion preimage at {} ({})",
                    position,
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
        if (candidate == null) return ExplosionRemovalAudit.Ticket.EMPTY;
        if (frame != null) frame.openTickets++;
        return new ExplosionRemovalAudit.Ticket(
                frame, session, world, dimension, copy, candidate, location, preimage, null);
    }

    /**
     * The commit after the block call (formerly ExplosionMixin.java:169-189): read the state again, complete the
     * block and list it only when complete returns evidence and the frame still has a current location. A position
     * another bracket of this thread already accounted for is skipped (inner bracket wins). Idempotent.
     */
    static void commit(ExplosionRemovalAudit.Ticket ticket) {
        if (ticket == null || ticket == ExplosionRemovalAudit.Ticket.EMPTY || ticket.done) return;
        ticket.done = true;
        if (ticket.frame != null && ticket.candidate != null) ticket.frame.openTickets--;
        if (ticket.watchBefore != null) {
            windBurst(ticket);
            return;
        }
        ExplosionAuditSession session = ticket.session;
        if (session == null || ticket.candidate == null) return;
        PositionKey key = ticket.frame == null || ticket.dimension == null
                ? null
                : new PositionKey(ticket.dimension, ticket.position);
        if (key != null) {
            Kind listedBy = ticket.frame.accounted.get(key);
            if (listedBy != null && (ticket.frame.kind == Kind.HELPER || listedBy == Kind.HELPER)) return;
        }
        if (ticket.preimage == null || ticket.location == null) {
            // Review-01 MAJOR-1: a begun block without a location frame or preimage may be destroyed by the game
            // without a row and without being counted, so the explosion's count is no longer exact.
            session.unlisted();
            return;
        }
        try {
            LocationFrame committedFrame = ticket.location;
            // A2-44: the row joins this explosion's one action; nothing is handed to storage until it ends.
            // MAJOR-1: proven destroyed but no current location = destroyed without a row or count.
            session.complete(ticket.candidate, ticket.preimage, ticket.world.state(ticket.position))
                    .ifPresent(evidence -> {
                        if (key != null) ticket.frame.accounted.put(key, ticket.frame.kind);
                        committedFrame.currentLocation().ifPresentOrElse(
                                location -> session.list(evidence, location),
                                session::unlisted);
                    });
        } catch (RuntimeException | LinkageError failure) {
            session.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            LOGGER.error(
                    "Could not finalize EventTrail explosion evidence at {} ({})",
                    ticket.position,
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    /** The removal call threw: the block's change can no longer be proven, so the count is no longer exact. */
    static void abandon(ExplosionRemovalAudit.Ticket ticket) {
        if (ticket == null || ticket == ExplosionRemovalAudit.Ticket.EMPTY || ticket.done) return;
        ticket.done = true;
        if (ticket.candidate == null) return;
        if (ticket.frame != null) ticket.frame.openTickets--;
        if (ticket.session != null) ticket.session.unlisted();
    }

    /** GF-BLOCKIDENTITY MINOR-1: reads the state before one block call of a wind burst. */
    static ExplosionRemovalAudit.Ticket watch(BlockWorld world, BlockPos position) {
        try {
            BlockPos copy = position.immutable();
            BlockState before = world.state(copy);
            if (before == null || before.isAir() || BlockIdentity.excluded(before)) {
                return ExplosionRemovalAudit.Ticket.EMPTY;
            }
            return new ExplosionRemovalAudit.Ticket(null, null, world, null, copy, null, null, null, before);
        } catch (RuntimeException | LinkageError failure) {
            return ExplosionRemovalAudit.Ticket.EMPTY;
        }
    }

    /** A block that mod code removed during a wind burst: counted and warned, never a row. */
    private static void windBurst(ExplosionRemovalAudit.Ticket ticket) {
        try {
            if (!BlockIdentity.blockDiffers(ticket.watchBefore, ticket.world.state(ticket.position))) return;
            WIND_BURST_REMOVALS.increment();
            WIND_BURST_WARNINGS.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail: wind burst removed a block; not recorded ({} at {} in {}; {} such block(s) since "
                            + "start; suppressed {} such warning(s) since the last warning)",
                    ticket.watchBefore,
                    ticket.position,
                    ticket.world.dimensionId(),
                    WIND_BURST_REMOVALS.sum(),
                    emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A count or warning must never change the game's result.
        }
    }

    // ----- close and publish ----------------------------------------------------------------------------------------

    /**
     * A2-44: closes the explosion (normally, or cut short by the game's throw {@code gameFailure}). A whole explosion
     * hands all of its rows to storage in ONE call, all or none. An explosion stored in parts (Owner decision B: too
     * large for one action, or cut short) has every part built first, then handed to the part publisher, which offers
     * the parts one by one and counts every part not stored; the build and the first offers stay inside this guard
     * (Advisor M-5 a). While a VirtualMachineError (StackOverflowError, OutOfMemoryError) unwinds, nothing is handed
     * to storage (review-01 MINOR-3): the explosion is closed and reported not recorded.
     */
    static void finish(ExplosionAuditSession session, Throwable gameFailure, Outlet outlet) {
        if (gameFailure instanceof VirtualMachineError) {
            session.abort();
            notRecorded(gameFailure);
            return;
        }
        ExplosionAuditSession.Summary summary;
        boolean stored;
        try {
            summary = gameFailure == null ? session.finish() : session.abort();
            if (session.storedInParts()) {
                ExplosionParts parts;
                try {
                    parts = session.planParts(outlet.newExplosionId());
                } catch (RuntimeException | LinkageError buildFailure) {
                    // Review M-A (1): no part exists, so the publisher never sees this explosion; count it here.
                    outlet.countUnbuilt(summary.listedRows());
                    throw buildFailure;
                }
                outlet.submit(parts);
                stored = true;
            } else {
                stored = outlet.publish(session);
            }
        } catch (RuntimeException | LinkageError failure) {
            notRecorded(failure);
            return;
        }
        if (!stored) {
            refused(summary);
        } else if (summary.truncated()) {
            truncated(summary);
        }
    }

    static void truncated(ExplosionAuditSession.Summary summary) {
        try {
            TRUNCATION_WARNINGS.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail explosion evidence was truncated in {} at {} after {} bounded work unit(s): {}; "
                            + "{} block row(s) listed, {}{} destroyed block(s) not listed; "
                            + "suppressed {} such warning(s) since the last warning",
                    summary.dimensionId(),
                    summary.center(),
                    summary.workUsed(),
                    summary.truncation().storageCode(),
                    summary.listedRows(),
                    summary.notListedExact() ? "" : "at least ",
                    summary.notListed(),
                    emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change the explosion's result.
        }
    }

    /** Review-01 MINOR-2: storage refused the whole explosion, so nothing of it was stored, truncated or not. */
    static void refused(ExplosionAuditSession.Summary summary) {
        try {
            NOT_RECORDED_WARNINGS.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail storage refused an explosion in {} at {} ({} block row(s), reason {}); "
                            + "none of its rows was written; suppressed {} such warning(s) since the last warning",
                    summary.dimensionId(),
                    summary.center(),
                    summary.listedRows(),
                    summary.truncation().storageCode(),
                    emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change the explosion's result.
        }
    }

    /** A2-44: an explosion that failed before or while its one publish was built is not recorded at all. */
    static void notRecorded(Throwable failure) {
        try {
            NOT_RECORDED_WARNINGS.acquire().ifPresent(emission -> LOGGER.warn(
                    "Could not record an EventTrail explosion ({}); none of its rows was written; "
                            + "suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure),
                    emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change the explosion's result.
        }
    }

    private static void warnStale(String what) {
        try {
            STALE_WARNINGS.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail: {}; suppressed {} such warning(s) since the last warning",
                    what,
                    emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change the game's result.
        }
    }
}
