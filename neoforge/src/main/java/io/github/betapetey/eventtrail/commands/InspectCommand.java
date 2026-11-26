package io.github.betapetey.eventtrail.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.commands.presentation.AuditActionPresentationCatalog;
import io.github.betapetey.eventtrail.commands.presentation.AuditEntryPresenter;
import io.github.betapetey.eventtrail.commands.presentation.AuditResultsComponentRenderer;
import io.github.betapetey.eventtrail.commands.presentation.InspectResultsView;
import io.github.betapetey.eventtrail.commands.presentation.RelativeAgeFormatter;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.storage.AdvancedLogStorage;
import io.github.betapetey.eventtrail.storage.EventTrailQueryException;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** NeoForge adapter and use-case boundary for block-history inspection. */
@EventBusSubscriber(modid = EventTrailIds.MOD_ID)
public final class InspectCommand {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final String INSPECT_PERMISSION = "command.eventtrail.inspect";
    private static final String PAGE_PERMISSION = "command.eventtrail.page";
    private static final AuditEntryPresenter PRESENTER = new AuditEntryPresenter(
            new RelativeAgeFormatter(Clock.systemUTC()),
            new AuditActionPresentationCatalog());
    private static final AuditResultsComponentRenderer RENDERER =
            new AuditResultsComponentRenderer();
    private static final InspectUseCase USE_CASE =
            new InspectUseCase(InvestigationSessions.REGISTRY, PRESENTER);

    private InspectCommand() {
    }

    private static int pageSize() {
        return EventTrailConfig.PAGE_SIZE.get();
    }

