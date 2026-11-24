package io.github.betapetey.eventtrail;

import io.github.betapetey.eventtrail.commands.EventTrailCommands;
import io.github.betapetey.eventtrail.commands.InspectCommand;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.core.EventTrailRuntimeCoordinator;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.evidence.ExplosionPartPublisher;
import io.github.betapetey.eventtrail.events.parts.PartRemovalItemCollector;
import io.github.betapetey.eventtrail.events.platform.CommandCreditGuard;
import io.github.betapetey.eventtrail.events.platform.OwnActionCreditGuard;
import io.github.betapetey.eventtrail.events.platform.ScopeLeakSweeper;
import io.github.betapetey.eventtrail.inventory.graves.GraveTakes;
import io.github.betapetey.eventtrail.inventory.graves.OriginJoinListener;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.storage.AdvancedLogStorage;
import io.github.betapetey.eventtrail.storage.JdbcShutdownDiagnostics;
import io.github.betapetey.eventtrail.storage.SQLiteStorage;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/** NeoForge bindings for the lifecycle-owned EventTrail runtime. */
@Mod(EventTrailMod.MOD_ID)
public final class EventTrailMod {
    public static final String MOD_ID = EventTrailIds.MOD_ID;
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public EventTrailMod() {
        io.github.betapetey.eventtrail.events.platform.NeoForgeAuditLocations.initialize();
        JdbcShutdownDiagnostics.initialize();
        EventTrailConfig.register(FMLPaths.CONFIGDIR.get().resolve(EventTrailConfig.CONFIG_FILE_NAME));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EventTrailMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(EventTrailPermissions::onGatherPermissionNodes);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EventTrailMod::onRetentionPlayerLogin);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EventTrailMod::onRetentionPlayerLogout);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onServerTick);
        // Kill credit B (design-05c 3.2.5 rule 4): left-over kill-credit tracking is cleared and counted each tick.
        NeoForge.EVENT_BUS.addListener(ScopeLeakSweeper::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onServerStarting);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(EventTrailMod::onServerStopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PartRemovalItemCollector::onEntityJoin);
        // Graves (design r6, 1.6.4 and 3.1): window joins, cancelled ones included; deferred entries cleared.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, EntityJoinLevelEvent.class,
                OriginJoinListener::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent.class,
                event -> GraveTakes.onPlayerLogout(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class,
                event -> GraveTakes.onServerStopped());
        io.github.betapetey.eventtrail.events.platform.PlayerActionScopes.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.register(EventTrailEventHandler.class);
        NeoForge.EVENT_BUS.register(InspectCommand.class);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        EventTrailCommands.register(event.getDispatcher());
        LOGGER.info("EventTrail commands registered");
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // NeoForge chose its permission handler before this event; choose EventTrail's one source now.
        EventTrailPermissions.selectForServerStart();
        // Command credit guard: withheld-credit counts and their one warning per reason start again.
        CommandCreditGuard.resetAtServerStart();
        // Kill credit A and B: the own-action counts and SCOPE_LEAK's one warning start again.
        OwnActionCreditGuard.resetAtServerStart();
        MinecraftServer server = event.getServer();
        Path eventTrailDirectory = server.getWorldPath(LevelResource.ROOT).resolve("eventtrail");
        EventTrailManager manager = EventTrailManager.getInstance();
        // Explosion split (Advisor M-2): parts left from an earlier run are dropped and counted, never offered here.
        ExplosionPartPublisher.production().beginRun();
        manager.bindServer(server);
        manager.retentionPlayersChanged(server, recognizedHumanCount(server.getPlayerList().getPlayers(), null, null));
        EventTrailRuntimeCoordinator.StartResult result = manager.start(eventTrailDirectory);

        if (!manager.isCurrentRecordingSuccess(result)) return;

        LOGGER.info("EventTrail runtime is READY using {}", result.backendName());
        logStorageSummary(manager);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        EventTrailManager.getInstance().serverStarted(server,
                recognizedHumanCount(server.getPlayerList().getPlayers(), null, null));
    }

    private static void logStorageSummary(EventTrailManager manager) {
        if (manager.getStorage() instanceof SQLiteStorage) {
            LOGGER.info("EventTrail storage is world-local SQLite");
        } else if (manager.getStorage() instanceof AdvancedLogStorage external) {
            LOGGER.info("EventTrail external server ID: {}", EventTrailDiagnostics.sanitizeLogValue(
                    external.getServerId(), EventTrailConfig.MAX_EXTERNAL_SERVER_ID_LENGTH));
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        // Explosion split (Advisor M-2): the stop pass runs before shutdown(), while parts can still reach storage;
        // what it cannot store is counted, so the coordinator's stop line includes it.
        ExplosionPartPublisher.production().stopPass();
        EventTrailManager.getInstance().shutdown();
        LOGGER.info("EventTrail runtime stopped");
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        // Explosion split (Advisor M-2): anything still waiting is dropped and counted before the repeated stop.
        ExplosionPartPublisher.production().endRun();
        // Defensive repeat for abnormal stop paths; coordinator stop is idempotent.
        EventTrailManager.getInstance().shutdown();
        EventTrailManager.getInstance().bindServer(null);
        EventTrailPermissions.clearSelection();
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EventTrailManager.getInstance().playerLoggedIn(player.getServer(), player.getUUID());
        }
    }

    private static void onRetentionPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                EventTrailManager.getInstance().retentionPlayersChanged(server,
                        recognizedHumanCount(server.getPlayerList().getPlayers(), player, null));
            }
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime.tick(event.getServer());
        io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime.tick(event.getServer());
        io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime.tick(event.getServer());
        // Explosion split: one drain pass per tick; contains every Throwable but a VirtualMachineError (M-5 a).
        ExplosionPartPublisher.production().tick();
        EventTrailManager.getInstance().retentionPlayersChanged(event.getServer(),
                recognizedHumanCount(event.getServer().getPlayerList().getPlayers(), null, null));
        EventTrailManager.getInstance().serverTick(event.getServer());
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLeft(event.getEntity(), EventTrailMod::clearPlayerRuntimeState);
    }

    /**
     * GF-FAKEPLAYER-ID (B52): the single guarded entry to per-UUID cleanup. A fake's logout carrying a real player's
     * UUID must not clear that player's sessions and spell or gadget state, so state is cleared only when
     * {@link #ownsPlayerState} holds.
     */
    static void playerLeft(Player leaving, Consumer<UUID> clear) {
        if (ownsPlayerState(leaving)) clear.accept(leaving.getUUID());
    }

    /**
     * GF-FAKEPLAYER-ID (B52): the leaving entity owns the per-UUID state only when it is not a FakePlayer and the
     * server's PlayerList holds nothing, or this same object, under its UUID (vanilla's own identity check on
     * removal). No server, no list, or any failure: false, and nothing is cleared.
     */
    static boolean ownsPlayerState(Player leaving) {
        try {
            if (!(leaving instanceof ServerPlayer player) || EventTrailEventHandler.isFakePlayer(player)) return false;
            MinecraftServer server = player.server;
            if (server == null || server.getPlayerList() == null) return false;
            ServerPlayer listed = server.getPlayerList().getPlayer(player.getUUID());
            return listed == null || listed == player;
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    private static void onRetentionPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
            MinecraftServer server = player.getServer();
            // NeoForge's logout signal can precede PlayerList removal.
            // GF-FAKEPLAYER-ID (B52): a leaving entity that does not own the UUID's state is not a human leaving.
            EventTrailManager.getInstance().retentionPlayersChanged(server,
                    recognizedHumanCount(server.getPlayerList().getPlayers(), null, departingHuman(player)));
        }
    }

    /** GF-FAKEPLAYER-ID (B52): the departing argument of {@link #recognizedHumanCount}: null for a non-owner. */
    static Player departingHuman(Player leaving) {
        return ownsPlayerState(leaving) ? leaving : null;
    }

    static int recognizedHumanCount(Iterable<? extends Player> players, Player arriving, Player departing) {
        int count = 0;
        boolean arrivingPresent = false;
        for (Player player : players) {
            arrivingPresent |= player == arriving;
            if (player != null && player != departing && !EventTrailEventHandler.isFakePlayer(player)) count++;
        }
        if (arriving != null && arriving != departing && !arrivingPresent
                && !EventTrailEventHandler.isFakePlayer(arriving)) count++;
        return count;
    }

    static void clearPlayerRuntimeState(UUID playerId) {
        EventTrailManager.getInstance().clearPlayerRuntimeState(playerId);
    }

}
