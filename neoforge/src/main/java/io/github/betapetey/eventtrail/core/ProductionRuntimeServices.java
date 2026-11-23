package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.commands.EventTrailQueryDispatcher;
import io.github.betapetey.eventtrail.commands.EventTrailStatusMonitor;
import io.github.betapetey.eventtrail.commands.ExtendedLookup;
import io.github.betapetey.eventtrail.commands.InspectCommand;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Production bindings for services owned by one server lifecycle. */
final class ProductionRuntimeServices implements EventTrailRuntimeServices {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private final RecordingEpisodes recordingEpisodes = new RecordingEpisodes();
    private Supplier<RecordingSnapshot> recordingSource = EventTrailStatusMonitor::recordingSnapshot;
    private volatile DeliveryBinding administratorBinding;
    private MinecraftServer notificationServer;
    private EventTrailMaintenance.Activity maintenanceActivity;
    private boolean serverFullyStarted;
    private int retentionHumanPlayers;

    /** A bind precedes start: the publication visible at bind belongs to the previous run. */
    private final class DeliveryBinding {
        private final UUID previousRun;
        private final long previousGeneration;
        private UUID run;
        private RecordingAdministratorDelivery delivery;

        DeliveryBinding(RecordingSnapshot previous) {
            previousRun = previous.serverRunId();
            previousGeneration = previous.generation();
        }

        synchronized boolean owns(RecordingSnapshot snapshot) {
            if (snapshot == null || snapshot.generation() <= previousGeneration
                    || snapshot.serverRunId().equals(previousRun)) return false;
            if (run == null) run = snapshot.serverRunId();
            return run.equals(snapshot.serverRunId());
        }

        RecordingSnapshot currentSnapshot() {
            if (administratorBinding != this) return null;
            RecordingSnapshot snapshot = recordingSource.get();
            return owns(snapshot) ? snapshot : null;
        }
    }

    @Override
    public void bindRecordingSource(Supplier<RecordingSnapshot> source) {
        recordingSource = java.util.Objects.requireNonNull(source);
    }

    @Override
    public void bindServer(MinecraftServer server) {
        if (server != notificationServer) {
            serverFullyStarted = false;
            // A real server binding must publish a main-thread player census before
            // maintenance may run. Standalone/unbound storage retains startup-only use.
            retentionHumanPlayers = server == null ? 0 : Integer.MAX_VALUE;
            maintenanceActivity = null;
        }
        bindAdministratorDelivery(server, (episodes, source) -> server == null ? null
                : createAdministratorDelivery(server, episodes, source));
    }

    /** Shared binding boundary; the factory permits offline tests with a real delivery controller. */
    void bindAdministratorDelivery(MinecraftServer server,
            BiFunction<RecordingEpisodes, Supplier<RecordingSnapshot>, RecordingAdministratorDelivery> factory) {
        DeliveryBinding next = new DeliveryBinding(recordingSource.get());
        DeliveryBinding previous = administratorBinding;
        administratorBinding = null;
        if (previous != null) previous.delivery.stop();
        notificationServer = server;
        next.delivery = factory.apply(recordingEpisodes, next::currentSnapshot);
        if (next.delivery != null) administratorBinding = next;
    }

    private RecordingAdministratorDelivery createAdministratorDelivery(
            MinecraftServer server, RecordingEpisodes episodes, Supplier<RecordingSnapshot> source) {
        return new RecordingAdministratorDelivery(episodes,
                source,
                new RecordingAdministratorDelivery.PlayerDirectory() {
                    @Override public int onlineCount() {
                        requireServerThread(server);
                        return server.getPlayerList().getPlayers().size();
                    }
                    @Override public UUID onlinePlayerId(int index) {
                        requireServerThread(server);
                        var players = server.getPlayerList().getPlayers();
                        return index >= 0 && index < players.size() ? players.get(index).getUUID() : null;
                    }
                    @Override public boolean authorizedOnline(UUID playerId) {
                        requireServerThread(server);
                        ServerPlayer player = currentPlayer(server, playerId);
                        return player != null && EventTrailPermissions.hasExactPermission(
                                player.createCommandSourceStack(), EventTrailPermissions.STATUS_PERMISSION);
                    }
                },
                server::execute,
                new RecordingAdministratorDelivery.MessageSink() {
                    @Override public boolean warning(UUID playerId, EventTrailFailure cause) {
                        return enqueue(server, playerId, "§cAUDIT LOGGING DISABLED §7[" + cause.code() + "] §f"
                                + cause.explanation() + " §7Check /eventtrail status for the safe action."
                                + " Events missed while disabled are not backfilled.");
                    }
                    @Override public boolean recovery(UUID playerId) {
                        return enqueue(server, playerId, "§a" + RecordingAnnouncements.recovered());
                    }
                }, System::currentTimeMillis);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Administrator delivery requires the server thread");
    }

    private static ServerPlayer currentPlayer(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null || player.hasDisconnected() || player.connection == null
                || !player.connection.getConnection().isConnected() ? null : player;
    }