    @SubscribeEvent
    public static void onRightClickBlock(RightClickBlock event) {
        // The lazy supplier is intentional: client/off-hand work is rejected
        // before reading the server-owned investigation registry.
        if (!shouldProcessInspectInteraction(
                event.getLevel().isClientSide(),
                event.getHand() == InteractionHand.MAIN_HAND,
                () -> InvestigationSessions.REGISTRY.isInspecting(event.getEntity().getUUID()))) {
            return;
        }

        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer session)) return;
        ILogStorage storage = EventTrailManager.getInstance().getStorage();
        UUID playerId = session.getUUID();
        boolean permitted = EventTrailPermissions.hasExactPermission(
                session.createCommandSourceStack(), INSPECT_PERMISSION);
        if (!USE_CASE.revalidate(playerId, permitted)) {
            if (!permitted) {
                session.sendSystemMessage(Component.literal(
                        "§cYou don't have permission to use EventTrail inspect mode."));
                consume(event);
            }
            return;
        }
        var physical = AuditBlockLocations.capture(event.getLevel(), event.getPos())
                .flatMap(AuditBlockLocations.Frame::currentLocation);
        if (physical.isEmpty()) {
            session.sendSystemMessage(Component.literal("§cPhysical audit location is unavailable for this target."));
            consume(event);
            return;
        }
        BlockPos position = physical.get().position();
        var dimension = physical.get().dimensionId();
        InspectUseCase.StartResult start = USE_CASE.plan(
                playerId,
                dimension,
                position,
                permitted,
                EventTrailManager.getInstance().isReady() && storage != null);
        if (!start.ready()) {
            if (start.status() == InspectUseCase.StartStatus.PERMISSION_DENIED) {
                session.sendSystemMessage(Component.literal(
                        "§cYou don't have permission to use EventTrail inspect mode."));
                consume(event);
            } else if (start.status() == InspectUseCase.StartStatus.NOT_READY) {
                session.sendSystemMessage(Component.literal(
                        "§c" + EventTrailAvailabilityMessages.unavailable(session.createCommandSourceStack())));
                consume(event);
            }
            return;
        }

        InspectUseCase.QueryRequest request = start.request();
        MinecraftServer server = session.createCommandSourceStack().getServer();
        // Explosion split (Advisor M-6 e): verdicts are read with the rows, on the query thread, never at render.
        AdvancedLogStorage advanced = storage instanceof AdvancedLogStorage partStorage ? partStorage : null;
        EventTrailQueryDispatcher.PlayerSubmission<InspectUseCase.InspectRows> submission =
                EventTrailQueryDispatcher.submitPlayerAndDispatch(
                        playerId,
                        () -> USE_CASE.executeWithVerdicts(request, (queryPosition, queryDimension, limit) ->
                                storage.queryPosition(queryPosition, queryDimension.toString(), limit),
                                advanced == null ? null : advanced::explosionParts,
                                advanced == null ? null : advanced::captureHighWaterMark),
                        () -> USE_CASE.admit(request),
                        server::execute,
                        () -> server.getPlayerList().getPlayer(playerId),
                        session,
                        (currentPlayer, records) -> {
                            boolean currentPermission = EventTrailPermissions.hasExactPermission(
                                    currentPlayer.createCommandSourceStack(), INSPECT_PERMISSION);
                            presentCompletion(currentPlayer, USE_CASE.complete(
                                    request, records, currentPermission, pageSize()));
                        },
                        (currentPlayer, error) -> {
                            if (!USE_CASE.fail(request)) return;
                            reportQueryFailure(currentPlayer, error, "block inspection");
                        });
        if (!submission.accepted()) {
            session.sendSystemMessage(Component.literal("§c" + EventTrailQueryDispatcher.busyMessage()));
        }
        consume(event);
    }

    private static void consume(RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    static boolean shouldProcessInspectInteraction(
            boolean clientSide,
            boolean mainHand,
            BooleanSupplier inspectingState) {
        if (clientSide || !mainHand) return false;
        return inspectingState.getAsBoolean();
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("inspect")
                .requires(source -> EventTrailPermissions.hasExactPermission(source, INSPECT_PERMISSION))
                .executes(InspectCommand::toggleInspect));
        root.then(Commands.literal("page")
                .requires(source -> EventTrailPermissions.hasExactPermission(source, PAGE_PERMISSION))
                .then(Commands.argument("num", IntegerArgumentType.integer(1))
                        .executes(InspectCommand::goToPage)));
    }

    private static int toggleInspect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cOnly players can use inspect mode."));
            return 0;
        }
        InspectUseCase.ToggleResult result = USE_CASE.toggle(
                player.getUUID(), EventTrailManager.getInstance().isReady());
        if (result == InspectUseCase.ToggleResult.NOT_READY) {
            source.sendFailure(Component.literal("§c" + EventTrailAvailabilityMessages.unavailable(source)));
            return 0;
        }
        afterToggle(player.getUUID(), result);
        source.sendSuccess(() -> result == InspectUseCase.ToggleResult.ENABLED
                ? Component.literal("§aInspect mode enabled. §7Right-click a block to view its history.")
                : Component.literal("§7Inspect mode disabled."), false);
        return 1;
    }

    /**
     * Turning inspect on clears the viewer's teleport links, as before; turning it off keeps them, so
     * inspect's "@ x y z" link lives as long as lookup's row links do: until the viewer's next lookup page clears
     * them, or the per-viewer cap or expiry drops them.
     */
    static void afterToggle(UUID playerId, InspectUseCase.ToggleResult result) {
        if (result == InspectUseCase.ToggleResult.ENABLED) ExtendedLookup.clearTeleportLinks(playerId);
    }

    static int goToInspectPage(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        int requested = IntegerArgumentType.getInteger(context, "num");
        InspectUseCase.Completion completion = USE_CASE.page(
                player.getUUID(),
                requested,
                EventTrailPermissions.hasExactPermission(source, INSPECT_PERMISSION),
                pageSize());
        return presentPageCompletion(source, player, requested, completion);
    }

    private static void reportQueryFailure(Player player, Throwable error, String operation) {
        if (EventTrailQueryException.find(error) == null
                && !EventTrailQueryDispatcher.isExpectedRejection(error)) {
            LOGGER.error("Unexpected error during EventTrail {}", operation, error);
        }
        String message = EventTrailQueryDispatcher.isExpectedRejection(error)
                ? EventTrailQueryDispatcher.busyMessage()
                : EventTrailAvailabilityMessages.isUnavailable(error)
                ? EventTrailAvailabilityMessages.unavailable(player instanceof ServerPlayer serverPlayer
                        && EventTrailPermissions.hasExactPermission(serverPlayer.createCommandSourceStack(),
                                EventTrailPermissions.STATUS_PERMISSION))
                : EventTrailQueryException.safeUserMessage(error);
        player.sendSystemMessage(Component.literal("§c" + message));
    }

    private static void presentCompletion(
            ServerPlayer player,
            InspectUseCase.Completion completion) {
        if (completion.hasPage()) {
            RENDERER.renderInspect(withTeleport(player, completion.view())).forEach(player::sendSystemMessage);
        } else if (completion.status() == InspectUseCase.CompletionStatus.EMPTY) {
            player.sendSystemMessage(Component.literal(
                    "§7No EventTrail history exists at that block."));
        } else if (completion.status() == InspectUseCase.CompletionStatus.PERMISSION_DENIED) {
            player.sendSystemMessage(Component.literal(
                    "§cYou don't have permission to use EventTrail inspect mode."));
        }
    }

    private static int presentPageCompletion(
            CommandSourceStack source,
            ServerPlayer player,
            int requested,
            InspectUseCase.Completion completion) {
        if (completion.hasPage()) {
            RENDERER.renderInspect(withTeleport(player, completion.view())).forEach(player::sendSystemMessage);
            return 1;
        }
        if (completion.status() == InspectUseCase.CompletionStatus.PERMISSION_DENIED) {
            source.sendFailure(Component.literal(
                    "§cYou don't have permission to use EventTrail inspect mode."));
        } else if (completion.status() == InspectUseCase.CompletionStatus.INVALID_PAGE) {
            source.sendFailure(Component.literal("§cPage " + requested + " does not exist."));
        } else {
            source.sendFailure(Component.literal(
                    "§cNo results to page through. Run a lookup or inspect first."));
        }
        return 0;
    }

    /**
     * Inspect's "@ x y z" teleports by the same private link as lookup's coordinates, so clicking it runs the same
     * permission check (Owner 8:15 PM 11-22). Each shown page gets its own link.
     */
    private static InspectResultsView withTeleport(ServerPlayer player, InspectResultsView view) {
        if (!ExtendedLookup.canRunTeleportLinks(player.createCommandSourceStack())) return view;
        return view.withPositionCommand(ExtendedLookup.createPositionTarget(
                player.getUUID(), view.dimensionId(), view.position(), System.currentTimeMillis()));
    }

    static boolean revalidateInspectState(UUID playerId, boolean permitted) {
        return USE_CASE.revalidate(playerId, permitted);
    }

    public static void onPlayerLogout(UUID playerId) {
        InvestigationSessions.REGISTRY.logout(playerId);
        EventTrailQueryDispatcher.clearPlayer(playerId);
    }

    static void clearInspectResults(UUID playerId) {
        InvestigationSessions.REGISTRY.revokeInspect(playerId);
    }

    public static void clearRuntimeState() {
        InvestigationSessions.REGISTRY.clearAll();
    }

    private static int goToPage(CommandContext<CommandSourceStack> context) {
        return ExtendedLookup.goToPage(context);
    }

    static boolean isInspecting(UUID playerId) {
        return InvestigationSessions.REGISTRY.isInspecting(playerId);
    }
}
