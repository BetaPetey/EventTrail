package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.ExplosionAuditSession;
import io.github.betapetey.eventtrail.events.evidence.ExplosionPartPublisher;
import io.github.betapetey.eventtrail.events.evidence.ExplosionParts;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * GF-EXPLOSIONSESSION: the one shared piece that records the blocks an explosion removes, for the vanilla explosion
 * (ExplosionMixin) and for every mod whose explosion removes blocks itself. It names no mod class.
 *
 * <p>A mod hook offers the explosion only as a BRACKET around one mod call that runs to its end in one go; there is no
 * "open now, close later" pair. Three forms:</p>
 * <ul>
 *   <li>{@link #run(Level, Explosion, Body)}: the hook has the Explosion when the mod method starts; the actor comes
 *       from {@link NeoForgeExplosionActorProvenance#resolve} exactly as for the vanilla explosion;</li>
 *   <li>{@link #run(Level, BlockPos, Cause, Body)}: a removal loop with no Explosion at all; the actor comes from a
 *       closed {@link Cause} (neutral, or an EventTrail-made launch ticket), never from a UUID, a Player or an
 *       adapter-built ActorProvenance;</li>
 *   <li>{@link #runDeferred(Level, Body)}: the mod creates its Explosion inside the method; the session is opened at
 *       the first block that names it ({@link #before(Level, BlockPos, Explosion)}).</li>
 * </ul>
 * <p>Inside a bracket the hook wraps each removal with {@link #before} just ahead of the mod's own removal call and
 * {@link #after} just behind the last call of the removal sequence, or with {@link #around} for one call. Every
 * bracket runs its body exactly once, closes and publishes in a finally block, and raises the "explosion is removing
 * blocks now" flag ({@link #removingBlocksNow}) for its whole body, even when no session exists. Server thread only:
 * a bracket asked for a client level or from another thread opens nothing and raises no flag. Each call's rows are
 * published when that call ends and paged through the explosion split (Owner Q20: no per-explosion allowance).</p>
 */
public final class ExplosionRemovalAudit {
    private ExplosionRemovalAudit() {
    }

    /** One mod call (or one removal call) run inside a bracket. Its own throw is passed on unchanged. */
    @FunctionalInterface
    public interface Body<T> {
        T run();
    }

    // ----- mod brackets ---------------------------------------------------------------------------------------------

    /** Form (a): a mod call that has its Explosion when it starts. */
    public static <T> T run(Level level, Explosion explosion, Body<T> body) {
        Objects.requireNonNull(body, "body");
        long tick = serverTick(level);
        if (tick < 0L) return body.run();
        return ExplosionBrackets.bracket(tick, dimensionOf(level), explosion, false,
                () -> explosion == null ? ExplosionBrackets.Opening.NONE : openFor(level, explosion), body,
                PRODUCTION);
    }

    /** Form (b): a mod removal loop with no Explosion object; {@code center} is the explosion's own centre. */
    public static <T> T run(Level level, BlockPos center, Cause cause, Body<T> body) {
        Objects.requireNonNull(body, "body");
        long tick = serverTick(level);
        if (tick < 0L) return body.run();
        return ExplosionBrackets.bracket(tick, dimensionOf(level), null, false,
                () -> openForCause(level, center, cause), body,
                PRODUCTION);
    }

    /** Form (c): a mod call that creates its Explosion inside; the session opens at the first block naming it. */
    public static <T> T runDeferred(Level level, Body<T> body) {
        Objects.requireNonNull(body, "body");
        long tick = serverTick(level);
        if (tick < 0L) return body.run();
        return ExplosionBrackets.bracket(tick, dimensionOf(level), null, true, () -> ExplosionBrackets.Opening.NONE,
                body, PRODUCTION);
    }

    /** Just ahead of the mod's own removal call at {@code position} (forms a and b). */
    public static Ticket before(Level level, BlockPos position) {
        return before(level, position, null);
    }

    /**
     * Just ahead of the mod's own removal call at {@code position}, naming the Explosion: in a deferred bracket the
     * first such call opens the session. Outside a bracket, after the session stopped or off the server thread this
     * touches nothing and returns an empty ticket. Never throws into the mod.
     */
    public static Ticket before(Level level, BlockPos position, Explosion explosion) {
        try {
            if (level == null || ExplosionBrackets.innermost() == null) return Ticket.EMPTY;
            return ExplosionBrackets.before(new LevelWorld(level), position, explosion,
                    created -> openFor(level, created), EventTrailManager.getInstance()::canAcceptAuditWrite);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            stopInnermost(failure);
            return Ticket.EMPTY;
        }
    }

    /** Just behind the last call of the mod's removal sequence for the ticket's position. Never throws into the mod. */
    public static void after(Ticket ticket) {
        try {
            ExplosionBrackets.commit(ticket);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            stopInnermost(failure);
        }
    }

    /** {@link #before} + one removal call + {@link #after}. A throw from {@code call} is passed on unchanged. */
    public static <T> T around(Level level, BlockPos position, Body<T> call) {
        Objects.requireNonNull(call, "call");
        Ticket ticket = before(level, position);
        T result;
        try {
            result = call.run();
        } catch (Throwable thrown) {
            ExplosionBrackets.abandon(ticket);
            throw thrown;
        }
        after(ticket);
        return result;
    }

    /**
     * The "explosion is removing blocks now" flag that GF-SCOPE and the drop and pickup fixes read as their explosion
     * mask: true while a vanilla or mod explosion bracket body runs on this (server) thread in the current server
     * tick, whether or not it holds a session (recording off, actor excluded, storage refused). Only a bracket opened
     * for this level raises it (GF-EXPLOSIONSESSION review-01 MINOR-5).
     */
    public static boolean removingBlocksNow(Level level) {
        long tick = serverTick(level);
        return tick >= 0L && ExplosionBrackets.removingBlocksNow(tick, dimensionOf(level));
    }

    /** MINOR-5: the level's dimension, or null when it cannot be read (then the flag matches every level). */
    static ResourceLocation dimensionOf(Level level) {
        try {
            return level == null ? null : level.dimension().location();
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * GF-BLOCKIDENTITY MINOR-1: blocks that mod code removed during a wind burst (TRIGGER_BLOCK) since this process
     * started. Such a block is never a row; each one is counted here and warned (rate-limited).
     */
    public static long windBurstRemovedBlocks() {
        return ExplosionBrackets.windBurstRemovedBlocks();
    }

    // ----- the vanilla explosion (ExplosionMixin) -------------------------------------------------------------------

    /**
     * Opens the vanilla explosion's bracket: today's gates and session (unchanged; the session stays in
     * ExplosionMixin's own field), plus the flag. Called once per Explosion.finalizeExplosion.
     */
    public static VanillaBracket openVanilla(Level level, Explosion explosion) {
        ExplosionBrackets.Opening opening = openFor(level, explosion);
        long tick = serverTick(level);
        ExplosionBrackets.Frame frame = null;
        if (tick >= 0L) {
            frame = ExplosionBrackets.push(ExplosionBrackets.Kind.VANILLA, tick, dimensionOf(level), PRODUCTION);
            frame.explosion = explosion;
            frame.windBurstWatch = opening.windBurstWatch();
        }
        return new VanillaBracket(opening.session(), frame);
    }

    /**
     * Closes the vanilla explosion: published once (normally, or cut short by the game's throw {@code gameFailure},
     * which is never replaced), then the flag is lowered.
     */
    public static void closeVanilla(VanillaBracket bracket, Throwable gameFailure) {
        ExplosionBrackets.close(bracket.frame, bracket.session, gameFailure, PRODUCTION, false);
    }

    /**
     * The capture before one vanilla block callback ({@code operationState} is the state the game's call works on).
     * A callback for another Explosion stops the session, as before.
     */
    public static Ticket beforeVanillaBlock(
            Explosion self,
            ExplosionAuditSession session,
            Level level,
            BlockPos position,
            Explosion explosion,
            BlockState operationState) {
        ExplosionBrackets.Frame frame = ExplosionBrackets.vanillaFrame(self);
        if (session == null) {
            return frame != null && frame.windBurstWatch
                    ? ExplosionBrackets.watch(new LevelWorld(level), position)
                    : Ticket.EMPTY;
        }
        if (explosion != self) {
            session.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            return Ticket.EMPTY;
        }
        return ExplosionBrackets.capture(frame, session, new LevelWorld(level), position, operationState,
                EventTrailManager.getInstance()::canAcceptAuditWrite);
    }

    /** The commit after one vanilla block callback returned normally. */
    public static void afterVanillaBlock(Ticket ticket) {
        ExplosionBrackets.commit(ticket);
    }

    // ----- gates and actors -----------------------------------------------------------------------------------------

    /**
     * The gates and the session for an explosion that has an Explosion object (formerly
     * ExplosionMixin.eventtrail$beginSession): client level, an explosion that does not touch blocks, a wind burst
     * (GF-BLOCKIDENTITY: TRIGGER_BLOCK only toggles blocks and opens no session), the switch
     * recording.blocks.explosions.enabled and the counted capture gate; then the actor. A wind burst that passes every
     * other check is watched for blocks that mod code removes (counted, never a row).
     */
    static ExplosionBrackets.Opening openFor(Level level, Explosion explosion) {
        if (level.isClientSide()
                || !explosion.interactsWithBlocks()
                || !ExplosionAuditSession.opensSessionFor(explosion.getBlockInteraction())
                || !EventTrailConfig.TRACK_EXPLOSIONS.get()
                || !EventTrailManager.getInstance().admitCapture()) {
            return windBurstOnly(level, explosion) ? ExplosionBrackets.Opening.WATCH : ExplosionBrackets.Opening.NONE;
        }
        try {
            return ExplosionBrackets.Opening.of(NeoForgeExplosionActorProvenance.resolve(explosion)
                    .map(actor -> new ExplosionAuditSession(
                            actor,
                            level.dimension().location(),
                            BlockPos.containing(explosion.center())))
                    .orElse(null));
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            ExplosionBrackets.LOGGER.error(
                    "Could not capture EventTrail explosion context ({})",
                    EventTrailDiagnostics.safeFailureSummary(failure));
            return ExplosionBrackets.Opening.NONE;
        }
    }

    /** Only the wind-burst rule kept the session closed (no capture gate is counted for it). */
    private static boolean windBurstOnly(Level level, Explosion explosion) {
        return !level.isClientSide()
                && explosion.interactsWithBlocks()
                && explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK
                && EventTrailConfig.TRACK_EXPLOSIONS.get();
    }

    /** The gates and the session for form (b): no Explosion, so the actor is the cause's. */
    static ExplosionBrackets.Opening openForCause(Level level, BlockPos center, Cause cause) {
        if (level.isClientSide()
                || center == null
                || cause == null
                || !EventTrailConfig.TRACK_EXPLOSIONS.get()
                || !EventTrailManager.getInstance().admitCapture()) {
            return ExplosionBrackets.Opening.NONE;
        }
        return ExplosionBrackets.Opening.of(
                new ExplosionAuditSession(cause.actor(), level.dimension().location(), center.immutable()));
    }

    /** The current server tick when called on the server thread for a server level, otherwise -1. */
    static long serverTick(Level level) {
        try {
            if (level instanceof ServerLevel serverLevel && !serverLevel.isClientSide()) {
                MinecraftServer server = serverLevel.getServer();
                if (server != null && server.isSameThread()) return server.getTickCount();
            }
        } catch (RuntimeException | LinkageError failure) {
            // No server tick: no bracket.
        }
        return -1L;
    }

    private static void stopInnermost(Throwable failure) {
        try {
            ExplosionBrackets.Frame frame = ExplosionBrackets.innermost();
            if (frame != null && frame.kind == ExplosionBrackets.Kind.HELPER && frame.session != null) {
                frame.session.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            }
            ExplosionBrackets.LOGGER.error(
                    "Could not record an EventTrail mod explosion block ({})",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // Nothing of EventTrail's own reaches the mod.
        }
    }

    /** Production outlet: the whole explosion through storage's counted tryLogAll, parts through the publisher. */
    private static final ExplosionBrackets.Outlet PRODUCTION = new ExplosionBrackets.Outlet() {
        @Override
        public boolean publish(ExplosionAuditSession session) {
            return session.publish(System.currentTimeMillis(), EventTrailManager.getInstance()::tryLogAll);
        }

        @Override
        public long newExplosionId() {
            return ExplosionPartPublisher.newExplosionId();
        }

        @Override
        public void submit(ExplosionParts parts) {
            ExplosionPartPublisher.production().submit(parts);
        }

        @Override
        public void countUnbuilt(long listedRows) {
            ExplosionPartPublisher.production().countUnbuilt(listedRows);
        }
    };

    /** The live level as the per-block code reads it. */
    private record LevelWorld(Level level) implements ExplosionBrackets.BlockWorld {
        @Override
        public ResourceLocation dimensionId() {
            return level.dimension().location();
        }

        @Override
        public BlockState state(BlockPos position) {
            return level.getBlockState(position);
        }

        @Override
        public ExplosionBrackets.LocationFrame frame(BlockPos position) {
            AuditBlockLocations.Frame frame = AuditBlockLocations.capture(level, position).orElse(null);
            return frame == null ? null : frame::currentLocation;
        }

        @Override
        public BlockAuditPreimage preimage(BlockPos position, BlockState liveState) {
            return BlockAuditPreimage.capture(
                    System.currentTimeMillis(),
                    level,
                    position,
                    liveState,
                    EventTrailManager.getInstance().canAcceptAuditWrite());
        }
    }

    // ----- value types ----------------------------------------------------------------------------------------------

    /** The vanilla explosion's open bracket: its session (or none) and its frame (none off the server thread). */
    public static final class VanillaBracket {
        private final ExplosionAuditSession session;
        private final ExplosionBrackets.Frame frame;

        private VanillaBracket(ExplosionAuditSession session, ExplosionBrackets.Frame frame) {
            this.session = session;
            this.frame = frame;
        }

        public ExplosionAuditSession session() {
            return session;
        }
    }

    /** One captured block between {@link #before} and {@link #after}. Holds its own copy of the position. */
    public static final class Ticket {
        static final Ticket EMPTY = new Ticket(null, null, null, null, null, null, null, null, null);

        final ExplosionBrackets.Frame frame;
        final ExplosionAuditSession session;
        final ExplosionBrackets.BlockWorld world;
        final ResourceLocation dimension;
        final BlockPos position;
        final ExplosionAuditSession.Candidate candidate;
        final ExplosionBrackets.LocationFrame location;
        final BlockAuditPreimage preimage;
        /** GF-BLOCKIDENTITY MINOR-1: the state before one wind-burst block call (watch only, no session). */
        final BlockState watchBefore;
        boolean done;

        Ticket(ExplosionBrackets.Frame frame,
               ExplosionAuditSession session,
               ExplosionBrackets.BlockWorld world,
               ResourceLocation dimension,
               BlockPos position,
               ExplosionAuditSession.Candidate candidate,
               ExplosionBrackets.LocationFrame location,
               BlockAuditPreimage preimage,
               BlockState watchBefore) {
            this.frame = frame;
            this.session = session;
            this.world = world;
            this.dimension = dimension;
            this.position = position;
            this.candidate = candidate;
            this.location = location;
            this.preimage = preimage;
            this.watchBefore = watchBefore;
        }

        /** Whether this ticket captured a block of a session (an empty ticket touched nothing). */
        public boolean captured() {
            return candidate != null;
        }
    }

    /**
     * The actor of a form (b) bracket. Exactly two ways to make one: {@link #neutral} and {@link #fromTicket}. No
     * entry takes a player UUID, a Player or a ready-made ActorProvenance, so a saved owner or placer field can never
     * become an actor.
     */
    public static final class Cause {
        private final String neutralLabel;
        private final LaunchTicket ticket;

        private Cause(String neutralLabel, LaunchTicket ticket) {
            this.neutralLabel = neutralLabel;
            this.ticket = ticket;
        }

        /**
         * A neutral explosion: {@code ActorProvenance.environment(source.toString(), "Explosion")}, or the label
         * "Explosion" when {@code source} is null. The label is the source's namespaced id (a mod's block, entity or
         * item id), never a player's name: a player name cannot contain the ':' every id has (GF-EXPLOSIONSESSION
         * review-01 MINOR-2).
         */
        public static Cause neutral(ResourceLocation source) {
            return new Cause(source == null ? "Explosion" : source.toString(), null);
        }

        /** An explosion that EventTrail proved a player's own action set off (in memory only; see LaunchTicket). */
        public static Cause fromTicket(LaunchTicket ticket) {
            return new Cause(null, Objects.requireNonNull(ticket, "ticket"));
        }

        ActorProvenance actor() {
            return ticket != null ? ticket.actor() : ActorProvenance.environment(neutralLabel, "Explosion");
        }
    }

    /**
     * An in-memory ticket that EventTrail itself creates inside a player's own action (GF-LAUNCH builds the creating
     * side; nothing creates one yet). It is bound to the object it was created for through {@code live}: once that
     * object is gone (restart, chunk reload, removed) the ticket answers a neutral actor. Only an exactly identified
     * player with immediate-secondary causality can be carried.
     */
    public static final class LaunchTicket {
        private final ActorProvenance actor;
        private final String neutralLabel;
        private final BooleanSupplier live;

        LaunchTicket(ActorProvenance actor, String neutralLabel, BooleanSupplier live) {
            this.actor = Objects.requireNonNull(actor, "actor");
            if (actor.actorKind() != ActorProvenance.ActorKind.PLAYER
                    || actor.causality() != ActorProvenance.Causality.IMMEDIATE_SECONDARY) {
                throw new IllegalArgumentException("A launch ticket carries only an immediate-secondary player");
            }
            this.neutralLabel = neutralLabel == null || neutralLabel.isBlank() ? "Explosion" : neutralLabel;
            this.live = Objects.requireNonNull(live, "live");
        }

        ActorProvenance actor() {
            try {
                if (live.getAsBoolean()) return actor;
            } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                // A ticket whose owner cannot be checked is not proof.
            }
            return ActorProvenance.environment(neutralLabel, "Explosion");
        }
    }
}