    private static boolean enqueue(MinecraftServer server, UUID playerId, String text) {
        requireServerThread(server);
        ServerPlayer current = currentPlayer(server, playerId);
        if (current == null) return false;
        // Authorization was rechecked immediately before the reducer's send claim.
        // Send one system packet, without vanilla's fallback-message retry listener.
        // A normal return acknowledges server-side enqueue, not client display.
        current.connection.send(new ClientboundSystemChatPacket(Component.literal(text), false));
        return true;
    }

    @Override
    public void playerLoggedIn(MinecraftServer server, UUID playerId) {
        DeliveryBinding binding = administratorBinding;
        if (server == notificationServer && binding != null) binding.delivery.onLogin(playerId);
    }

    @Override
    public void serverTick(MinecraftServer server) {
        if (server != null && server == notificationServer && maintenanceActivity != null) {
            requireServerThread(server);
            maintenanceActivity.tick(); // Scheduling/state only, never SQL on this thread.
        }
        DeliveryBinding binding = administratorBinding;
        if (server == notificationServer && binding != null) binding.delivery.tick();
    }

    @Override
    public void retentionPlayersChanged(MinecraftServer server, int humanPlayers) {
        if (server == null || server != notificationServer) return;
        requireServerThread(server);
        if (humanPlayers < 0) throw new IllegalArgumentException("Negative online human count");
        retentionHumanPlayers = humanPlayers;
        if (maintenanceActivity != null) maintenanceActivity.update(serverFullyStarted, humanPlayers);
    }

    @Override
    public void serverStarted(MinecraftServer server, int humanPlayers) {
        if (server == null || server != notificationServer) return;
        requireServerThread(server);
        serverFullyStarted = true;
        retentionPlayersChanged(server, humanPlayers);
    }

    @Override
    public void recordingChanged(RecordingSnapshot snapshot) {
        EventTrailStatusMonitor.publishRecording(snapshot);
        for (RecordingEpisodes.ConsoleNotice notice : recordingEpisodes.accept(snapshot)) {
            switch (notice.kind()) {
                case DISABLED -> LOGGER.error(RecordingAnnouncements.disabled(notice.cause()));
                case RECOVERY -> LOGGER.info(RecordingAnnouncements.recovered());
                case DIAGNOSTIC -> LOGGER.warn(RecordingAnnouncements.diagnostic(notice.cause()));
            }
        }
        DeliveryBinding binding = administratorBinding;
        if (binding != null && binding.owns(snapshot) && recordingEpisodes.isCurrentSnapshot(snapshot)) {
            if (snapshot.state() == RecordingSnapshot.State.STOPPING
                    || snapshot.state() == RecordingSnapshot.State.STOPPED) binding.delivery.stop();
            else binding.delivery.onRecordingChanged(snapshot);
        }
    }

    @Override
    public void resetBeforeStart() {
        stopStatus();
        stopMaintenance();
        stopQueryWork();
        stopCompatibilityAndSessions();
    }

    @Override
    public void startCompatibility() {
        UniversalContainerTracker.startRuntimeCompatibility();
        io.github.betapetey.eventtrail.events.parts.PartRemovalAuditBridge.startServer();
    }

    @Override
    public void stopCompatibilityAndSessions() {
        io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime.clear();
        io.github.betapetey.eventtrail.events.spells.ArsItemAuditRuntime.clear();
        io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime.clear();
        io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime.clear();
        ExtendedLookup.clearRuntimeState();
        InspectCommand.clearRuntimeState();
        UniversalContainerTracker.clearRuntimeState();
        // Last, so nothing here (not even a failure to load the class) can skip the clears above (recheck-02 R2-3).
        io.github.betapetey.eventtrail.events.parts.PartRemovalAuditBridge.stopServer();
    }

    @Override
    public void startQueryWork() {
        EventTrailQueryDispatcher.start();
    }

    @Override
    public void stopQueryWork() {
        EventTrailQueryDispatcher.shutdown();
    }

    @Override
    public void startMaintenance(ILogStorage storage, int retentionDays) {
        if (retentionDays > 0 && !EventTrailMaintenance.start(
                storage, retentionDays, serverFullyStarted, retentionHumanPlayers)) {
            throw new IllegalStateException("Retention maintenance did not start");
        }
        maintenanceActivity = retentionDays > 0 ? EventTrailMaintenance.activity() : null;
    }

    @Override
    public void stopMaintenance() {
        maintenanceActivity = null;
        EventTrailMaintenance.stop();
    }

    @Override
    public void startStatus(ILogStorage storage) {
        EventTrailStatusMonitor.start(storage);
    }

    @Override
    public void stopStatus() {
        EventTrailStatusMonitor.stop();
    }

    @Override
    public void showUnavailableStatus() {
        EventTrailStatusMonitor.start(null);
    }

    @Override
    public void clearPlayerState(UUID playerId) {
        io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime.onLogout(playerId);
        io.github.betapetey.eventtrail.events.spells.ArsItemAuditRuntime.onLogout(playerId);
        io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime.onLogout(playerId);
        io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime.onLogout(playerId);
        ExtendedLookup.onPlayerLogout(playerId);
        InspectCommand.onPlayerLogout(playerId);
        UniversalContainerTracker.onPlayerLogout(playerId);
    }
}
