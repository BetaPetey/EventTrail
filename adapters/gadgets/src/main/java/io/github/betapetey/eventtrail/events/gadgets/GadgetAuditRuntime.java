package io.github.betapetey.eventtrail.events.gadgets;

import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidenceCapturer;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.GadgetBlockEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Exact input -> bounded member -> exact placeholder -> terminal commit. No mod or world discovery. */
public final class GadgetAuditRuntime {
    public static final long MAX_LIFETIME_NANOS = 120_000_000_000L;
    public static final int MAX_PENDING = 4096, MAX_PENDING_PER_PLAYER = 2048;
    public static final long MAX_RETAINED_METADATA_BYTES = 8L * 1024 * 1024;
    private static final ThreadLocal<Input> INPUT = new ThreadLocal<>();
    private static final DeferredEffectLedger<Ticket> PENDING = new DeferredEffectLedger<Ticket>(
            MAX_PENDING, MAX_PENDING_PER_PLAYER, MAX_RETAINED_METADATA_BYTES,
            (ticket, failure) -> AdapterFailSafe.report(
                    ticket.authority.key, "bridge:GadgetAuditRuntime.pendingValidity", failure));
    private static final List<Lifecycle> LIFECYCLES = new CopyOnWriteArrayList<>();
    private static final RateLimitedDiagnosticGate NOT_RECORDED = new RateLimitedDiagnosticGate(60_000);
    /** GADGET-DELAYED: Building Gadgets block changes with no row handed to storage (see {@link #noteTerminal}). */
    private static final LongAdder UNRECORDED_BUILDING_GADGET_CHANGES = new LongAdder();
    private GadgetAuditRuntime() {}
    public enum Kind { BREAK, PLACE, REPLACE }

    public static InputBoundary beginInput(ServerPlayer player) {
        try {
        Input previous = INPUT.get();
        if (previous != null) { previous.invalid = true; return new InputBoundary(null); }
        if (!liveActor(player) || !admitInput(player)) return new InputBoundary(null);
        Input input = new Input(player);
        // N-1: the boundary is built before INPUT is set, so a failure while building it leaves nothing open.
        InputBoundary boundary = new InputBoundary(input);
        INPUT.set(input);
        return boundary;
        } catch (RuntimeException | LinkageError unavailable) {
            reportPreGadget("bridge:GadgetAuditRuntime.beginInput", unavailable);
            Input previous = INPUT.get();
            if (previous != null) previous.invalid = true;
            INPUT.remove();
            return new InputBoundary(null);
        }
    }
    public static ServerPlayer currentInputActor() {
        Input input = INPUT.get();
        return input == null || input.invalid || !live(input.player) ? null : input.player;
    }
    public static Authority captureInput(ServerPlayer player, String provider) {
        try {
        Input input = INPUT.get();
        if (input == null || input.invalid || currentInputActor() != player || provider == null) return null;
        var actor = NeoForgePlayerProvenance.capture(player, ActorProvenance.Causality.DIRECT, provider);
        return actor.map(value -> new Authority(input, value, keyOf(provider))).orElse(null);
        } catch (RuntimeException | LinkageError unavailable) {
            AdapterFailSafe.report(keyOf(provider), "bridge:GadgetAuditRuntime.captureInput", unavailable);
            return null;
        }
    }
    /** The gadget key of a provider string (the mod id the bridge passes to {@link #captureInput}). */
    private static AdapterKey keyOf(String provider) {
        return AdapterKey.BUILDING_GADGETS.modId().equals(provider) ? AdapterKey.BUILDING_GADGETS : AdapterKey.MINING_GADGETS;
    }
    private static boolean live(ServerPlayer player) {
        return liveActor(player) && EventTrailManager.getInstance().admitCapture();
    }
    /**
     * {@link #live} without the readiness gate (M-1: beginInput asks the gadget check before counting). GF-FAKEPLAYER-ID
     * fu2: the listed-player test is the counted {@link NeoForgePlayerProvenance#isAdmittedPlayer}, after the uncounted
     * thread, removed, spectator and FakePlayer tests, so an unlisted player that is not a FakePlayer is counted in the
     * unlisted-player status line.
     */
    private static boolean liveActor(ServerPlayer player) {
        return player != null && player.server != null && player.server.isSameThread()
                && !player.isRemoved() && !player.isSpectator() && !NeoForgePlayerProvenance.isSynthetic(player)
                && NeoForgePlayerProvenance.isAdmittedPlayer(player)
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    /**
     * M-1: the input packet's readiness answer, exactly {@code isReady()} either way. Every item use and block
     * interaction opens an input, so the counted not-ready gate runs only when a gadget is in either hand; without
     * one the same readiness is read uncounted (CaptureGateSourceScanTest names this exemption). A failed gadget
     * check falls back to the counted gate.
     */
    private static boolean admitInput(ServerPlayer player) {
        return gadgetInHand(player) ? EventTrailManager.getInstance().admitCapture()
                : EventTrailManager.getInstance().isReady();
    }
    private static boolean gadgetInHand(ServerPlayer player) {
        try {
            for (InteractionHand hand : InteractionHand.values()) {
                String namespace = BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()).getNamespace();
                if (AdapterKey.BUILDING_GADGETS.modId().equals(namespace)
                        || AdapterKey.MINING_GADGETS.modId().equals(namespace)) return true;
            }
            return false;
        } catch (RuntimeException | LinkageError unavailable) {
            // The check cannot run, so the counted gate is used (the answer that never hides a skipped attempt).
            reportPreGadget("bridge:GadgetAuditRuntime.gadgetInHand", unavailable);
            return true;
        }
    }

