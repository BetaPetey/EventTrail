package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Stable call-site facade over EventTrail's explicit server runtime coordinator.
 *
 * <p>The facade has no independently mutable storage or readiness state. Its
 * eagerly constructed coordinator owns and atomically publishes each runtime.</p>
 */
public final class EventTrailManager {
    private static final EventTrailManager INSTANCE = new EventTrailManager(
            EventTrailRuntimeCoordinator.production(EventTrailConfig.LOG_RETENTION_DAYS::get));

    private final EventTrailRuntimeCoordinator coordinator;

    private EventTrailManager(EventTrailRuntimeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public static EventTrailManager getInstance() {
        return INSTANCE;
    }

    public EventTrailRuntimeCoordinator.StartResult start(Path eventTrailDirectory) {
        return coordinator.start(eventTrailDirectory);
    }

    public void log(AuditRecord record) {
        tryLog(record);
    }

    /** Returns whether the READY runtime atomically accepted this audit fact. */
    public boolean tryLog(AuditRecord record) {
        PlayerActionScopes.noteWritten(record);
        return coordinator.tryLog(record);
    }

    /** Returns whether the READY runtime accepted every row of this action; on false none was accepted. */
    public boolean tryLogAll(List<AuditRecord> records) {
        PlayerActionScopes.noteWritten(records);
        return coordinator.tryLogAll(records);
    }

    /**
     * Explosion split: one part of an explosion stored in parts, admitted only with the headroom left free; NO_ROOM
     * and REFUSED count nothing (the publisher counts every part not stored in {@link #explosionPartLosses}).
     */
    public ILogStorage.OfferResult tryLogAllWhenRoom(
            List<AuditRecord> records, long retainedBytes, int headroomRows, long headroomBytes) {
        PlayerActionScopes.noteWritten(records);
        return coordinator.tryLogAllWhenRoom(records, retainedBytes, headroomRows, headroomBytes);
    }

    /** Explosion split: parts of explosions stored in parts that were not stored, since this process started. */
    public ExplosionPartLossCounter explosionPartLosses() {
        return coordinator.explosionPartLosses();
    }

    /** Cheap advisory check used before expensive metadata serialization. */
    public boolean canAcceptAuditWrite() {
        return coordinator.canAcceptAuditWrite();
    }

    public ILogStorage getStorage() {
        return coordinator.storage();
    }

    /**
     * Readiness for status, commands and maintenance. Capture points must use {@link #admitCapture} or
     * {@link #admitCaptureWithWriteCapacity} instead, so a skipped capture attempt is counted (af-2).
     */
    public boolean isReady() {
        return coordinator.isReady();
    }

    /**
     * The shared capture gate (af-2, Owner decision R-M1/R-m6 b): exactly {@link #isReady}, and when false one
     * skipped capture attempt is counted. Every capture point that checks readiness before it builds or offers an
     * action calls this once per attempt.
     */
    public boolean admitCapture() {
        return coordinator.admitCapture();
    }

    /** The same counted gate for capture points that also need write room (container access). */
    public boolean admitCaptureWithWriteCapacity() {
        return coordinator.admitCaptureWithWriteCapacity();
    }

    /**
     * af-3 (re-check m4): counts sessions and in-flight actions the stop's cleanup dropped without writing their
     * rows, once each, into the skipped-capture-attempt figure (the same counter as the capture gate).
     */
    public void countDroppedCaptures(int dropped) {
        coordinator.countDroppedCaptures(dropped);
    }

    /**
     * Capture attempts skipped because EventTrail was not ready, plus sessions and in-flight actions dropped at a
     * stop, since this process started (an upper bound).
     */
    public long skippedCaptureAttempts() {
        return coordinator.skippedCaptureAttempts();
    }

    public EventTrailLifecycleState getLifecycleState() {
        return coordinator.state();
    }

    public String getBackendName() {
        return coordinator.backendName();
    }

    public RecordingSnapshot recordingSnapshot() {
        return coordinator.recordingSnapshot();
    }

    /** Audit rows refused because the runtime was not READY, since this process started (af-1 F6). */
    public long notReadyRefusedRows() {
        return coordinator.notReadyRefusedRows();
    }

    public boolean isCurrentRecordingSuccess(EventTrailRuntimeCoordinator.StartResult result) {
        return coordinator.isCurrentRecordingSuccess(result);
    }

    public void clearPlayerRuntimeState(UUID playerId) {
        coordinator.clearPlayerState(playerId);
    }

    public void bindServer(MinecraftServer server) { coordinator.bindServer(server); }
    public void playerLoggedIn(MinecraftServer server, UUID playerId) {
        coordinator.playerLoggedIn(server, playerId);
    }
    public void serverTick(MinecraftServer server) { coordinator.serverTick(server); }
    public void retentionPlayersChanged(MinecraftServer server, int humanPlayers) {
        coordinator.retentionPlayersChanged(server, humanPlayers);
    }
    public void serverStarted(MinecraftServer server, int humanPlayers) {
        coordinator.serverStarted(server, humanPlayers);
    }

    public void shutdown() {
        coordinator.stop();
    }

}
