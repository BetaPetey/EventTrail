package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.storage.ILogStorage;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.Supplier;

/** Lifecycle-controlled non-storage services and runtime session state. */
interface EventTrailRuntimeServices {
    /** Safe immutable facts only; implementations must not perform storage I/O. */
    default void recordingChanged(RecordingSnapshot snapshot) {}
    default void bindRecordingSource(Supplier<RecordingSnapshot> source) {}
    default void bindServer(MinecraftServer server) {}
    default void playerLoggedIn(MinecraftServer server, UUID playerId) {}
    default void retentionPlayersChanged(MinecraftServer server, int humanPlayers) {}
    default void serverStarted(MinecraftServer server, int humanPlayers) {}
    default void serverTick(MinecraftServer server) {}

    void resetBeforeStart();

    void startCompatibility();

    void stopCompatibilityAndSessions();

    void startQueryWork();

    void stopQueryWork();

    void startMaintenance(ILogStorage storage, int retentionDays);

    void stopMaintenance();

    void startStatus(ILogStorage storage);

    void stopStatus();

    void showUnavailableStatus();

    void clearPlayerState(UUID playerId);
}