    public static Candidate prepare(Authority authority, BlockPos position) {
        if (authority == null || position == null || !stillHolds(authority) || !authority.level().hasChunkAt(position)) return null;
        try {
            var frame = AuditBlockLocations.capture(authority.level(), position).orElse(null);
            if (frame == null) return null;
            var state = authority.level().getBlockState(position);
            var before = BlockAuditPreimage.capture(System.currentTimeMillis(), authority.level(), position, state,
                    EventTrailManager.getInstance().canAcceptAuditWrite());
            return new Candidate(authority, before, frame);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(authority.key, "bridge:GadgetAuditRuntime.prepare", failure);
            return null;
        }
    }

    public static boolean attach(Candidate candidate, BlockEntity placeholder,
            BlockState semanticSource, BlockState expectedTarget, Kind kind) {
        if (candidate == null || placeholder == null || expectedTarget == null || semanticSource == null || kind == null) return false;
        Authority authority = candidate.authority();
        BlockAuditPreimage before = candidate.preimage();
        if (!stillHolds(authority) || !candidate.frame().isCurrent() || placeholder.isRemoved() || placeholder.getLevel() != authority.level()
                || !before.position().equals(placeholder.getBlockPos())
                || authority.level().getBlockEntity(before.position()) != placeholder
                || (kind == Kind.PLACE ? !semanticSource.isAir() : !before.blockState().equals(semanticSource))
                || (kind == Kind.BREAK && (!expectedTarget.isAir() || before.blockState().isAir()))
                || (kind != Kind.BREAK && expectedTarget.isAir())) return false;
        Ticket ticket = new Ticket(authority, before, placeholder, expectedTarget, kind, candidate.frame());
        // Count, lifetime and a conservative retained metadata charge are independent bounds.
        long weight = before.blockEntityEvidence().canonicalSnbt().map(s -> 4L * s.length() + 65536L).orElse(0L);
        return PENDING.put(placeholder, authority.player().getUUID(), ticket, weight,
                () -> stillHolds(authority) && candidate.frame().isCurrent() && !placeholder.isRemoved()
                        && authority.level().hasChunkAt(before.position())
                        && authority.level().getBlockEntity(before.position()) == placeholder);
    }
    public static boolean belongsTo(Object placeholder, ServerPlayer player) {
        Ticket ticket = PENDING.get(placeholder);
        return ticket != null && ticket.authority.player() == player;
    }
    public static Claim beginCommit(BlockEntity placeholder) {
        Ticket ticket = PENDING.take(placeholder);
        return ticket == null ? null : new Claim(ticket);
    }
    /**
     * The terminal commit of a claimed effect. GADGET-DELAYED: returns whether its action was handed to storage (true
     * once {@link #publishCommitted} returned, whatever storage answered: storage counts its own refusals); false on
     * every early return, for a null or already finished claim, and when EventTrail failed (the failure is reported and
     * warned). The Building Gadgets render hook counts a block change with no handed action ({@link #noteTerminal});
     * the Mining Gadgets callers ignore the result.
     */
    public static boolean finish(Claim claim, boolean mutationSucceeded, boolean returnedNormally) {
        if (claim == null || claim.finished) return false;
        claim.finished = true;
        Ticket ticket = claim.ticket;
        Authority authority = ticket.authority;
        if (!mutationSucceeded || !returnedNormally || !stillHolds(authority)
                || !authority.level().hasChunkAt(ticket.before.position())) return false;
        try {
            if (authority.level().getBlockEntity(ticket.before.position()) == ticket.placeholder) return false;
            BlockState after = authority.level().getBlockState(ticket.before.position());
            if (after.equals(ticket.before.blockState())) return false;
            if (after.is(ticket.placeholder.getBlockState().getBlock())) return false;
            // The admitted builder may adjust state properties or fail survival to air;
            // replacement by an unrelated block is not this ticket's placement proof.
            if (ticket.kind != Kind.BREAK && !after.isAir() && !after.is(ticket.target.getBlock())) return false;
            var physical = ticket.frame.currentLocation().orElse(null);
            if (physical == null) return false;
            long now = System.currentTimeMillis();
            var manager = EventTrailManager.getInstance();
            // A2-44: a REPLACE's break and place rows are one action, built first and published once, all or none.
            Supplier<AuditRecord> breakRow = ticket.kind != Kind.PLACE && !ticket.before.blockState().isAir()
                    ? () -> GadgetBlockEvidenceNormalizer.committed(now, AuditAction.BLOCK_BREAK, authority.actor,
                            physical.dimensionId(), physical.position(), ticket.before.blockState(), ticket.before.blockEntityEvidence())
                    : null;
            Supplier<AuditRecord> placeRow = ticket.kind != Kind.BREAK && !after.isAir()
                    ? () -> GadgetBlockEvidenceNormalizer.committed(now, AuditAction.BLOCK_PLACE, authority.actor,
                            physical.dimensionId(), physical.position(), after,
                            BlockEntityEvidenceCapturer.capture(authority.level(), ticket.before.position(), manager.canAcceptAuditWrite()))
                    : null;
            publishCommitted(manager::tryLogAll, breakRow, placeRow);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            // Observation never changes the gadget's result or grants fallback authority. Nothing of the action was
            // handed to storage before the failure, so none of its rows was written; the operator is told (rate-limited).
            AdapterFailSafe.report(authority.key, "bridge:GadgetAuditRuntime.finish", failure);
            actionNotRecorded(failure);
            return false;
        }
    }

