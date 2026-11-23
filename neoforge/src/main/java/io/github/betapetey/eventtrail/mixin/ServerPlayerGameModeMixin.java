package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.BlockEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.PlayerBreakAuditSession;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.events.parts.PartRemovalAuditBridge;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Records player breaks only at Minecraft's exact committed removal boundary. */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @Unique
    private static final Logger EVENTTRAIL_LOGGER = LogManager.getLogger("eventtrail");

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    /** Server-thread-owned and restored around nested destroyBlock invocations. */
    @Unique
    private PlayerBreakAuditSession eventtrail$activeBreak;
    @Unique
    private AuditBlockLocations.Frame eventtrail$breakFrame;

    @WrapMethod(
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$recordCommittedBreak(
            BlockPos position,
            Operation<Boolean> original) {
        // GF-SCOPE (p2gf-5 #1): a survival break finished in ServerPlayerGameMode#tick runs outside every packet
        // handler, so destroyBlock opens its own scope (inside an open scope it only marks the break session).
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openDestroy(this.player, position)) {
            return eventtrail$recordCommittedBreakInScope(position, original);
        }
    }

    @Unique
    private boolean eventtrail$recordCommittedBreakInScope(
            BlockPos position,
            Operation<Boolean> original) {
        PlayerBreakAuditSession session = new PlayerBreakAuditSession(position);
        PlayerBreakAuditSession previous = this.eventtrail$activeBreak;
        AuditBlockLocations.Frame previousFrame = this.eventtrail$breakFrame;
        AuditBlockLocations.Frame frame = AuditBlockLocations.capture(this.level, position).orElse(null);
        this.eventtrail$breakFrame = frame;
        this.eventtrail$activeBreak = session;

        boolean completedNormally = false;
        boolean destroySucceeded = false;
        try {
            destroySucceeded = original.call(position);
            completedNormally = true;
            return destroySucceeded;
        } finally {
            this.eventtrail$activeBreak = previous;
            this.eventtrail$breakFrame = previousFrame;
            if (!completedNormally) {
                session.abort();
            } else {
                eventtrail$finishBreak(session, position, destroySucceeded, frame);
            }
        }
    }

    @WrapOperation(
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/CommonHooks;fireBlockBreak"
                            + "(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/level/GameType;"
                            + "Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)"
                            + "Lnet/neoforged/neoforge/event/level/BlockEvent$BreakEvent;"),
            require = 1,
            expect = 1,
            allow = 1)
    private BlockEvent.BreakEvent eventtrail$observeFinalBreakEventDecision(
            Level level,
            GameType gameType,
            ServerPlayer player,
            BlockPos position,
            BlockState state,
            Operation<BlockEvent.BreakEvent> original) {
        BlockEvent.BreakEvent event = original.call(level, gameType, player, position, state);
        PlayerBreakAuditSession session = this.eventtrail$activeBreak;
        if (session == null || event.isCanceled() || !EventTrailManager.getInstance().admitCapture()) {
            return event;
        }

        try {
            NeoForgePlayerProvenance.capture(
                            player,
                            ActorProvenance.Causality.DIRECT,
                            null)
                    .ifPresent(actor -> session.acceptBreakEvent(
                            actor, level.dimension().location(), position));
        } catch (RuntimeException failure) {
            session.abort();
            EVENTTRAIL_LOGGER.error(
                    "Could not capture EventTrail block-break actor evidence at {} ({})",
                    position,
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
        return event;
    }

    @WrapOperation(
            method = "removeBlock(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Z)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;onDestroyedByPlayer"
                            + "(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/entity/player/Player;Z"
                            + "Lnet/minecraft/world/level/material/FluidState;)Z"),
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$observeRemovalResult(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            boolean canHarvest,
            FluidState fluidState,
            Operation<Boolean> original) {
        PlayerBreakAuditSession session = this.eventtrail$activeBreak;
        BlockAuditPreimage preimage = null;
        if (session != null && player != null) {
            try {
                BlockState liveState = level.getBlockState(position);
                if (this.eventtrail$breakFrame != null && this.eventtrail$breakFrame.isCurrent()
                        && session.beginRemovalBoundary(
                        level.dimension().location(),
                        position,
                        player.getUUID(),
                        liveState,
                        state)) {
                    preimage = BlockAuditPreimage.capture(
                            System.currentTimeMillis(),
                            level,
                            position,
                            liveState,
                            EventTrailManager.getInstance().canAcceptAuditWrite());
                }
            } catch (RuntimeException failure) {
                session.abort();
                EVENTTRAIL_LOGGER.error(
                        "Could not capture EventTrail block-break preimage at {} ({})",
                        position,
                        EventTrailDiagnostics.safeFailureSummary(failure));
            }
        }

        // Parts A: a part removal inside the mod's call is bracketed only after the existing checks passed and a
        // preimage exists, outside the try above, so the bridge can never abort or skip the existing break row.
        PartRemovalAuditBridge.Removal parts = (session != null && preimage != null)
                ? PartRemovalAuditBridge.beginRemoval(level, position, player, preimage, this.eventtrail$breakFrame)
                : PartRemovalAuditBridge.Removal.NOOP;
        boolean returned = false;
        boolean removed;
        try {
            removed = original.call(state, level, position, player, canHarvest, fluidState);
            returned = true;
        } finally {
            if (!returned) parts.abandon();
        }
        try {
            if (session != null && preimage != null) {
                try {
                    // GF-BLOCKIDENTITY: the state right after the removal step decides whether the Block was removed
                    // (a later same-call re-set, such as a replant, does not hide a real break).
                    session.recordRemovalResult(preimage, removed, level.getBlockState(position));
                } catch (RuntimeException failure) {
                    session.abort();
                    EVENTTRAIL_LOGGER.error(
                            "Could not retain EventTrail block-break removal evidence at {} ({})",
                            position,
                            EventTrailDiagnostics.safeFailureSummary(failure));
                }
            }
        } finally {
            // After the existing result, on every path (recheck-02 R2-1); it catches every throwable itself.
            parts.finish(removed);
        }
        return removed;
    }

    @Unique
    private void eventtrail$finishBreak(
            PlayerBreakAuditSession session,
            BlockPos position,
            boolean destroySucceeded,
            AuditBlockLocations.Frame frame) {
        try {
            if (frame == null || !frame.isCurrent()) { session.abort(); return; }
            session.complete(
                            this.level.dimension().location(),
                            position,
                            this.level.getBlockState(position),
                            destroySucceeded)
                    .flatMap(evidence -> frame.currentLocation()
                            .map(location -> BlockEvidenceNormalizer.toAuditRecord(evidence, location)))
                    .ifPresent(entry -> EventTrailManager.getInstance().log(entry));
        } catch (RuntimeException failure) {
            session.abort();
            EVENTTRAIL_LOGGER.error(
                    "Could not finalize EventTrail block-break evidence at {} ({})",
                    position,
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }
}
