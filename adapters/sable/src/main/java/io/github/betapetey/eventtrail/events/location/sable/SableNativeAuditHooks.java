package io.github.betapetey.eventtrail.events.location.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.function.BiFunction;

/**
 * Observers never modify Sable's pose, membership, gameplay, or permissions.
 *
 * <p>Every state-keeping path fails conservatively before a failure reaches the
 * hook guard: observe taints the known sub-level, a duplicate allocation taints
 * both sides, and a source-frame invalidation that fails makes every frame
 * non-current. Runtime and linkage failures of an observation stay contained
 * here as before; any other throwable is re-thrown to the guard after the taint.</p>
 */
public final class SableNativeAuditHooks {
    private SableNativeAuditHooks() { }

    public static void observe(SubLevel subLevel) {
        try {
            if (subLevel instanceof ServerSubLevel server && server.getLevel().getServer() != null
                    && server.getLevel().getServer().isSameThread()) observe(subLevel, subLevel.logicalPose());
        } catch (RuntimeException | LinkageError failure) {
            taint(subLevel);
            AdapterFailSafe.report(AdapterKey.SABLE_FRAME, "bridge:SableNativeAuditHooks.observe", failure);
        } catch (Throwable failure) {
            taint(subLevel);
            throw failure;
        }
    }

    public static void observe(SubLevel subLevel, Pose3dc pose) {
        try {
            if (subLevel instanceof ServerSubLevel server && server.getLevel().getServer() != null
                    && server.getLevel().getServer().isSameThread() && subLevel instanceof SableAuditCarrier carrier
                    && carrier.eventtrail$sableAuditState() != null) carrier.eventtrail$sableAuditState().observe(pose);
        } catch (RuntimeException | LinkageError failure) {
            taint(subLevel);
            AdapterFailSafe.report(AdapterKey.SABLE_FRAME, "bridge:SableNativeAuditHooks.observePose", failure);
        } catch (Throwable failure) {
            taint(subLevel);
            throw failure;
        }
    }

    public static void duplicate(SubLevel prior, SubLevel added) {
        if (prior != null && added != null && prior != added) {
            try {
                taint(prior);
            } finally {
                taint(added);
            }
        }
    }

    /**
     * Moves the context epoch of the sub-level that owns the assembly anchor. If
     * the plot or sub-level cannot be resolved because of a failure, the target is
     * unknown, so every frame becomes non-current before the failure propagates.
     */
    public static void invalidateSourceFrame(ServerLevel level, BlockPos anchor) {
        invalidateSourceFrame(level, anchor, SableNativeAuditHooks::sourceSubLevel);
    }

    /** Test seam: {@code lookup} resolves the anchor's sub-level (null when there is none). */
    static void invalidateSourceFrame(ServerLevel level, BlockPos anchor,
            BiFunction<ServerLevel, BlockPos, SubLevel> lookup) {
        boolean invalidated = false;
        try {
            invalidateContext(lookup.apply(level, anchor));
            invalidated = true;
        } finally {
            if (!invalidated) SableAuditState.invalidateEveryFrame();
        }
    }

    public static void invalidateContext(SubLevel subLevel) {
        if (subLevel instanceof SableAuditCarrier carrier && carrier.eventtrail$sableAuditState() != null)
            carrier.eventtrail$sableAuditState().invalidateContext();
    }

    private static SubLevel sourceSubLevel(ServerLevel level, BlockPos anchor) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        LevelPlot plot = container.getPlot(anchor.getX() >> 4, anchor.getZ() >> 4);
        return plot == null ? null : plot.getSubLevel();
    }

    private static void taint(SubLevel subLevel) {
        if (subLevel instanceof SableAuditCarrier carrier && carrier.eventtrail$sableAuditState() != null)
            carrier.eventtrail$sableAuditState().taint();
    }
}