    /**
     * GADGET-DELAYED: called by the Building Gadgets render hook right after {@link #finish}, at a placeholder's own
     * final write. Counts the block once when that write changed it while no row was handed to storage for it
     * ({@link #unrecordedChange}): a ticket refused at the commit, a use that never got one (a limit, a mode EventTrail
     * does not record, a use it did not admit) or an EventTrail failure. Reads the block only in a loaded chunk of a
     * server level (the write just happened there). Never throws into the mod: a failure is reported under the
     * Building Gadgets key.
     *
     * @param placeholder      the RenderBlockBE whose final write this was
     * @param placeholderBlock the placeholder's own block, read before the write
     * @param source           the placeholder's source state (the block before the gadget), read before the write
     */
    public static void noteTerminal(BlockEntity placeholder, Block placeholderBlock, BlockState source,
            boolean handed, boolean succeeded) {
        try {
            if (handed || !succeeded || placeholder == null || !(placeholder.getLevel() instanceof ServerLevel level)) return;
            BlockPos position = placeholder.getBlockPos();
            if (!level.hasChunkAt(position)) return;
            if (unrecordedChange(handed, succeeded, level.getBlockState(position), placeholderBlock, source)) {
                UNRECORDED_BUILDING_GADGET_CHANGES.increment();
            }
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.BUILDING_GADGETS, "bridge:GadgetAuditRuntime.noteTerminal", failure);
        }
    }

    /**
     * GADGET-DELAYED: whether a placeholder's own final write left a real block change with no row handed to storage.
     * Not when a row was handed (storage counts its own refusals), not when the write did not succeed, not while the
     * placeholder block is still there, and not when the block is back to its source (a failed survival to air in a
     * build). A null source counts. Pure; package-private for its table test.
     */
    static boolean unrecordedChange(boolean handed, boolean succeeded, BlockState after, Block placeholderBlock,
            BlockState source) {
        return !handed && succeeded && after != null && !after.is(placeholderBlock) && !after.equals(source);
    }

    /**
     * GADGET-DELAYED (review-01 MINOR-1): called by the Building Gadgets render hook when a placeholder is removed,
     * reconfigured or loaded. Counts the block once when something other than the placeholder's own final write
     * removed the placeholder (a player, a machine, a command, an explosion) after the gadget had already taken away a
     * non-air source block: that removal by the gadget's user then has no row ({@link #unrecordedRemoval}). Reads the
     * block only through {@code getChunkNow}, so it never loads a chunk; a chunk unload still sees the placeholder, or
     * no chunk, and is not counted here (the block is counted at its later final write). Never throws into the mod.
     *
     * @param placeholder      the RenderBlockBE
     * @param placeholderBlock the placeholder's own block
     * @param source           the placeholder's source state (the block before the gadget)
     * @param ownFinalWrite    whether the placeholder's own final write is running ({@link #noteTerminal} counts it)
     */
    public static void noteRemoved(BlockEntity placeholder, Block placeholderBlock, BlockState source,
            boolean ownFinalWrite) {
        try {
            if (ownFinalWrite || placeholder == null || !(placeholder.getLevel() instanceof ServerLevel level)) return;
            BlockPos position = placeholder.getBlockPos();
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(position.getX()), SectionPos.blockToSectionCoord(position.getZ()));
            BlockState now = chunk == null ? null : chunk.getBlockState(position);
            if (unrecordedRemoval(ownFinalWrite, now, placeholderBlock, source)) {
                UNRECORDED_BUILDING_GADGET_CHANGES.increment();
            }
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.BUILDING_GADGETS, "bridge:GadgetAuditRuntime.noteRemoved", failure);
        }
    }

    /**
     * GADGET-DELAYED (review-01 MINOR-1): whether a placeholder was removed before its own final write after the gadget
     * had taken away a real block. Not during the placeholder's own final write, not with no loaded chunk (null), not
     * while the placeholder block is still at the position (a chunk unload, a reconfiguration, a load), and not when
     * the source was air or unknown (a build into air leaves nothing of the gadget's behind). Pure; package-private for
     * its table test.
     */
    static boolean unrecordedRemoval(boolean ownFinalWrite, BlockState now, Block placeholderBlock, BlockState source) {
        return !ownFinalWrite && now != null && !now.is(placeholderBlock) && source != null && !source.isAir();
    }

    /**
     * GADGET-DELAYED: the Building Gadgets block changes counted by {@link #noteTerminal} and {@link #noteRemoved}
     * since this process started (for the /eventtrail status line).
     */
    public static long unrecordedBuildingGadgetChanges() { return UNRECORDED_BUILDING_GADGET_CHANGES.sum(); }

    /**
     * GADGET-DELAYED: the commit-side test of an authority. A Building Gadgets authority keeps the admission made at
     * the use ({@link Authority#admitted}); every other key keeps the live-player test ({@link Authority#current}).
     */
    private static boolean stillHolds(Authority authority) {
        return authority.delayed() ? authority.admitted() : authority.current();
    }

    /**
     * A2-44: one committed gadget effect as one action, all or none: the break row (if any) and then the place row
     * (if any). A REPLACE is one action of two rows; a BREAK or a PLACE is the same batch with one row; a null
     * supplier means the effect has no such row, and with neither row nothing is published. Every row is built
     * inside the batch before anything is handed to {@code sink}, so a row whose build throws discards the whole
     * action and the failure propagates to the caller's guard. Package-private for GadgetReplacePublishTest.
     *
     * @return whether storage took the whole action (true when there was no row to publish)
     */
    static boolean publishCommitted(Predicate<List<AuditRecord>> sink, Supplier<AuditRecord> breakRow,
            Supplier<AuditRecord> placeRow) {
        return ActionRecordBatch.collect(sink, batch -> {
            if (breakRow != null) batch.add(breakRow.get());
            if (placeRow != null) batch.add(placeRow.get());
        });
    }

    /** A2-44: a gadget effect that failed before its one publish is not recorded at all; warn, rate-limited. */
    private static void actionNotRecorded(Throwable failure) {
        try {
            NOT_RECORDED.acquire().ifPresent(emission -> LogManager.getLogger("eventtrail").warn(
                    "Could not record a gadget block action ({}); none of its rows was written; "
                            + "suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
        } catch (RuntimeException | LinkageError ignored) {
            // A warning must never change the gadget's result.
        }
    }
    public static void invalidate(Object placeholder) { PENDING.remove(placeholder); }
    public static void invalidateOperation(Authority authority) {
        if (authority != null) authority.input.invalid = true;
        PENDING.prune();
    }
    /**
     * R2-inner lifecycle rule: each registration carries its own key. While that key is OFF or its hook family is
     * stopped its {@code tick} is skipped; its {@code clear} and {@code logout} always run. A failure of any of them
     * is reported to the fail-safe and never thrown.
     */
    public static void registerLifecycle(AdapterKey key, Runnable tick, Runnable clear, Consumer<UUID> logout) {
        if (LIFECYCLES.size() >= 4) throw new IllegalStateException("Gadget lifecycle producer limit");
        LIFECYCLES.add(new Lifecycle(java.util.Objects.requireNonNull(key, "key"), tick, clear, logout));
    }
    /**
     * R2-inner: a failure of runtime code that runs before any gadget is known (the input boundary, the gadget check,
     * the ledger sweep) is reported once under each gadget key that is registered with this runtime (each mod's
     * status stays true), and under no key whose mod is not registered; with nothing registered nothing is reported.
     * A key registered twice is reported once. Allocates nothing, so it is safe at the edge of the stack.
     * Package-private for GadgetPreGadgetReportTest.
     */
    static void reportPreGadget(String site, Throwable failure) {
        for (int index = 0; index < LIFECYCLES.size(); index++) {
            AdapterKey key = LIFECYCLES.get(index).key;
            boolean seen = false;
            for (int earlier = 0; earlier < index; earlier++) {
                if (LIFECYCLES.get(earlier).key == key) { seen = true; break; }
            }
            if (!seen) AdapterFailSafe.report(key, site, failure);
        }
    }
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        // The ledger sweep touches only EventTrail and vanilla state (no gadget member), so it is cleanup like
        // clear: it always runs and is not skipped with a gadget key. A failure is reported under every registered
        // gadget key (reportPreGadget), not thrown.
        try {
            PENDING.prune();
        } catch (Throwable failure) {
            reportPreGadget("lifecycle:GadgetAuditRuntime.prune", failure);
        }
        for (Lifecycle lifecycle : LIFECYCLES) {
            AdapterFailSafe.run(lifecycle.key, "lifecycle:GadgetAuditRuntime.tick", lifecycle.tick);
        }
    }
    /**
     * Stop/restart cleanup; no pending effect is completed. af-3 (Owner decision R-M1/R-m6 b, re-check m4): each
     * pending effect dropped here (an in-flight gadget action whose rows would be written at its commit) is counted
     * once as a skipped capture attempt.
     */
    public static void clear() {
        Input input = INPUT.get(); if (input != null) input.invalid = true;
        INPUT.remove();
        int dropped = PENDING.clear();
        if (dropped > 0) EventTrailManager.getInstance().countDroppedCaptures(dropped);
        for (Lifecycle lifecycle : LIFECYCLES) {
            AdapterFailSafe.contain(lifecycle.key, "lifecycle:GadgetAuditRuntime.clear", lifecycle.clear);
        }
    }
    /**
     * GADGET-DELAYED: a logout no longer drops the player's pending effects. A Building Gadgets placement he ordered
     * can finish after he left and is credited from the use ({@link Authority#admitted}); a Mining Gadgets effect is
     * dropped by the Mining Gadgets logout lifecycle (invalidateOperation) or by the next prune, because its live-player
     * test then fails. The lifecycles' logout steps always run.
     */
    public static void onLogout(UUID player) {
        for (Lifecycle lifecycle : LIFECYCLES) {
            AdapterFailSafe.contain(lifecycle.key, "lifecycle:GadgetAuditRuntime.logout", () -> lifecycle.logout.accept(player));
        }
    }
    public static int pendingEffects() { return PENDING.size(); }
    public static long retainedMetadataCharge() { return PENDING.bytes(); }
    static boolean withinLifetime(long started, long now) {
        long elapsed = now - started;
        return elapsed >= 0 && elapsed < MAX_LIFETIME_NANOS;
    }
    private record Lifecycle(AdapterKey key, Runnable tick, Runnable clear, Consumer<UUID> logout) {}
    public record Candidate(Authority authority, BlockAuditPreimage preimage, AuditBlockLocations.Frame frame) {}
    private record Ticket(Authority authority, BlockAuditPreimage before, BlockEntity placeholder, BlockState target, Kind kind, AuditBlockLocations.Frame frame) {}
    public static final class Claim {
        private final Ticket ticket; private boolean finished;
        private Claim(Ticket ticket) { this.ticket = ticket; }
        public Authority authority() { return ticket.authority; }
        public BlockAuditPreimage preimage() { return ticket.before; }
        public BlockState expectedTarget() { return ticket.target; }
        public Kind kind() { return ticket.kind; }
    }
    public static final class Authority {
        private final Input input; private final ActorProvenance actor; private final AdapterKey key;
        private Authority(Input input, ActorProvenance actor, AdapterKey key) {
            this.input = input; this.actor = actor; this.key = key;
        }
        public ServerPlayer player() { return input.player; }
        public ServerLevel level() { return input.level; }
        public UUID operationId() { return input.operation; }
        /**
         * The live-use check: the admitted use, and its player object still live, listed and in the use's level. Used
         * by Mining Gadgets commits (GADGET-DELAYED: no Building Gadgets commit uses it; Building Gadgets admission goes
         * through {@link GadgetAuditRuntime#currentInputActor}).
         */
        public boolean current() {
            try {
            return input.successful && !input.invalid && withinLifetime(input.started, System.nanoTime())
                    && live(input.player) && input.player.level() == input.level
                    && EventTrailManager.getInstance().recordingSnapshot().generation() == input.generation;
            } catch (RuntimeException | LinkageError unavailable) {
                AdapterFailSafe.report(key, "bridge:GadgetAuditRuntime.authorityCurrent", unavailable);
                return false;
            }
        }
        /**
         * GADGET-DELAYED: whether the use admitted at its real, live, listed input still holds, whatever became of its
         * player object since: the same tests as {@link #current}, in its order, without the player-object and
         * player-level tests (successful, not invalid, the 120 s lifetime, the server thread, the counted readiness
         * gate, the recording generation). It never asks whether the player is listed, so it never counts an unlisted
         * player. Failure handling as current(): reported under the key, false.
         */
        boolean admitted() {
            try {
            return input.successful && !input.invalid && withinLifetime(input.started, System.nanoTime())
                    && input.level.getServer().isSameThread() && EventTrailManager.getInstance().admitCapture()
                    && EventTrailManager.getInstance().recordingSnapshot().generation() == input.generation;
            } catch (RuntimeException | LinkageError unavailable) {
                AdapterFailSafe.report(key, "bridge:GadgetAuditRuntime.authorityAdmitted", unavailable);
                return false;
            }
        }
        /**
         * GADGET-DELAYED: Building Gadgets places an admitted use's blocks later, on its own schedule, into the queue's
         * level, for the listed player with the queue's UUID (BG2 1.3.9 and 21.1.1: ServerTickHandler, RenderBlockBE,
         * RenderBlock and ServerBuildList are byte-identical), so its tickets keep the admission made at the use. Mining
         * Gadgets commits only inside the player's live use tick, so its checks keep the live-player test.
         */
        private boolean delayed() { return key == AdapterKey.BUILDING_GADGETS; }
    }
    private static final class Input {
        final ServerPlayer player; final ServerLevel level; final UUID operation = UUID.randomUUID();
        final long started = System.nanoTime(), generation;
        boolean successful, invalid;
        Input(ServerPlayer player) {
            this.player = player; this.level = player.serverLevel();
            this.generation = EventTrailManager.getInstance().recordingSnapshot().generation();
        }
    }
    public static final class InputBoundary implements AutoCloseable {
        private final Input input; private boolean closed;
        private InputBoundary(Input input) { this.input = input; }
        public void success() { if (input != null && INPUT.get() == input && !input.invalid) input.successful = true; }
        @Override public void close() {
            if (closed || input == null) return; closed = true;
            if (INPUT.get() != input || !input.successful) input.invalid = true;
            INPUT.remove();
        }
    }
}
