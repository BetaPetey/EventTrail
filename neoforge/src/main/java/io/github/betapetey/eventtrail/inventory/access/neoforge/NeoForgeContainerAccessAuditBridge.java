package io.github.betapetey.eventtrail.inventory.access.neoforge;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessActor;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessEvidence;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessMetrics;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessNormalizer;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessRuntimeDiagnostics;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessSessionTracker;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessTargetSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Thin NeoForge lifecycle owner for loader-neutral container-access sessions. */
public final class NeoForgeContainerAccessAuditBridge {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final ContainerAccessSessionTracker SESSIONS =
            new ContainerAccessSessionTracker();
    private static final AtomicLong DUPLICATE_OPENS_SUPPRESSED = new AtomicLong();
    private static final AtomicLong LAST_EMIT_FAILURE_LOG_MILLIS = new AtomicLong();
    private static final long EMIT_FAILURE_LOG_INTERVAL_MILLIS = 60_000L;

    static {
        ContainerAccessRuntimeDiagnostics.installProvider(
                NeoForgeContainerAccessAuditBridge::metrics);
    }

    private NeoForgeContainerAccessAuditBridge() {}

    public static boolean trackingEnabled() {
        return EventTrailConfig.TRACK_CONTAINER_ACCESS.get();
    }

    /**
     * Checks duplicate delivery before interaction evidence is consumed or the
     * independent transaction session is touched.
     */
    public static boolean suppressDuplicateOpen(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (!trackingEnabled() || !isAuthoritativeOpenBoundary(player, menu)) return false;
        if (!SESSIONS.isDuplicate(player.getUUID(), menu)) return false;
        DUPLICATE_OPENS_SUPPRESSED.incrementAndGet();
        return true;
    }

    /**
     * A newly installed menu makes any different prior menu impossible to
     * close authoritatively. Cleanup is deliberately not a CLOSE fact.
     */
    public static void abandonSupersededSession(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (!trackingEnabled() || !isInstalledMenuBoundary(player, menu)) return;
        SESSIONS.abandonIfDifferent(player.getUUID(), menu);
    }

    /**
     * Whether this open is one container access observes (tracking on, authoritative boundary, real player),
     * without EventTrail's readiness. The caller then asks the counted capture gate
     * {@link EventTrailManager#admitCaptureWithWriteCapacity} once (af-2, Owner decision R-M1/R-m6 b).
     */
    public static boolean observesOpen(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (!trackingEnabled() || !isAuthoritativeOpenBoundary(player, menu)) return false;
        boolean synthetic = EventTrailEventHandler.isFakePlayer(player);
        // GF-FAKEPLAYER-ID (B51): an open is admitted only for the PlayerList's own object for that UUID.
        return !synthetic && EventTrailEventHandler.isAdmittedPlayer(player);
    }

    public static void observeOpen(
            ServerPlayer player,
            AbstractContainerMenu menu,
            ContainerAccessTargetSnapshot target) {
        observeOpen(player, menu, target, () -> true);
    }

    public static void observeOpen(
            ServerPlayer player, AbstractContainerMenu menu,
            ContainerAccessTargetSnapshot target, BooleanSupplier currentAuthority) {
        observeOpen(player, menu, target, currentAuthority, () -> { });
    }

    /**
     * As {@link #observeOpen(ServerPlayer, AbstractContainerMenu, ContainerAccessTargetSnapshot, BooleanSupplier)},
     * with a callback the session tracker runs once when this session's close finds {@code currentAuthority} false
     * (its CLOSE row is then not written). It is how an access-point row's close-time warning is sent.
     */
    public static void observeOpen(
            ServerPlayer player, AbstractContainerMenu menu,
            ContainerAccessTargetSnapshot target, BooleanSupplier currentAuthority, Runnable onAuthorityLost) {
        if (!observesOpen(player, menu) || target == null) return;
        // af-2: a skipped OPEN row is counted here once; the caller only reaches this after its own gate passed.
        if (!EventTrailManager.getInstance().admitCaptureWithWriteCapacity()) return;

        boolean synthetic = EventTrailEventHandler.isFakePlayer(player);
        ContainerAccessActor actor = new ContainerAccessActor(
                player.getUUID(), player.getGameProfile().getName(), synthetic);
        ContainerAccessSessionTracker.OpenResult result = SESSIONS.open(
                actor, menu, target, System.currentTimeMillis(), currentAuthority,
                onAuthorityLost == null ? () -> { } : onAuthorityLost);
        if (result.evidence().isEmpty()) return;

        if (!emit(result.evidence().orElseThrow())) {
            // A failed OPEN row cannot establish a later CLOSE fact.
            SESSIONS.abandon(player.getUUID());
        }
    }

    public static void observeClose(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (!trackingEnabled() || !isAuthoritativeCloseBoundary(player, menu)) return;
        SESSIONS.close(player.getUUID(), menu, System.currentTimeMillis())
                .ifPresent(NeoForgeContainerAccessAuditBridge::emit);
    }

    public static void unresolvedTarget() {
        if (trackingEnabled()) SESSIONS.unresolvedTarget();
    }

    /** Logout is cleanup only; it is not evidence that a menu close completed. */
    public static void onPlayerLogout(UUID playerId) {
        SESSIONS.abandon(playerId);
    }

    /**
     * Server stop/restart cleanup never fabricates CLOSE evidence. af-3 (Owner decision R-M1/R-m6 b, re-check m4):
     * each open session dropped here is counted once as a skipped capture attempt (its CLOSE row is not written).
     */
    public static void clearRuntimeState() {
        int dropped = SESSIONS.clear();
        if (dropped > 0) EventTrailManager.getInstance().countDroppedCaptures(dropped);
    }

    /** Starts a new server lifecycle with bounded zeroed diagnostic counters. */
    public static void resetRuntimeState() {
        SESSIONS.reset();
        DUPLICATE_OPENS_SUPPRESSED.set(0L);
        LAST_EMIT_FAILURE_LOG_MILLIS.set(0L);
    }

    public static ContainerAccessMetrics metrics() {
        ContainerAccessSessionTracker.Metrics core = SESSIONS.metrics();
        return new ContainerAccessMetrics(
                core.activeSessions(), core.opened(), core.closed(),
                core.duplicateOpens() + DUPLICATE_OPENS_SUPPRESSED.get(),
                core.unresolvedTargets(), core.unmatchedCloses(), core.abandoned());
    }

    static boolean authoritativeBoundary(
            boolean serverThread,
            boolean activeMenuMatches,
            boolean playerInventoryMenu) {
        return serverThread && activeMenuMatches && !playerInventoryMenu;
    }

    private static boolean isAuthoritativeOpenBoundary(
            ServerPlayer player, AbstractContainerMenu menu) {
        return isInstalledMenuBoundary(player, menu) && menu != player.inventoryMenu;
    }

    private static boolean isInstalledMenuBoundary(
            ServerPlayer player, AbstractContainerMenu menu) {
        return player != null
                && menu != null
                && player.getServer() != null
                && player.getServer().isSameThread()
                && player.containerMenu == menu;
    }

    private static boolean isAuthoritativeCloseBoundary(
            ServerPlayer player, AbstractContainerMenu menu) {
        // NeoForge posts Close from ServerPlayer#doCloseContainer while the
        // exact closing menu is still installed as containerMenu.
        return isAuthoritativeOpenBoundary(player, menu);
    }

    private static boolean emit(ContainerAccessEvidence evidence) {
        EventTrailManager manager = EventTrailManager.getInstance();
        // af-2 (Owner decision R-M1/R-m6 b): the same answer as the readiness and write-room checks before (the
        // write-room check includes readiness); a skipped OPEN or CLOSE row is counted.
        if (!manager.admitCaptureWithWriteCapacity()) return false;
        try {
            return manager.tryLog(ContainerAccessNormalizer.toAuditRecord(evidence));
        } catch (RuntimeException | LinkageError failure) {
            if (claimFailureDiagnostic(
                    LAST_EMIT_FAILURE_LOG_MILLIS,
                    System.currentTimeMillis(),
                    EMIT_FAILURE_LOG_INTERVAL_MILLIS)) {
                LOGGER.error(
                        "Failed to record bounded container-access evidence for provider {} ({})",
                        evidence.target().providerId(), failure.getClass().getSimpleName());
            }
            return false;
        }
    }

    static boolean claimFailureDiagnostic(
            AtomicLong lastReportedMillis, long nowMillis, long intervalMillis) {
        if (lastReportedMillis == null || intervalMillis <= 0L) return false;
        while (true) {
            long previous = lastReportedMillis.get();
            if (previous != 0L && nowMillis - previous < intervalMillis) return false;
            if (lastReportedMillis.compareAndSet(previous, nowMillis)) return true;
        }
    }

}
