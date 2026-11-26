package io.github.betapetey.eventtrail.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.commands.investigation.PageState;
import io.github.betapetey.eventtrail.commands.investigation.PageWindow;
import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationRegistry.PlannedLookupPage;
import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationRegistry.RequestTransition;
import io.github.betapetey.eventtrail.commands.presentation.AuditActionPresentationCatalog;
import io.github.betapetey.eventtrail.commands.presentation.AuditEntryPresenter;
import io.github.betapetey.eventtrail.commands.presentation.AuditResultsComponentRenderer;
import io.github.betapetey.eventtrail.commands.presentation.DisplayTimeFormatter;
import io.github.betapetey.eventtrail.commands.presentation.ExplosionVerdicts;
import io.github.betapetey.eventtrail.commands.presentation.LookupResultsView;
import io.github.betapetey.eventtrail.commands.presentation.LookupSummaryView;
import io.github.betapetey.eventtrail.commands.presentation.RelativeAgeFormatter;
import io.github.betapetey.eventtrail.adapters.AdapterFamilyMisses;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.ContainerLabelFallbacks;
import io.github.betapetey.eventtrail.adapters.ItemsNotTrackedOpens;
import io.github.betapetey.eventtrail.adapters.RecordingGaps;
import io.github.betapetey.eventtrail.events.ItemClickOverrideProof;
import io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.events.platform.CommandCreditGuard;
import io.github.betapetey.eventtrail.events.platform.ExplosionRemovalAudit;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.github.betapetey.eventtrail.events.platform.OwnActionCreditGuard;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.core.EventTrailMaintenance;
import io.github.betapetey.eventtrail.core.ExplosionPartLossCounter;
import io.github.betapetey.eventtrail.core.RecordingSnapshot;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessMetrics;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessRuntimeDiagnostics;
import io.github.betapetey.eventtrail.inventory.optional.IntegrationStatusDisplay;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.storage.AbstractJdbcStorage;
import io.github.betapetey.eventtrail.storage.ActionSelection;
import io.github.betapetey.eventtrail.storage.AdvancedLogStorage;
import io.github.betapetey.eventtrail.storage.AdvancedQuery;
import io.github.betapetey.eventtrail.storage.AdvancedQueryResult;
import io.github.betapetey.eventtrail.storage.EventTrailQueryException;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.storage.StorageMaintenanceSnapshot;
import io.github.betapetey.eventtrail.storage.StorageTransportSecurity;
import io.github.betapetey.eventtrail.storage.ThingFilter;
import io.github.betapetey.eventtrail.storage.ThingFilters;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.time.Clock;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured lookup, status, pagination, and private result-navigation support. */
public final class ExtendedLookup {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    static final int DEFAULT_PER_PAGE = 5;
    static final int MAX_PER_PAGE = 50;
    static final int MAX_LOOKUP_INPUT_LENGTH = 1024;
    static final int MAX_LOOKUP_TOKENS = 32;
    static final int MAX_LOOKUP_FILTERS = 8;
    static final int MAX_FILTER_VALUE_LENGTH = 160;
    static final int MAX_RESOLVED_FILTER_VALUES = 128;
    static final int MAX_QUERY_COMPLEXITY = 100;
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;
    // Fixed resource-cost tier; intentionally independent from authorization configuration.
    private static final long QUERY_COMPLEXITY_MEDIUM_WINDOW_MS = 14L * MILLIS_PER_DAY;
    private static final long TELEPORT_LINK_TTL_MS = 5L * 60L * 1000L;
    private static final int MAX_TELEPORT_LINKS_PER_PLAYER = MAX_PER_PAGE;
    private static final double TELEPORT_COMPLETION_TOLERANCE = 1.0E-3D;
    private static final double COLLISION_EPSILON = 1.0E-7D;
    private static final double SUPPORT_PROBE_DEPTH = 1.0E-4D;

    private static final String LOOKUP_PERMISSION = "command.eventtrail.lookup";
    private static final String PAGE_PERMISSION = "command.eventtrail.page";
    private static final String STATUS_PERMISSION = EventTrailPermissions.STATUS_PERMISSION;
    private static final String WORLD_PERMISSION = "command.eventtrail.lookup.world";
    private static final String GLOBAL_PERMISSION = "command.eventtrail.lookup.global";
    private static final String EXTENDED_PERMISSION = "command.eventtrail.lookup.extended";
    private static final String TELEPORT_PERMISSION = "command.eventtrail.teleport";
    private static final String INTERNAL_TELEPORT_PREFIX = "__eventtrail_tp:";
    private static final Pattern DURATION = Pattern.compile("(?i)^(\\d+)(s|m|h|d|w)$");
    private static final List<LookupActionSelector> ACTIVE_ACTION_TYPES = LookupActionSelector.catalog();
    private static final List<String> ACTIVE_ACTION_SUGGESTIONS = ACTIVE_ACTION_TYPES.stream()
            .map(action -> "action:" + action.token())
            .toList();
    private static final Set<String> SUPPORTED_LOOKUP_FILTERS = Set.of(
            "player", "action", "block", "item", "entity", "mod", "per-page");
    /** The one-line refusal of the removed name: filter (Owner 11-22 5:12 AM, item 10). */
    static final String NAME_FILTER_REMOVED = "name: was removed. Use block:, item: or entity: with the name in"
            + " quotes, for example item:\"Lucky Pick\".";
    private static final AuditActionPresentationCatalog ACTION_PRESENTATIONS =
            new AuditActionPresentationCatalog();
    private static final AuditEntryPresenter ENTRY_PRESENTER = new AuditEntryPresenter(
            new RelativeAgeFormatter(Clock.systemUTC()), ACTION_PRESENTATIONS);
    private static final AuditResultsComponentRenderer RESULTS_RENDERER =
            new AuditResultsComponentRenderer();
    // Teleport links are server-thread-owned and deliberately separate from page-session state.
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, TeleportLink>> TELEPORT_LINKS =
            new ConcurrentHashMap<>();

    private ExtendedLookup() {}

    /** Attaches structured lookup and status to EventTrail's canonical root builder. */
    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> lookup = Commands.literal("lookup")
                .requires(src -> EventTrailPermissions.hasExactPermission(src, LOOKUP_PERMISSION))
                .executes(ExtendedLookup::showLookupUsage);

        lookup.then(Commands.argument("query", StringArgumentType.greedyString())
                .suggests(ExtendedLookup::suggest)
                .executes(ExtendedLookup::execute));

        LiteralArgumentBuilder<CommandSourceStack> status = Commands.literal("status")
                .requires(src -> EventTrailPermissions.hasExactPermission(src, STATUS_PERMISSION))
                .executes(ExtendedLookup::showStatus);

        root.then(lookup);
        root.then(status);
        root.then(Commands.literal("maintenance")
                .requires(src -> EventTrailPermissions.hasExactPermission(src, STATUS_PERMISSION))
                .then(Commands.literal("status")
                        .requires(src -> EventTrailPermissions.hasExactPermission(src, STATUS_PERMISSION))
                        .executes(ExtendedLookup::showMaintenanceStatus)));
    }

    private static int showLookupUsage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendFailure(Component.literal("§cChoose a lookup scope first: radius:<blocks>, world:<dimension>, or global."));
        return 0;
    }

    /** Read-only synchronous view of the lifecycle-owned background health snapshot. */
    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!EventTrailPermissions.hasExactPermission(source, STATUS_PERMISSION)) return 0;
        return renderCachedStatus(text -> sendStatusLine(source, text), System.currentTimeMillis());
    }

    private static int showMaintenanceStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!EventTrailPermissions.hasExactPermission(source, STATUS_PERMISSION)) return 0;
        return renderCachedMaintenanceStatus(text -> sendStatusLine(source, text), System.currentTimeMillis(),
                EventTrailManager.getInstance().getStorage(), EventTrailConfig.LOG_RETENTION_DAYS.get());
    }

    static int renderCachedStatus(Consumer<String> output, long now) {
        return renderCachedStatus(output, now, EventTrailManager.getInstance().recordingSnapshot());
    }

    /** Deterministic rendering seam; production supplies the coordinator's authoritative cached value. */
    static int renderCachedStatus(Consumer<String> output, long now, RecordingSnapshot recording) {
        EventTrailStatusMonitor.HealthSnapshot snapshot = EventTrailStatusMonitor.snapshot();
        AbstractJdbcStorage.WriteBufferStats writeStats = currentWriteBufferStats();
        List<String> lines = new ArrayList<>(withSpoolStatusLines(recordingStatusLines(recording, snapshot, writeStats,
                EventTrailManager.getInstance().notReadyRefusedRows(),
                EventTrailManager.getInstance().skippedCaptureAttempts(), now), currentSpoolStatusLines()));
        lines.addAll(lines.size() - 1, maintenanceStatusLines(EventTrailManager.getInstance().getStorage(),
                EventTrailConfig.LOG_RETENTION_DAYS.get(), now, false));
        lines.addAll(lines.size() - 1, explosionPartStatusLines(
                EventTrailManager.getInstance().explosionPartLosses().snapshot()));
        lines.addAll(lines.size() - 1, unlistedPlayerStatusLines(NeoForgePlayerProvenance.unlistedPlayerRefusals()));
        lines.addAll(lines.size() - 1, buildingGadgetUnrecordedStatusLines(
                GadgetAuditRuntime.unrecordedBuildingGadgetChanges()));
        lines.addAll(lines.size() - 1, recordingGapStatusLines(RecordingGaps.counts()));
        lines.addAll(lines.size() - 1, windBurstStatusLines(ExplosionRemovalAudit.windBurstRemovedBlocks()));
        lines.addAll(lines.size() - 1, overrideProofStatusLines(ItemClickOverrideProof.recordingFailures()));
        lines.addAll(lines.size() - 1, withheldLocationStatusLines(AuditBlockLocations.withheldCounts()));
        lines.forEach(output);
        return recording.recordingAvailable() ? 1 : 0;
    }

    /**
     * GADGET-DELAYED: one status line with the Building Gadgets effect blocks whose own final write changed the block
     * while no row was handed to storage for it, or whose placeholder was removed before it finished after the gadget
     * had taken away a real block, since this process started, when there were any. It rises once per block, and only
     * while Building Gadgets recording is on (its hooks do not observe while the key is OFF or its family is stopped).
     */
    static List<String> buildingGadgetUnrecordedStatusLines(long changes) {
        if (changes <= 0) return List.of();
        return List.of("§6Building Gadgets block changes without a row since this process started (counted while "
                + "Building Gadgets recording is on): " + changes + " (placed, replaced or removed blocks a gadget "
                + "finished, or removed before it finished, that EventTrail could not tie to the use that ordered them: "
                + "for example after their chunk was unloaded or the server restarted, past a size, time or count "
                + "limit, in a mode EventTrail does not record, for a use it did not admit, after a recording-settings "
                + "change, while EventTrail was not ready, or after an EventTrail error). No row credits anyone with "
                + "them.");
    }

    /**
     * GF-FAKEPLAYER-ID (B51): one status line with the player checks refused since this process started because the
     * player was not a FakePlayer but was not proven to be the server's own player object for its UUID, when there
     * were any (review-01 M2). The count rises once per check, so one action can add several. No row credits such a
     * player with an action, but rows that show it only as an automated player (a real player's kill of it, or the
     * death of a real player it caused) are still written.
     */
    static List<String> unlistedPlayerStatusLines(long refused) {
        if (refused <= 0) return List.of();
        return List.of("§6Player checks refused since this process started because the player was not proven to be the "
                + "server's own player object (for example a mod's bot that is not a NeoForge fake player; NeoForge "
                + "fake players, such as Create's deployer, are not counted): " + refused
                + " (one action can add several). No row credited them with those actions; rows that show them only as "
                + "an automated player, such as a real player's kill of them or a death they caused, are still written.");
    }

    /**
     * GF-WARNINGS (B45/B46/B48): one status line naming each kind of container action EventTrail did not record since
     * server start, with its count, when there were any. Each kind also wrote rate-limited warnings. No row was
     * written for any of them.
     */
    static List<String> recordingGapStatusLines(Map<RecordingGaps.Gap, Long> gaps) {
        if (gaps == null || gaps.isEmpty()) return List.of();
        StringBuilder named = new StringBuilder();
        gaps.forEach((gap, count) -> {
            if (gap == null || count == null || count <= 0L) return;
            if (!named.isEmpty()) named.append(", ");
            named.append(gap.code()).append(": ").append(count);
        });
        if (named.isEmpty()) return List.of();
        return List.of("§6Container actions not recorded since server start (no row was written for them): " + named);
    }

    /**
     * GF-WARNINGS r3 (review-01 NIT-1): one status line naming each cause of the physical audit locations withheld
     * since this process started ({@link AuditBlockLocations#withheldCounts()}, never reset at a server start or
     * stop), with its count, when there were any. Each cause also wrote rate-limited warnings. No row was written for
     * the evidence they withheld.
     */
    static List<String> withheldLocationStatusLines(Map<AuditBlockLocations.Cause, Long> withheld) {
        if (withheld == null || withheld.isEmpty()) return List.of();
        StringBuilder named = new StringBuilder();
        withheld.forEach((cause, count) -> {
            if (cause == null || count == null || count <= 0L) return;
            if (!named.isEmpty()) named.append(", ");
            named.append(cause.text()).append(": ").append(count);
        });
        if (named.isEmpty()) return List.of();
        // r4 (review-02 NIT-3): these are lookups, and one action can make several, so the line says lookups.
        return List.of("§6Physical audit location lookups withheld since this process started (one action can make"
                + " several; no row was written for them): " + named);
    }

    /**
     * GF-EXPLOSIONSESSION review-01 MINOR-4: one status line with the blocks removed by wind bursts (wind charges)
     * since this process started, when there were any. Those removals are counted, not recorded as rows.
     */
    static List<String> windBurstStatusLines(long removed) {
        if (removed <= 0L) return List.of();
        return List.of("§6Blocks removed by wind bursts since this process started (counted, not recorded as rows): "
                + removed);
    }

    /**
     * GF-WARNINGS r2 (GF-NESTED review m-3): one status line with the item-click override steps that could not be
     * recorded since server start ({@link ItemClickOverrideProof#recordingFailures()}), when there were any. Each such
     * click had no proof, so the nested second pass could not run for it; each also wrote a rate-limited WARN.
     */
    static List<String> overrideProofStatusLines(long failures) {
        if (failures <= 0L) return List.of();
        return List.of("§6Item-click override steps not recorded since server start (the nested second pass could not"
                + " run for those clicks): " + failures);
    }

    /**
     * Explosion split (condition 4): one status line with the parts of explosions stored in parts that were not
     * stored since this process started, when there were any (the same figure as the coordinator's stop line).
     */
    static List<String> explosionPartStatusLines(ExplosionPartLossCounter.Snapshot losses) {
        if (losses == null || losses.isEmpty()) return List.of();
        return List.of("§6Explosions stored in parts since this process started: " + losses.describe()
                + "; lookups show those explosions INCOMPLETE.");
    }

    static int renderCachedMaintenanceStatus(Consumer<String> output, long now,
                                             ILogStorage storage, int configuredDays) {
        maintenanceStatusLines(storage, configuredDays, now, true).forEach(output);
        return 1;
    }

    private static List<String> maintenanceStatusLines(ILogStorage storage,
                                                       int configuredDays, long now, boolean detail) {
        var snapshot = storage == null
                ? StorageMaintenanceSnapshot.unavailable(StorageMaintenanceSnapshot.Backend.UNKNOWN, 0L)
                : storage.maintenanceSnapshot();
        if (snapshot == null) snapshot = StorageMaintenanceSnapshot.unavailable(StorageMaintenanceSnapshot.Backend.UNKNOWN, 0L);
        return MaintenanceStatusFormatter.lines(
                EventTrailMaintenance.retentionSnapshot(storage, configuredDays),
                snapshot, now, detail, EventTrailConfig.reclaimEnabled());
    }

    static List<String> recordingStatusLines(
            RecordingSnapshot recording, EventTrailStatusMonitor.HealthSnapshot connectivity,
            AbstractJdbcStorage.WriteBufferStats stats, long now) {
        return recordingStatusLines(recording, connectivity, stats, -1L, -1L, now);
    }

    /**
     * {@code notReadyRefusedRows}: the coordinator's rows refused while not READY (af-1 F6);
     * {@code skippedCaptureAttempts}: the coordinator's capture attempts skipped at a capture gate (af-2, Owner
     * decision R-M1/R-m6 b). A negative value omits its line (the four-argument rendering seam passes -1 for both).
     */
    static List<String> recordingStatusLines(
            RecordingSnapshot recording, EventTrailStatusMonitor.HealthSnapshot connectivity,
            AbstractJdbcStorage.WriteBufferStats stats, long notReadyRefusedRows, long skippedCaptureAttempts,
            long now) {
        return recordingStatusLines(recording, connectivity, stats, notReadyRefusedRows, skippedCaptureAttempts, now,
                ZoneId.systemDefault());
    }

    /** As above; the shown times use {@link DisplayTimeFormatter} in {@code zone} (Owner Q241 A). */
    static List<String> recordingStatusLines(
            RecordingSnapshot recording, EventTrailStatusMonitor.HealthSnapshot connectivity,
            AbstractJdbcStorage.WriteBufferStats stats, long notReadyRefusedRows, long skippedCaptureAttempts,
            long now, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        lines.add("§8§m-----------------------------------------------------");
        lines.add("§6EventTrail Status");
        switch (recording.state()) {
            case DISABLED -> {
                lines.add("§cAUDIT LOGGING DISABLED");
                var failure = recording.currentFailure();
                if (failure != null) {
                    lines.add("§7Code: §f" + failure.code());
                    lines.add("§7Reason: §f" + failure.explanation());
                    lines.add("§7Action: §f" + failure.action());
                }
                lines.add("§7Disabled since: §f" + DisplayTimeFormatter.format(recording.disabledSinceMillis(), zone)
                        + " §8(" + formatStatusAge(Math.max(0L, now - recording.disabledSinceMillis())) + ")");
                lines.add("§cEvents occurring while disabled are not recorded and will not be backfilled.");
            }
            case RECORDING -> lines.add("§7Recording: §aAvailable for new events");
            case BUFFERING -> lines.add("§7Recording: §eDEGRADED / BUFFERING; accepted entries are volatile");
            case STARTING -> lines.add("§7Recording: §eSTARTING; availability is not yet confirmed");
            case STOPPING -> lines.add("§7Recording: §7STOPPING; new event capture is stopped");
            case STOPPED -> lines.add("§7Recording: §7STOPPED");
        }
        if (recording.recordingAvailable()) {
            lines.add("§7Accepted entries may still be volatile until commit-confirmed.");
        }
        if (recording.originalFailure() != null
                && !recording.originalFailure().equals(recording.currentFailure())) {
            lines.add("§7Initiating cause: §f" + recording.originalFailure().code());
        }
        for (var cleanup : recording.cleanupFailures()) {
            lines.add("§7Separate cleanup fact: §f" + cleanup.code() + " §8(" + cleanup.phase()
                    + ", " + cleanup.role() + ")");
        }
        if (recording.lastRecoveryMillis() > 0L) {
            lines.add("§7Last verified recording recovery: §f"
                    + DisplayTimeFormatter.format(recording.lastRecoveryMillis(), zone));
        }
        lines.add(recording.historyReady()
                ? "§7History: §aAvailable for investigation; connectivity is separate"
                : "§7History: §eUnavailable");
        lines.add("§7Storage: §f" + recording.backend().displayName());
        boolean stale = connectivity.isStale(now);
        lines.add("§7Connectivity status: §f" + connectivity.state()
                + (stale ? " §e(stale observation)" : ""));
        switch (connectivity.state()) {
            case HEALTHY -> lines.add(stale
                    ? "§7Database: §eLast check connected"
                    : "§7Database: §aConnected");
            case PENDING -> lines.add("§7Database health: §ePending (not yet checked)");
            case UNHEALTHY -> {
                var failure = connectivity.failure() == null
                        ? StorageFailureCategory.INTERNAL_ERROR : connectivity.failure();
                lines.add(failure == StorageFailureCategory.HEALTH_CHECK_UNAVAILABLE
                        ? "§7Database health: §eUnknown"
                        : "§7Database: §cUnavailable");
                lines.add("§7Connectivity reason: §c" + failure.userReason);
            }
            case STOPPED -> lines.add("§7Database health: §eUnknown; health worker is stopped");
        }
        lines.add("§7Connectivity observation: §f" + formatStatusAge(connectivity.ageMillis(now)) + " old");
        lines.addAll(transportSecurityStatusLines(connectivity.transportSecurity(),
                connectivity.state() == EventTrailStatusMonitor.HealthState.HEALTHY && !stale));
        String backend = recording.backend().displayName();
        if (("MySQL".equals(backend) || "PostgreSQL".equals(backend))
                && connectivity.serverId() != null && !connectivity.serverId().isBlank()) {
            lines.add("§7Server ID: §f" + sanitizeStatusValue(connectivity.serverId()));
        }
        if (stats == null) {
            lines.add("§7Write accounting: §eUnavailable; not inferred as zero");
        } else {
            lines.add("§7Accepted writes: §f" + stats.accepted());
            lines.add("§7Commit-confirmed writes: §f" + stats.written());
            lines.add("§7Pending writes (volatile): §f" + stats.pending()
                    + " §8(" + stats.pendingPayloadBytes() + " estimated payload bytes)");
            lines.add("§7Discarded without commit confirmation: §f"
                    + (stats.permanentFailed() + stats.shutdownLost()));
            lines.add("§7Dropped before admission (not stored): §f" + (stats.rejectedFull() + stats.rejectedPayload()
                    + stats.rejectedOversized() + stats.rejectedClosed()));
            lines.add("§7Uncertain outcomes may still have database rows; discard is not proof of absence.");
        }
        if (notReadyRefusedRows >= 0L) {
            lines.add("§7Refused while EventTrail was not ready: §f" + notReadyRefusedRows
                    + " §8(since this process started; before start-up completed, after a failed start, or after a"
                    + " stop began)");
        }
        if (skippedCaptureAttempts >= 0L) {
            lines.add("§7Skipped capture attempts while EventTrail was not ready: §f" + skippedCaptureAttempts
                    + " §8(an upper bound, not rows; since this process started; container access also while the"
                    + " database writer was stopped or the write buffer was nearly full; includes open container"
                    + " sessions and in-flight actions dropped at a stop)");
        }
        lines.addAll(compatibilityStatusLines(OptionalMenuAdapterLoader.compatibilitySnapshot()));
        lines.addAll(containerAccessStatusLines(
                EventTrailConfig.TRACK_CONTAINER_ACCESS.get(),
                ContainerAccessRuntimeDiagnostics.metrics()));
        lines.addAll(itemsNotTrackedStatusLines(ItemsNotTrackedOpens.counts()));
        lines.addAll(adapterMissStatusLines(AdapterFamilyMisses.counts()));
        lines.addAll(commandCreditStatusLines(CommandCreditGuard.counts()));
        lines.addAll(ownActionCreditStatusLines(OwnActionCreditGuard.counts()));
        lines.addAll(ContainerLabelFallbacks.statusLines());
        lines.add("§8§m-----------------------------------------------------");
        return List.copyOf(lines);
    }

    static List<String> statusLines(EventTrailStatusMonitor.HealthSnapshot snapshot, long now) {
        return statusLines(snapshot, now, null);
    }

    static List<String> statusLines(
            EventTrailStatusMonitor.HealthSnapshot snapshot,
            long now,
            AbstractJdbcStorage.WriteBufferStats writeStats) {
        List<String> lines = new ArrayList<>();
        boolean stale = snapshot.isStale(now);
        boolean writeImpaired = writeStats != null && writeStats.impaired();
        lines.add("§8§m-----------------------------------------------------");
        lines.add("§6EventTrail Status");

        if (snapshot.state() == EventTrailStatusMonitor.HealthState.HEALTHY
                && !stale && !writeImpaired) {
            lines.add("§7Overall: §aHEALTHY");
        } else if (snapshot.state() == EventTrailStatusMonitor.HealthState.HEALTHY
                && !stale) {
            lines.add("§7Overall: §eDEGRADED");
        } else if (snapshot.state() == EventTrailStatusMonitor.HealthState.PENDING) {
            lines.add("§7Overall: §ePENDING");
        } else if (snapshot.state() == EventTrailStatusMonitor.HealthState.HEALTHY) {
            lines.add("§7Overall: §eSTALE");
        } else {
            lines.add("§7Overall: §cUNHEALTHY" + (stale ? " §e(stale observation)" : ""));
        }

        lines.add(snapshot.coreReady() ? "§7Core: §aReady" : "§7Core: §cNot ready");
        lines.add("§7Storage: §f" + stableBackendLabel(snapshot.backend()));
        lines.addAll(transportSecurityStatusLines(
                snapshot.transportSecurity(),
                snapshot.state() == EventTrailStatusMonitor.HealthState.HEALTHY && !stale));

        switch (snapshot.state()) {
            case PENDING -> {
                lines.add("§7Database health: §ePending (not yet checked)");
                lines.add("§7Logging / lookups: §eAvailable; health not yet confirmed");
                if (writeStats != null) {
                    lines.addAll(writeBufferStatusLines(writeStats, currentSpoolWaitingActions()));
                }
                lines.add("§7Observation: §ePending for " + formatStatusAge(snapshot.ageMillis(now)));
            }
            case HEALTHY -> {
                lines.add(stale
                        ? "§7Database: §eLast check connected §8(" + snapshot.latencyMs() + "ms§8)"
                        : "§7Database: §aConnected §8(" + snapshot.latencyMs() + "ms§8)");
                if (stale) {
                    lines.add("§7Logging / lookups: §eCurrent health unknown");
                    if (writeStats != null) {
                        lines.addAll(writeBufferStatusLines(writeStats, currentSpoolWaitingActions()));
                    }
                } else if (writeStats != null) {
                    lines.add("§7Lookups: §aReady");
                    lines.addAll(writeBufferStatusLines(writeStats, currentSpoolWaitingActions()));
                } else {
                    lines.add("§7Logging / lookups: §aReady");
                }
                lines.add("§7Observation: " + (stale ? "§eStale" : "§7Current")
                        + " §8(" + formatStatusAge(snapshot.ageMillis(now)) + " old§8)");
            }
            case UNHEALTHY, STOPPED -> {
                StorageFailureCategory failure = snapshot.failure() == null
                        ? StorageFailureCategory.INTERNAL_ERROR
                        : snapshot.failure();
                if (failure == StorageFailureCategory.HEALTH_CHECK_UNAVAILABLE) {
                    lines.add("§7Database health: §eUnknown §8(" + snapshot.latencyMs() + "ms§8)");
                    lines.add("§7Reason: §e" + failure.userReason);
                    lines.add(snapshot.coreReady()
                            ? "§7Logging / lookups: §eCurrent health not confirmed"
                            : "§7Logging / lookups: §cUnavailable");
                } else {
                    lines.add("§7Database: §cUnavailable §8(" + snapshot.latencyMs() + "ms§8)");
                    lines.add("§7Reason: §c" + failure.userReason);
                    lines.add(snapshot.coreReady()
                            ? "§7Logging / lookups: §cImpacted until storage recovers"
                            : "§7Logging / lookups: §cUnavailable");
                }
                if (writeStats != null) {
                    lines.addAll(writeBufferStatusLines(writeStats, currentSpoolWaitingActions()));
                }
                lines.add("§7Observation: " + (stale ? "§eStale" : "§7Current")
                        + " §8(" + formatStatusAge(snapshot.ageMillis(now)) + " old§8)");
            }
        }

        String stableBackend = stableBackendLabel(snapshot.backend());
        boolean externalBackend = "MySQL".equals(stableBackend) || "PostgreSQL".equals(stableBackend);
        if (snapshot.serverId() != null && !snapshot.serverId().isBlank() && externalBackend) {
            lines.add("§7Server ID: §f" + sanitizeStatusValue(snapshot.serverId()));
        }
        lines.addAll(compatibilityStatusLines(
                OptionalMenuAdapterLoader.compatibilitySnapshot()));
        lines.addAll(containerAccessStatusLines(
                EventTrailConfig.TRACK_CONTAINER_ACCESS.get(),
                ContainerAccessRuntimeDiagnostics.metrics()));
        lines.addAll(itemsNotTrackedStatusLines(ItemsNotTrackedOpens.counts()));
        lines.addAll(adapterMissStatusLines(AdapterFamilyMisses.counts()));
        lines.addAll(commandCreditStatusLines(CommandCreditGuard.counts()));
        lines.addAll(ownActionCreditStatusLines(OwnActionCreditGuard.counts()));
        lines.add("§8§m-----------------------------------------------------");
        return List.copyOf(lines);
    }

    private static AbstractJdbcStorage.WriteBufferStats currentWriteBufferStats() {
        Object storage = EventTrailManager.getInstance().getStorage();
        return storage instanceof AbstractJdbcStorage jdbcStorage
                ? jdbcStorage.getWriteBufferStats()
                : null;
    }

    /** writer-spool design-02 11.2: the disk spool's status lines, placed after "Pending writes (volatile)". */
    static List<String> currentSpoolStatusLines() {
        Object storage = EventTrailManager.getInstance().getStorage();
        if (!(storage instanceof AbstractJdbcStorage jdbcStorage)) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : jdbcStorage.spoolStatusLines()) lines.add("§7" + line);
        return lines;
    }

    /** Inserts {@code spoolLines} right after the "Pending writes (volatile)" line (or nowhere when it is absent). */
    static List<String> withSpoolStatusLines(List<String> lines, List<String> spoolLines) {
        if (spoolLines.isEmpty()) return lines;
        List<String> merged = new ArrayList<>(lines);
        for (int i = 0; i < merged.size(); i++) {
            if (merged.get(i).startsWith("§7Pending writes (volatile):")) {
                merged.addAll(i + 1, spoolLines);
                return merged;
            }
        }
        return lines;
    }

    private static long currentSpoolWaitingActions() {
        Object storage = EventTrailManager.getInstance().getStorage();
        return storage instanceof AbstractJdbcStorage jdbcStorage ? jdbcStorage.spoolWaitingActions() : 0L;
    }

    static List<String> writeBufferStatusLines(AbstractJdbcStorage.WriteBufferStats stats) {
        return writeBufferStatusLines(stats, 0L);
    }

    /** {@code spoolWaitingActions}: actions waiting on disk (design-01 11: "Backlogged; N actions waiting on disk"). */
    static List<String> writeBufferStatusLines(AbstractJdbcStorage.WriteBufferStats stats, long spoolWaitingActions) {
        if (stats == null) return List.of();
        List<String> lines = new ArrayList<>(3);
        if (!stats.writerAlive()) {
            lines.add("§7Audit logging: §cIMPAIRED; database writer is not running");
        } else if (!stats.acceptingWrites()) {
            lines.add("§7Audit logging: §cIMPAIRED; database writer is not accepting new records");
        } else if (stats.retrying()) {
            lines.add("§7Audit logging: §eBacklogged; retrying database writes");
        } else if (stats.saturated()) {
            // R-11 (r9): an impaired writer is never hidden behind the backlog headline.
            lines.add("§7Audit logging: §cIMPAIRED; write buffer is saturated");
        } else if (stats.lostOrRejected() > 0L) {
            lines.add("§7Audit logging: §eActive; prior rejection/discard recorded"
                    + (spoolWaitingActions > 0L ? "; " + spoolWaitingActions + " actions waiting on disk" : ""));
        } else if (spoolWaitingActions > 0L) {
            lines.add("§7Audit logging: §eBacklogged; " + spoolWaitingActions + " actions waiting on disk");
        } else {
            lines.add("§7Audit logging: §aActive");
        }
        lines.add("§7Pending writes: §f" + stats.pending());
        if (stats.lostOrRejected() > 0L) {
            lines.add("§7Rejected/discarded writes since startup: §c" + stats.lostOrRejected());
            lines.add("§7Discarded means not commit-confirmed; uncertain writes may still have database rows");
        }
        return List.copyOf(lines);
    }

    private static String formatStatusAge(long ageMillis) {
        if (ageMillis < 1_000L) return "under 1s";
        return Math.max(1L, ageMillis / 1_000L) + "s";
    }

    static List<String> statusLines(
            String backend, String serverId, StorageStatus result, boolean coreReady) {
        return statusLines(backend, serverId, result, coreReady, StorageTransportSecurity.unknown());
    }

    static List<String> statusLines(
            String backend,
            String serverId,
            StorageStatus result,
            boolean coreReady,
            StorageTransportSecurity transportSecurity) {
        List<String> lines = new ArrayList<>();
        lines.add("§8§m-----------------------------------------------------");
        lines.add("§6EventTrail Status");
        lines.add(result.healthy ? "§7Overall: §aHEALTHY" : "§7Overall: §cUNHEALTHY");
        lines.add(coreReady ? "§7Core: §aReady" : "§7Core: §cNot ready");
        lines.add("§7Storage: §f" + stableBackendLabel(backend));
        lines.addAll(transportSecurityStatusLines(transportSecurity, result.healthy));

        if (result.healthy) {
            lines.add("§7Database: §aConnected §8(" + result.latencyMs + "ms§8)");
            lines.add("§7Logging / lookups: §aReady");
        } else {
            StorageFailureCategory failure = result.failure == null
                    ? StorageFailureCategory.INTERNAL_ERROR
                    : result.failure;
            if (failure == StorageFailureCategory.HEALTH_CHECK_UNAVAILABLE) {
                lines.add("§7Database health: §eUnknown §8(" + result.latencyMs + "ms§8)");
                lines.add("§7Reason: §e" + failure.userReason);
                lines.add(coreReady
                        ? "§7Logging / lookups: §eCurrent health not confirmed"
                        : "§7Logging / lookups: §cUnavailable");
            } else {
                lines.add("§7Database: §cUnavailable §8(" + result.latencyMs + "ms§8)");
                lines.add("§7Reason: §c" + failure.userReason);
                lines.add(coreReady
                        ? "§7Logging / lookups: §cImpacted until storage recovers"
                        : "§7Logging / lookups: §cUnavailable");
            }
        }

        String stableBackend = stableBackendLabel(backend);
        boolean externalBackend = "MySQL".equals(stableBackend) || "PostgreSQL".equals(stableBackend);
        if (serverId != null && !serverId.isBlank() && externalBackend) {
            lines.add("§7Server ID: §f" + sanitizeStatusValue(serverId));
        }
        lines.addAll(compatibilityStatusLines(
                OptionalMenuAdapterLoader.compatibilitySnapshot()));
        lines.addAll(containerAccessStatusLines(
                EventTrailConfig.TRACK_CONTAINER_ACCESS.get(),
                ContainerAccessRuntimeDiagnostics.metrics()));
        lines.addAll(itemsNotTrackedStatusLines(ItemsNotTrackedOpens.counts()));
        lines.addAll(adapterMissStatusLines(AdapterFamilyMisses.counts()));
        lines.addAll(commandCreditStatusLines(CommandCreditGuard.counts()));
        lines.addAll(ownActionCreditStatusLines(OwnActionCreditGuard.counts()));
        lines.add("§8§m-----------------------------------------------------");
        return List.copyOf(lines);
    }

    /**
     * Tracker catch-all (Owner decision A, 11-14): per adapter family, the menus it recognized whose item moves
     * EventTrail did not record since the server started (an OPEN row may exist), by path. Nothing when there is none.
     */
    static List<String> adapterMissStatusLines(Map<AdapterKey, AdapterFamilyMisses.Counts> misses) {
        if (misses == null || misses.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(misses.size() + 1);
        lines.add("§7Recognized menus whose item moves were not recorded since server start:");
        misses.forEach((key, counts) -> lines.add("§8 - §f" + key.displayName() + ": " + counts.refused()
                + " selection refused, " + counts.unresolved() + " selected then not resolved"));
        return List.copyOf(lines);
    }

    /**
     * Command credit guard: per reason, the kill credits withheld for damage a command built since the server started.
     * Nothing when there is none.
     */
    static List<String> commandCreditStatusLines(Map<CommandCreditGuard.Reason, Long> withheld) {
        if (withheld == null || withheld.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(withheld.size() + 1);
        lines.add("§7Kill credits withheld for command-made damage since server start:");
        withheld.forEach((reason, count) -> lines.add("§8 - §f" + reason.label() + ": " + count));
        return List.copyOf(lines);
    }

    /**
     * Kill credit A and B (design-05c section 5): per reason, the kill credits not given because the kill was not the
     * player's own action, since the server started. Nothing when there is none.
     */
    static List<String> ownActionCreditStatusLines(Map<OwnActionCreditGuard.Reason, Long> withheld) {
        if (withheld == null || withheld.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(withheld.size() + 1);
        withheld.forEach((reason, count) -> {
            if (reason != null && count != null && count > 0L) {
                lines.add("§8 - §f" + reason.label() + ": " + count);
            }
        });
        if (lines.isEmpty()) return List.of();
        lines.addFirst("§7Kill credits not given (not the player's own action) since server start:");
        return List.copyOf(lines);
    }

    /**
     * Own-slot screens "opened" line (Owner decision A, 11-14): since the server started, how many screens that no
     * adapter records got an "opened" line marked "items not tracked", and how many screens no adapter records got none,
     * by reason (entity, item and command screens included, review-01 MINOR-1). Owner decision 32 A: how many of the
     * written lines were kept because the clicked block's item handler could not be read, only when there were any.
     * Nothing when there is none of these. It comes before the catch-all (adapter-miss) block; the command credit lines
     * follow that block, and they are last before the separator.
     */
    static List<String> itemsNotTrackedStatusLines(ItemsNotTrackedOpens.Counts counts) {
        if (counts == null || counts.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(counts.refused().size() + 2);
        lines.add("§7Screens no adapter records, since server start: " + counts.written()
                + " opened line(s) written, items not tracked");
        if (counts.blockItemsUnread() > 0L) {
            lines.add("§8 - §fopened line kept, the clicked block's item handler could not be read: "
                    + counts.blockItemsUnread());
        }
        counts.refused().forEach((reason, count) ->
                lines.add("§8 - §fno opened line, " + reason.label() + ": " + count));
        return List.copyOf(lines);
    }

    static List<String> containerAccessStatusLines(
            boolean configured,
            ContainerAccessMetrics metrics) {
        return containerAccessStatusLines(configured, metrics,
                io.github.betapetey.eventtrail.adapters.AdapterFailSafe.accessRefusals());
    }

    /**
     * As {@link #containerAccessStatusLines(boolean, ContainerAccessMetrics)}, with the access rows (B65/B66) each
     * adapter did not write since server start: one line naming every such adapter with its count, after the
     * diagnostics line, and nothing when there are none. Each of them also wrote one warning line per reason and site.
     */
    static List<String> containerAccessStatusLines(
            boolean configured,
            ContainerAccessMetrics metrics,
            Map<AdapterKey, Long> accessRefusals) {
        if (!configured) return List.of("§7Container access audit: §8Disabled");
        if (metrics == null) return List.of("§7Container access audit: §eEnabled; state unavailable");

        List<String> lines = new ArrayList<>(4);
        lines.add("§7Container access audit: §aEnabled §8(active §f"
                + metrics.activeSessions() + "§8)");
        lines.add("§7Container access lifecycle: §f"
                + metrics.opened() + " open, " + metrics.closed() + " close");
        if (metrics.unresolvedTargets() > 0L
                || metrics.unmatchedCloses() > 0L
                || metrics.abandoned() > 0L
                || metrics.duplicateOpens() > 0L) {
            lines.add("§7Container access diagnostics: §f"
                    + metrics.unresolvedTargets() + " unresolved, "
                    + metrics.unmatchedCloses() + " unmatched close, "
                    + metrics.abandoned() + " abandoned, "
                    + metrics.duplicateOpens() + " duplicate open");
        }
        if (accessRefusals != null && !accessRefusals.isEmpty()) {
            StringBuilder named = new StringBuilder();
            accessRefusals.forEach((key, count) -> {
                if (key == null || count == null || count <= 0L) return;
                if (!named.isEmpty()) named.append(", ");
                named.append(key.displayName()).append(": ").append(count);
            });
            if (!named.isEmpty()) {
                lines.add("§7Container access rows not written, since server start: §f" + named);
            }
        }
        return List.copyOf(lines);
    }

    static List<String> transportSecurityStatusLines(
            StorageTransportSecurity security, boolean connectionVerified) {
        if (security == null) return List.of();
        return switch (security.mode()) {
            case LOCAL, UNKNOWN -> List.of();
            case TLS_DISABLED -> List.of("§7TLS: §eDisabled");
            case VERIFIED_TLS -> {
                List<String> lines = new ArrayList<>(2);
                lines.add(connectionVerified
                        ? "§7TLS: §aActive; certificate and hostname verified"
                        : "§7TLS policy: §fCertificate and hostname verification required");
                lines.add(switch (security.trustSource()) {
                    case CONFIGURED_CA -> "§7CA trust: §fConfigured CA file";
                    case DRIVER_DEFAULT -> "§7CA trust: §fDriver default trust configuration";
                    case NOT_APPLICABLE, UNKNOWN -> "§7CA trust: §fUnknown";
                });
                yield List.copyOf(lines);
            }
        };
    }

    static List<String> compatibilityStatusLines(
            OptionalIntegrationCompatibility.Snapshot snapshot) {
        if (snapshot == null || snapshot.integrations().isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(snapshot.integrations().size() + 1);
        lines.add("§7Optional integration compatibility:");
        // GF-WARNINGS (B49): the display line folds in stopped hooks, off-path adapters, an OFF base, the Sable
        // provider and skip counts; the snapshot and the capture gate are unchanged.
        boolean locationProviderUnavailable = AuditBlockLocations.providerUnavailable();
        for (OptionalIntegrationCompatibility.IntegrationState state : snapshot.integrations()) {
            lines.add("§8 - §f" + IntegrationStatusDisplay.line(state, snapshot, locationProviderUnavailable));
        }
        return List.copyOf(lines);
    }

    static StorageStatus failedStorageStatus(Throwable error, long latencyMs) {
        return new StorageStatus(false, latencyMs, classifyStorageFailure(error));
    }

    static StorageFailureCategory classifyStorageFailure(Throwable error) {
        if (containsRejectedExecution(error)) {
            return StorageFailureCategory.HEALTH_CHECK_UNAVAILABLE;
        }
        EventTrailQueryException queryException = EventTrailQueryException.find(error);
        if (queryException != null) {
            return switch (queryException.getCategory()) {
                case STORAGE_UNAVAILABLE -> StorageFailureCategory.CONNECTION_FAILED;
                case TIMEOUT -> StorageFailureCategory.TIMEOUT;
                case DATA_READ_FAILURE, QUERY_FAILURE -> StorageFailureCategory.INTERNAL_ERROR;
            };
        }
        return switch (EventTrailDiagnostics.classifyDatabaseFailure(error)) {
            case CONNECTION_FAILED -> StorageFailureCategory.CONNECTION_FAILED;
            case TIMEOUT -> StorageFailureCategory.TIMEOUT;
            case OTHER -> StorageFailureCategory.INTERNAL_ERROR;
        };
    }

    private static boolean containsRejectedExecution(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof RejectedExecutionException) return true;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    static String stableBackendLabel(String backend) {
        return EventTrailDiagnostics.stableStorageBackendLabel(backend);
    }

    private static String sanitizeStatusValue(String value) {
        return EventTrailDiagnostics.sanitizeLogValue(value, EventTrailConfig.MAX_EXTERNAL_SERVER_ID_LENGTH)
                .replace('§', '?');
    }

    private static void sendStatusLine(CommandSourceStack source, String text) {
        EventTrailAvailabilityMessages.sendStatusIfAuthorized(
                () -> EventTrailPermissions.hasExactPermission(source, STATUS_PERMISSION),
                line -> source.sendSystemMessage(Component.literal(line)), text);
    }

    /** Pages structured lookup results, or inspect results when no lookup session is active. */
    public static int goToPage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        if (InvestigationSessions.REGISTRY.pageOwner(player.getUUID())
                != PageState.Owner.LOOKUP) {
            return InspectCommand.goToInspectPage(ctx);
        }
        LookupSession session = InvestigationSessions.REGISTRY
                .lookupContext(player.getUUID()).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal(
                    "§cNo results to page through. Run a lookup or inspect first."));
            return 0;
        }

        int page;
        try {
            page = IntegerArgumentType.getInteger(ctx, "num");
        } catch (Throwable t) {
            source.sendFailure(Component.literal("§cInvalid page number."));
            return 0;
        }
        String permissionDenial = revalidatePageLookupState(player.getUUID(), session,
                node -> EventTrailPermissions.hasExactPermission(source, node));
        if (permissionDenial != null) {
            source.sendFailure(Component.literal("§c" + permissionDenial));
            return 0;
        }
        return startPageQuery(source, player, session, Math.max(1, page));
    }

    /** Clearing this lets /eventtrail page return to inspect-mode paging after /eventtrail inspect is used. */
    static void clearLastLookup(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) invalidateLookupState(player.getUUID());
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining() == null ? "" : builder.getRemaining();
        if (remaining.trim().startsWith(INTERNAL_TELEPORT_PREFIX)) return builder.buildFuture();
        int lastSpace = remaining.lastIndexOf(' ');
        String before = lastSpace >= 0 ? remaining.substring(0, lastSpace).trim() : "";
        String token = lastSpace >= 0 ? remaining.substring(lastSpace + 1) : remaining;
        List<String> complete = splitTokens(before);

        SuggestionsBuilder target = builder;
        if (lastSpace >= 0) target = builder.createOffset(builder.getInput().length() - token.length());

        CommandSourceStack source = ctx.getSource();
        if (complete.isEmpty()) {
            suggestScope(source, target, token);
            return target.buildFuture();
        }

        if (!isCompleteScope(complete.get(0))) {
            suggestScope(source, target, token);
            return target.buildFuture();
        }

        if (complete.size() == 1) {
            suggestTime(target, token);
            return target.buildFuture();
        }

        if (!complete.get(1).toLowerCase(Locale.ROOT).startsWith("time:")) {
            suggestTime(target, token);
            return target.buildFuture();
        }

        suggestOptional(source, target, token, complete);
        return target.buildFuture();
    }

    private static void suggestScope(CommandSourceStack source, SuggestionsBuilder b, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("radius:")) {
            int max = EventTrailConfig.MAX_SEARCH_RADIUS.get();
            for (int r : new int[]{5, 10, 25, 50, 100, 250, 500, 1000}) {
                if (r <= max) suggestIfPrefix(b, "radius:" + r, lower);
            }
            return;
        }
        if (lower.startsWith("world:")) {
            if (!hasExactPermission(source, WORLD_PERMISSION)) return;
            for (String world : loadedWorldIds(source)) suggestIfPrefix(b, "world:" + world, lower);
            return;
        }
        suggestIfPrefix(b, "radius:", lower);
        if (hasExactPermission(source, WORLD_PERMISSION)) suggestIfPrefix(b, "world:", lower);
        if (hasExactPermission(source, GLOBAL_PERMISSION)) suggestIfPrefix(b, "global", lower);
    }

    private static void suggestTime(SuggestionsBuilder b, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || "time:".startsWith(lower)) suggestIfPrefix(b, "time:", lower);
        for (String s : new String[]{"time:30s", "time:15m", "time:30m", "time:1h", "time:2h", "time:6h", "time:12h", "time:1d", "time:3d", "time:1w", "time:2w"}) {
            suggestIfPrefix(b, s, lower);
        }
    }

    private static void suggestOptional(CommandSourceStack source, SuggestionsBuilder b, String token, List<String> complete) {
        String lower = token.toLowerCase(Locale.ROOT);
        Set<String> used = new LinkedHashSet<>();
        for (int i = 2; i < complete.size(); i++) {
            String t = complete.get(i).toLowerCase(Locale.ROOT);
            int colon = t.indexOf(':');
            if (colon > 0) {
                String key = t.substring(0, colon);
                if (!SUPPORTED_LOOKUP_FILTERS.contains(key) || !used.add(key)) return;
            }
        }

        if (lower.startsWith("action:")) {
            if (used.contains("action")) return;
            for (String suggestion : actionSuggestionsForToken(token)) {
                b.suggest(suggestion);
            }
            return;
        }
        if (lower.startsWith("per-page:")) {
            if (used.contains("per-page")) return;
            for (String s : new String[]{"per-page:5", "per-page:10", "per-page:25", "per-page:50"}) {
                suggestIfPrefix(b, s, lower);
            }
            return;
        }
        if (lower.startsWith("player:")) {
            for (String name : onlinePlayerNames(source)) suggestIfPrefix(b, "player:" + name, lower);
            return;
        }

        if (!used.contains("player")) suggestIfPrefix(b, "player:", lower);
        if (!used.contains("action")) {
            suggestIfPrefix(b, "action:", lower);
        }
        if (lower.startsWith("mod:")) {
            if (used.contains("mod")) return;
            for (String suggestion : modSuggestionsForToken(token, installedModIds())) b.suggest(suggestion);
            return;
        }

        if (!used.contains("block")) suggestIfPrefix(b, "block:", lower);
        if (!used.contains("item")) suggestIfPrefix(b, "item:", lower);
        if (!used.contains("entity")) suggestIfPrefix(b, "entity:", lower);
        if (!used.contains("mod")) suggestIfPrefix(b, "mod:", lower);
        if (!used.contains("per-page")) suggestIfPrefix(b, "per-page:", lower);
    }

    /**
     * Tab for mod: (Owner 11-22 5:12 AM item 13): the installed mod IDs plus "minecraft", completing the member
     * after the last ',' by prefix; members already typed are kept and not offered again.
     */
    static List<String> modSuggestionsForToken(String token, Collection<String> installedModIds) {
        if (token == null || !token.regionMatches(true, 0, "mod:", 0, "mod:".length())) return List.of();
        String value = token.substring("mod:".length());
        int separator = value.lastIndexOf(',');
        String completed = separator < 0 ? "" : value.substring(0, separator + 1);
        String partial = (separator < 0 ? value : value.substring(separator + 1)).toLowerCase(Locale.ROOT);
        Set<String> typed = new LinkedHashSet<>(List.of(completed.toLowerCase(Locale.ROOT).split(",")));
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(installedModIds);
        ids.add("minecraft");
        List<String> suggestions = new ArrayList<>();
        for (String id : ids) {
            if (id.startsWith(partial) && !typed.contains(id)) suggestions.add("mod:" + completed + id);
        }
        return List.copyOf(suggestions);
    }

    /** The installed mod IDs from the mod loader; empty when there is no mod list (unit tests). */
    static List<String> installedModIds() {
        try {
            net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
            if (mods == null) return List.of();
            List<String> ids = new ArrayList<>();
            for (net.neoforged.neoforgespi.language.IModInfo mod : mods.getMods()) ids.add(mod.getModId());
            return List.copyOf(ids);
        } catch (RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }

    /** Full-token replacements for the bounded comma-separated action grammar. */
    static List<String> actionSuggestionsForToken(String token) {
        if (token == null || !token.regionMatches(true, 0, "action:", 0, "action:".length())) {
            return List.of();
        }

        String value = token.substring("action:".length());
        int separator = value.lastIndexOf(',');
        String completed = separator < 0 ? "" : value.substring(0, separator);
        String partial = separator < 0 ? value : value.substring(separator + 1);
        if (!partial.equals(partial.trim()) || partial.indexOf(',') >= 0) return List.of();

        ActionSelection selected = ActionSelection.unrestricted();
        if (separator >= 0) {
            if (completed.isEmpty()) return List.of();
            ActionFilterParseResult parsed = parseActionFilter(completed);
            if (!parsed.successful()) return List.of();
            selected = parsed.selection();
        }

        String normalizedPartial = partial.toLowerCase(Locale.ROOT).replace('-', '_');
        String prefix = selected.restricted()
                ? "action:" + canonicalActions(selected) + ","
                : "action:";
        List<String> suggestions = new ArrayList<>();
        for (LookupActionSelector action : ACTIVE_ACTION_TYPES) {
            if (action.selectedBy(selected)) continue;
            String canonical = action.token();
            if (canonical.startsWith(normalizedPartial)) {
                suggestions.add(prefix + canonical);
            }
        }
        return List.copyOf(suggestions);
    }

    private static void suggestIfPrefix(SuggestionsBuilder b, String value, String lowerToken) {
        if (lowerToken.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(lowerToken)) b.suggest(value);
    }

    private static List<String> splitTokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String t : s.trim().split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    private static boolean isCompleteScope(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("global") || (lower.startsWith("radius:") && lower.length() > 7)
                || (lower.startsWith("world:") && lower.length() > 6);
    }

    public static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        String raw;
        try { raw = StringArgumentType.getString(ctx, "query"); }
        catch (Throwable t) { raw = ""; }
        String trimmed = raw.trim();
        if (trimmed.startsWith(INTERNAL_TELEPORT_PREFIX)) {
            return handleTeleportLink(source, player, trimmed);
        }
        return startQuery(source, player, raw, true);
    }

    private static int startQuery(CommandSourceStack source, ServerPlayer player, String raw, boolean announce) {
        if (!EventTrailManager.getInstance().isReady() || EventTrailManager.getInstance().getStorage() == null) {
            source.sendFailure(Component.literal("§c" + EventTrailAvailabilityMessages.unavailable(source)));
            return 0;
        }

        long snapshotTime = System.currentTimeMillis();
        Parsed parsed = parse(raw, source, player, snapshotTime);
        if (parsed.error != null) {
            source.sendFailure(Component.literal("§c" + parsed.error));
            return 0;
        }

        String permissionDenial = revalidateLookupState(player.getUUID(), parsed,
                node -> EventTrailPermissions.hasExactPermission(source, node));
        if (permissionDenial != null) {
            source.sendFailure(Component.literal("§c" + permissionDenial));
            return 0;
        }

        long started = System.currentTimeMillis();
        MinecraftServer server = source.getServer();
        UUID playerId = player.getUUID();
        BlockPos capturedCenter = parsed.scope == Scope.RADIUS
                ? player.blockPosition().immutable()
                : null;
        Object storage = EventTrailManager.getInstance().getStorage();
        if (!(storage instanceof AdvancedLogStorage advancedStorage)) {
            source.sendFailure(Component.literal("§cEventTrail storage does not support structured lookups."));
            return 0;
        }
        AdvancedQuery query = createAdvancedQuery(parsed, capturedCenter);
        RequestTransition transition = InvestigationSessions.REGISTRY
                .planInitialLookup(playerId);

        EventTrailQueryDispatcher.PlayerSubmission<QueryResult> submission = EventTrailQueryDispatcher.submitPlayerAndDispatch(
                playerId,
                () -> runInitialQuery(advancedStorage, query, started),
                () -> {
                    InvestigationSessions.REGISTRY.admit(transition);
                    clearTeleportLinks(playerId);
                    if (announce) player.sendSystemMessage(Component.literal("§7Searching EventTrail..."));
                },
                server::execute,
                () -> server.getPlayerList().getPlayer(playerId),
                player,
                (currentPlayer, result) -> {
                    if (!InvestigationSessions.REGISTRY.isPending(transition)) return;
                    LookupSession session = LookupSession.from(parsed, result.query());
                    String denial = revalidateLookupState(playerId, session,
                            node -> EventTrailPermissions.hasExactPermission(
                                    currentPlayer.createCommandSourceStack(), node));
                    if (denial != null) {
                        currentPlayer.sendSystemMessage(Component.literal("§c" + denial));
                        return;
                    }
                    if (!InvestigationSessions.REGISTRY.publishLookup(transition, session)) return;
                    render(currentPlayer, session, result);
                },
                (currentPlayer, error) -> {
                    if (!InvestigationSessions.REGISTRY.fail(transition)) return;
                    if (EventTrailQueryException.find(error) == null
                            && !EventTrailQueryDispatcher.isExpectedRejection(error)) {
                        LOGGER.error("Unexpected error during structured EventTrail lookup", error);
                    }
                    String message = EventTrailQueryDispatcher.isExpectedRejection(error)
                            ? EventTrailQueryDispatcher.busyMessage()
                            : EventTrailAvailabilityMessages.isUnavailable(error)
                            ? EventTrailAvailabilityMessages.unavailable(currentPlayer.createCommandSourceStack())
                            : EventTrailQueryException.safeUserMessage(error);
                    currentPlayer.sendSystemMessage(Component.literal("§c" + message));
                });
        if (!submission.accepted()) {
            source.sendFailure(Component.literal("§c" + EventTrailQueryDispatcher.busyMessage()));
            return 0;
        }
        return 1;
    }

    private static int startPageQuery(
            CommandSourceStack source, ServerPlayer player, LookupSession session, int requestedPage) {
        if (!EventTrailManager.getInstance().isReady() || EventTrailManager.getInstance().getStorage() == null) {
            source.sendFailure(Component.literal("§c" + EventTrailAvailabilityMessages.unavailable(source)));
            return 0;
        }
        Object storage = EventTrailManager.getInstance().getStorage();
        if (!(storage instanceof AdvancedLogStorage advancedStorage)) {
            source.sendFailure(Component.literal("§cEventTrail storage does not support structured lookups."));
            return 0;
        }

        long started = System.currentTimeMillis();
        MinecraftServer server = source.getServer();
        UUID playerId = player.getUUID();
        PlannedLookupPage<LookupSession> planned = InvestigationSessions.REGISTRY
                .planLookupPage(playerId).orElse(null);
        if (planned == null || !planned.context().equals(session)) {
            source.sendFailure(Component.literal(
                    "§cNo lookup results are available for that page request."));
            return 0;
        }
        RequestTransition transition = planned.transition();
        AdvancedQuery pageQuery = session.query().withPage(requestedPage);
        EventTrailQueryDispatcher.PlayerSubmission<QueryResult> submission = EventTrailQueryDispatcher.submitPlayerAndDispatch(
                playerId,
                () -> runQuery(advancedStorage, pageQuery, started),
                () -> {
                    InvestigationSessions.REGISTRY.admit(transition);
                    clearTeleportLinks(playerId);
                },
                server::execute,
                () -> server.getPlayerList().getPlayer(playerId),
                player,
                (currentPlayer, result) -> {
                    if (!InvestigationSessions.REGISTRY.isPending(transition)) return;
                    String denial = revalidatePageLookupState(playerId, session,
                            node -> EventTrailPermissions.hasExactPermission(
                                    currentPlayer.createCommandSourceStack(), node));
                    if (denial != null) {
                        currentPlayer.sendSystemMessage(Component.literal("§c" + denial));
                        return;
                    }
                    LookupSession updated = session.withQuery(result.query());
                    if (!InvestigationSessions.REGISTRY.publishLookup(transition, updated)) return;
                    render(currentPlayer, updated, result);
                },
                (currentPlayer, error) -> {
                    if (!InvestigationSessions.REGISTRY.fail(transition)) return;
                    if (EventTrailQueryException.find(error) == null
                            && !EventTrailQueryDispatcher.isExpectedRejection(error)) {
                        LOGGER.error("Unexpected error during structured EventTrail lookup paging", error);
                    }
                    String message = EventTrailQueryDispatcher.isExpectedRejection(error)
                            ? EventTrailQueryDispatcher.busyMessage()
                            : EventTrailAvailabilityMessages.isUnavailable(error)
                            ? EventTrailAvailabilityMessages.unavailable(currentPlayer.createCommandSourceStack())
                            : EventTrailQueryException.safeUserMessage(error);
                    currentPlayer.sendSystemMessage(Component.literal("§c" + message));
                });
        if (!submission.accepted()) {
            source.sendFailure(Component.literal("§c" + EventTrailQueryDispatcher.busyMessage()));
            return 0;
        }
        return 1;
    }

    static Parsed parse(
            String raw, CommandSourceStack source, ServerPlayer player, long snapshotTime) {
        Parsed p = new Parsed();
        p.perPage = DEFAULT_PER_PAGE;
        p.page = 1;

        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            p.error = "Choose a lookup scope first: radius:<blocks>, world:<dimension>, or global.";
            return p;
        }

        String inputError = validateLookupInput(trimmed);
        if (inputError != null) {
            p.error = inputError;
            return p;
        }

        String[] rawTokens = trimmed.split("\\s+");
        if (rawTokens.length < 1) {
            p.error = "Choose a lookup scope first.";
            return p;
        }

        String[] tokens = mergeLookupTokens(rawTokens);
        String tokenError = validateMergedLookupTokens(tokens);
        if (tokenError != null) {
            p.error = tokenError;
            return p;
        }
        String scopeToken = tokens[0];
        String scopeLower = scopeToken.toLowerCase(Locale.ROOT);
        List<String> baseTokens = new ArrayList<>();

        if (scopeLower.startsWith("radius:")) {
            String value = scopeToken.substring(scopeToken.indexOf(':') + 1);
            int radius;
            try { radius = Integer.parseInt(value); }
            catch (NumberFormatException e) { p.error = "Invalid radius: " + value; return p; }
            int max = EventTrailConfig.MAX_SEARCH_RADIUS.get();
            if (radius < 1 || radius > max) {
                p.error = "Radius must be between 1 and " + max + ".";
                return p;
            }
            p.scope = Scope.RADIUS;
            p.radius = radius;
            p.currentWorld = currentWorld(player);
            p.world = p.currentWorld;
            baseTokens.add("radius:" + radius);
        } else if (scopeLower.startsWith("world:")) {
            String value = scopeToken.substring(scopeToken.indexOf(':') + 1);
            if (value.isBlank()) { p.error = "world: requires a dimension id."; return p; }
            p.scope = Scope.WORLD;
            p.world = resolveWorld(value, source);
            if (p.world == null) {
                p.error = "Unknown world/dimension '" + value + "'. Use the world: autocomplete to select a loaded dimension.";
                return p;
            }
            baseTokens.add("world:" + p.world);
        } else if (scopeLower.equals("global")) {
            p.scope = Scope.GLOBAL;
            p.world = null;
            baseTokens.add("global");
        } else {
            p.error = "The first lookup option must be radius:<blocks>, world:<dimension>, or global.";
            return p;
        }

        if (tokens.length < 2 || !tokens[1].toLowerCase(Locale.ROOT).startsWith("time:")) {
            p.error = "Specify a time period after the scope, for example time:2h, time:3d, or time:2w.";
            return p;
        }

        String timeValue = tokens[1].substring(tokens[1].indexOf(':') + 1).toLowerCase(Locale.ROOT);
        long duration = parseDuration(timeValue);
        if (duration <= 0L) {
            p.error = "Invalid time value '" + timeValue + "'. Use values like 30m, 2h, 3d, 1w, or 2w.";
            return p;
        }
        p.durationMs = duration;
        p.timeLabel = timeValue;
        p.throughTime = snapshotTime;
        p.fromTime = snapshotTime - duration;
        baseTokens.add("time:" + timeValue);

        if (lookupComplexity(tokens, p.scope, duration) > MAX_QUERY_COMPLEXITY) {
            p.error = "That EventTrail lookup is too large or complex. Narrow the search and try again.";
            return p;
        }

        OptionalFilterTokenParseResult optionalFilters = parseOptionalFilterTokens(tokens);
        if (!optionalFilters.successful()) {
            p.error = optionalFilters.error();
            return p;
        }
        for (LookupFilterToken filter : optionalFilters.filters()) {
            String key = filter.key();
            String value = filter.value();
            switch (key) {
                case "player" -> {
                    if (value.isBlank()) { p.error = "player: requires a player name."; return p; }
                    p.player = value;
                    baseTokens.add("player:" + value);
                }
                case "action" -> {
                    ActionFilterParseResult parsedActions = parseActionFilter(value);
                    if (!parsedActions.successful()) {
                        p.error = parsedActions.error();
                        return p;
                    }
                    p.actionSelection = parsedActions.selection();
                    baseTokens.add("action:" + canonicalActions(p.actionSelection));
                }
                case "block", "item", "entity" -> {
                    LookupThingValue.Result things = LookupThingValue.parseThings(key, value,
                            name -> normalNameIds(key, name) != null);
                    if (!things.successful()) {
                        p.error = things.error();
                        return p;
                    }
                    List<ThingFilter> filters = new ArrayList<>();
                    for (LookupThingValue.Member member : things.members()) {
                        if (!member.quoted()) {
                            filters.add(ThingFilter.id(member.value()));
                            continue;
                        }
                        String folded = ThingFilter.foldName(member.value());
                        List<String> normalIds = normalNameIds(key, folded);
                        if (normalIds == null) {
                            p.error = key + ":" + member.typed() + " matches more than " + MAX_RESOLVED_FILTER_VALUES
                                    + " normal names. Narrow the name.";
                            return p;
                        }
                        filters.add(ThingFilter.name(folded, normalIds));
                    }
                    if (totalThingValues(p) + filters.size() > MAX_RESOLVED_FILTER_VALUES) {
                        p.error = "That EventTrail lookup is too large or complex. Narrow the search and try again.";
                        return p;
                    }
                    switch (key) {
                        case "block" -> p.blocks = List.copyOf(filters);
                        case "item" -> p.items = List.copyOf(filters);
                        default -> p.entities = List.copyOf(filters);
                    }
                    baseTokens.add(key + ":" + things.canonical());
                }
                case "mod" -> {
                    LookupThingValue.Result mods = LookupThingValue.parseMods(value);
                    if (!mods.successful()) {
                        p.error = mods.error();
                        return p;
                    }
                    List<String> ids = new ArrayList<>();
                    for (LookupThingValue.Member member : mods.members()) {
                        if (!ids.contains(member.value())) ids.add(member.value());
                    }
                    // R35: an ID no installed mod has (a removed mod) is searched like any other. G16 (a): when such
                    // a search finds nothing, noMatchLines adds "<id> is not installed.".
                    p.mods = List.copyOf(ids);
                    baseTokens.add("mod:" + String.join(",", ids));
                }
                case "per-page" -> {
                    PerPageParseResult parsedPageSize = parsePerPageValue(value);
                    if (!parsedPageSize.successful()) {
                        p.error = parsedPageSize.error();
                        return p;
                    }
                    int n = parsedPageSize.perPage();
                    p.perPage = n;
                    if (n != DEFAULT_PER_PAGE) baseTokens.add("per-page:" + n);
                }
                default -> throw new IllegalStateException("Validated lookup filter was not handled: " + key);
            }
        }
        p.baseQuery = String.join(" ", baseTokens);
        return p;
    }

    private static int totalThingValues(Parsed p) {
        return p.blocks.size() + p.items.size() + p.entities.size() + p.mods.size();
    }

    /**
     * The registry IDs whose normal (game) name matches a folded quoted name, whole and ignoring case, {@code *}
     * as the wildcard: the "normal name" half of a quoted block:, item: or entity: value. Empty when the
     * registries cannot be read (unit tests without a game); null when more than
     * {@link #MAX_RESOLVED_FILTER_VALUES} IDs match.
     */
    static List<String> normalNameIds(String key, String foldedName) {
        List<String> ids = new ArrayList<>();
        try {
            switch (key) {
                case "block" -> {
                    for (Block block : BuiltInRegistries.BLOCK) {
                        if (!addNormalNameId(ids, foldedName, DisplayNameResolver.blockDisplayName(block),
                                BuiltInRegistries.BLOCK.getKey(block))) return null;
                    }
                }
                case "item" -> {
                    for (Item item : BuiltInRegistries.ITEM) {
                        if (!addNormalNameId(ids, foldedName, DisplayNameResolver.itemTypeName(item),
                                BuiltInRegistries.ITEM.getKey(item))) return null;
                    }
                }
                default -> {
                    for (net.minecraft.world.entity.EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                        if (!addNormalNameId(ids, foldedName, type.getDescription().getString(),
                                BuiltInRegistries.ENTITY_TYPE.getKey(type))) return null;
                    }
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // No game registries (unit tests): the logged-name half still matches.
            return List.of();
        }
        return List.copyOf(ids);
    }

    /** Adds the ID when its normal name matches; false once more than the cap would be added. */
    private static boolean addNormalNameId(
            List<String> ids, String foldedName, String normalName, net.minecraft.resources.ResourceLocation key) {
        if (key == null || normalName == null) return true;
        if (!ThingFilter.nameMatches(foldedName, normalName)) return true;
        if (ids.size() >= MAX_RESOLVED_FILTER_VALUES) return false;
        ids.add(key.toString());
        return true;
    }

    private static boolean isQuote(char character) {
        return character == '"' || character == '\'';
    }

    static PerPageParseResult parsePerPageValue(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return new PerPageParseResult(null, "Invalid per-page value: " + value);
        }
        if (parsed < 1 || parsed > MAX_PER_PAGE) {
            return new PerPageParseResult(
                    null, "per-page must be between 1 and " + MAX_PER_PAGE + ".");
        }
        return new PerPageParseResult(parsed, null);
    }

    /**
     * Splits the already-bounded optional lookup tokens without interpreting
     * their values. Production parsing consumes this exact result, so one
     * filter value cannot absorb, replace, or skip a neighboring filter.
     */
    static OptionalFilterTokenParseResult parseOptionalFilterTokens(String[] tokens) {
        List<LookupFilterToken> filters = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 2; tokens != null && i < tokens.length; i++) {
            String token = tokens[i];
            if (token == null || token.isBlank()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            int colon = lower.indexOf(':');
            if (colon <= 0) {
                return OptionalFilterTokenParseResult.failure(
                        "Unknown lookup option: " + token);
            }
            String key = lower.substring(0, colon);
            if (key.equals("name")) {
                return OptionalFilterTokenParseResult.failure(NAME_FILTER_REMOVED);
            }
            if (!isSupportedLookupFilter(key)) {
                return OptionalFilterTokenParseResult.failure(
                        "Unknown lookup option: " + token);
            }
            if (!seen.add(key)) {
                return OptionalFilterTokenParseResult.failure(
                        key + ": was specified more than once.");
            }
            filters.add(new LookupFilterToken(
                    key, token.substring(token.indexOf(':') + 1)));
        }
        return OptionalFilterTokenParseResult.success(filters);
    }

    private static String revalidateLookupState(
            UUID playerId, Parsed parsed, Predicate<String> permissionCheck) {
        return revalidateLookupState(
                playerId,
                parsed.scope == Scope.WORLD,
                parsed.scope == Scope.GLOBAL,
                parsed.durationMs,
                permissionCheck);
    }

    private static String revalidateLookupState(
            UUID playerId, LookupSession session, Predicate<String> permissionCheck) {
        return revalidateLookupState(
                playerId,
                session.scope() == Scope.WORLD,
                session.scope() == Scope.GLOBAL,
                session.durationMs(),
                permissionCheck);
    }

    private static String revalidatePageLookupState(
            UUID playerId, LookupSession session, Predicate<String> permissionCheck) {
        return revalidatePageLookupState(
                playerId,
                session.scope() == Scope.WORLD,
                session.scope() == Scope.GLOBAL,
                session.durationMs(),
                EventTrailConfig.LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION.get(),
                permissionCheck);
    }

    static String revalidatePageLookupState(
            UUID playerId,
            boolean worldScope,
            boolean globalScope,
            long durationMs,
            int maxDaysWithoutExtendedPermission,
            Predicate<String> permissionCheck) {
        if (!permissionCheck.test(PAGE_PERMISSION)) {
            invalidateLookupState(playerId);
            return "You don't have permission to page EventTrail results.";
        }
        return revalidateLookupState(
                playerId,
                worldScope,
                globalScope,
                durationMs,
                maxDaysWithoutExtendedPermission,
                permissionCheck);
    }

    static String revalidateLookupState(
            UUID playerId,
            boolean worldScope,
            boolean globalScope,
            long durationMs,
            Predicate<String> permissionCheck) {
        return revalidateLookupState(
                playerId,
                worldScope,
                globalScope,
                durationMs,
                EventTrailConfig.LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION.get(),
                permissionCheck);
    }

    static String revalidateLookupState(
            UUID playerId,
            boolean worldScope,
            boolean globalScope,
            long durationMs,
            int maxDaysWithoutExtendedPermission,
            Predicate<String> permissionCheck) {
        String denial = currentLookupPermissionDenial(
                worldScope,
                globalScope,
                durationMs,
                maxDaysWithoutExtendedPermission,
                permissionCheck);
        if (denial != null) invalidateLookupState(playerId);
        return denial;
    }

    static String currentLookupPermissionDenial(
            boolean worldScope,
            boolean globalScope,
            long durationMs,
            Predicate<String> permissionCheck) {
        return currentLookupPermissionDenial(
                worldScope,
                globalScope,
                durationMs,
                EventTrailConfig.LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION.get(),
                permissionCheck);
    }

    static String currentLookupPermissionDenial(
            boolean worldScope,
            boolean globalScope,
            long durationMs,
            int maxDaysWithoutExtendedPermission,
            Predicate<String> permissionCheck) {
        if (!permissionCheck.test(LOOKUP_PERMISSION)) {
            return "You don't have permission to use EventTrail lookups.";
        }
        if (worldScope && !permissionCheck.test(WORLD_PERMISSION)) {
            return "You don't have permission to use whole-world EventTrail lookups.";
        }
        if (globalScope && !permissionCheck.test(GLOBAL_PERMISSION)) {
            return "You don't have permission to use global EventTrail lookups.";
        }
        if (requiresExtendedHistoryPermission(durationMs, maxDaysWithoutExtendedPermission)
                && !permissionCheck.test(EXTENDED_PERMISSION)) {
            String unit = maxDaysWithoutExtendedPermission == 1 ? "day" : "days";
            return "You may only search up to " + maxDaysWithoutExtendedPermission + " " + unit
                    + " of EventTrail history.";
        }
        return null;
    }

    static boolean requiresExtendedHistoryPermission(
            long durationMs, int maxDaysWithoutExtendedPermission) {
        if (maxDaysWithoutExtendedPermission < 0
                || maxDaysWithoutExtendedPermission
                        > EventTrailConfig.MAX_LOOKUP_DAYS_WITHOUT_EXTENDED_PERMISSION) {
            throw new IllegalArgumentException("Lookup history limit is outside its validated range.");
        }
        if (maxDaysWithoutExtendedPermission == 0) return false;
        long ordinaryWindowMs = Math.multiplyExact(
                (long) maxDaysWithoutExtendedPermission, MILLIS_PER_DAY);
        return durationMs > ordinaryWindowMs;
    }

    static void invalidateLookupState(UUID playerId) {
        InvestigationSessions.REGISTRY.revokeLookup(playerId);
        clearTeleportLinks(playerId);
    }

    static void clearTeleportLinks(UUID playerId) {
        TELEPORT_LINKS.remove(playerId);
    }

    static boolean hasActiveTeleportLinks(UUID playerId) {
        ConcurrentHashMap<String, TeleportLink> links = TELEPORT_LINKS.get(playerId);
        return links != null && !links.isEmpty();
    }

    public static void onPlayerLogout(UUID playerId) {
        InvestigationSessions.REGISTRY.logout(playerId);
        clearTeleportLinks(playerId);
        EventTrailQueryDispatcher.clearPlayer(playerId);
    }

    public static void clearRuntimeState() {
        InvestigationSessions.REGISTRY.clearAll();
        TELEPORT_LINKS.clear();
    }

    static boolean hasTeleportPermission(Predicate<String> permissionCheck) {
        return permissionCheck.test(TELEPORT_PERMISSION);
    }

    static AdvancedQuery createAdvancedQuery(Parsed p, BlockPos capturedCenter) {
        return new AdvancedQuery(
                p.fromTime,
                p.throughTime,
                Long.MAX_VALUE,
                p.scope == Scope.GLOBAL ? null : p.world,
                capturedCenter,
                p.scope == Scope.RADIUS ? p.radius : 0,
                p.player,
                p.actionSelection,
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                p.perPage,
                p.page,
                null,
                new ThingFilters(p.blocks, p.items, p.entities, p.mods)
        );
    }

    /**
     * The line shown when a lookup with a block:, item:, entity: or mod: filter finds nothing (Owner 11-22
     * 5:12 AM item 8; mock text): {@code No match for item:diamond. Hover names for IDs.} Null when the base
     * query has no such filter. NOT YET SHOWN: the line points at the name hovers, which the held presentation
     * track builds, so the render call joins with that track; it calls {@link #noMatchLines}, which adds G16's
     * "not installed" lines.
     */
    static String noMatchLine(String baseQuery) {
        if (baseQuery == null) return null;
        List<String> filters = new ArrayList<>();
        for (String token : mergeLookupTokens(baseQuery.split(" "))) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("block:") || lower.startsWith("item:") || lower.startsWith("entity:")
                    || lower.startsWith("mod:")) {
                filters.add(token);
            }
        }
        return filters.isEmpty() ? null : "No match for " + String.join(" ", filters) + ". Hover names for IDs.";
    }

    /**
     * The lines shown when a lookup with a block:, item:, entity: or mod: filter finds nothing: {@link #noMatchLine},
     * then G16 (a) (lead D-380): for each mod: ID that no installed mod has, {@code <id> is not installed.}, in the
     * order typed. Such an ID is still searched like any other (R35: entries from a removed mod still match), so
     * the note appears only when the search finds nothing. "minecraft" always counts as installed, as in Tab. Empty
     * when the base query has no such filter.
     */
    static List<String> noMatchLines(String baseQuery, Collection<String> installedModIds) {
        String first = noMatchLine(baseQuery);
        if (first == null) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add(first);
        Set<String> installed = new LinkedHashSet<>(installedModIds == null ? List.of() : installedModIds);
        installed.add("minecraft");
        for (String token : mergeLookupTokens(baseQuery.split(" "))) {
            if (!token.regionMatches(true, 0, "mod:", 0, "mod:".length())) continue;
            for (String id : token.substring("mod:".length()).split(",")) {
                if (!id.isEmpty() && !installed.contains(id)) {
                    lines.add(id + " is not installed.");
                    installed.add(id);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static QueryResult runInitialQuery(
            AdvancedLogStorage storage, AdvancedQuery query, long started) {
        long highWaterId = storage.captureHighWaterMark();
        return runQuery(storage, query.withHighWaterId(highWaterId), started);
    }

    private static QueryResult runQuery(
            AdvancedLogStorage storage, AdvancedQuery query, long started) {
        AdvancedQueryResult result = storage.queryAdvanced(query);
        // Explosion split (Advisor M-6 d, e): verdicts are read here, on the query thread, at the page's id mark;
        // each read has its own guard and reads "not checked" on any failure.
        ExplosionVerdicts verdicts = ExplosionVerdicts.read(
                result.entries(), storage::explosionParts, query.highWaterId());
        return new QueryResult(
                result.total(),
                result.entries(),
                result.page(),
                result.totalPages(),
                System.currentTimeMillis() - started,
                query.withPage(result.page()),
                verdicts
        );
    }

    private static void render(ServerPlayer player, LookupSession session, QueryResult result) {
        beginTeleportResultPage(player.getUUID(), System.currentTimeMillis());
        PageWindow<AuditRecord> page = PageWindow.fromPage(
                result.entries(), result.total(), result.page(), session.perPage());
        LookupSummaryView summary = new LookupSummaryView(
                result.total(),
                session.scope() == Scope.RADIUS ? session.radius() : null,
                session.timeLabel(),
                session.scope() == Scope.GLOBAL ? "All dimensions" : session.world(),
                result.elapsedMs());
        LookupResultsView view = ENTRY_PRESENTER.lookup(
                summary,
                page,
                session.scope() == Scope.GLOBAL,
                record -> createNavigationTarget(
                        player.getUUID(), record, System.currentTimeMillis()),
                result.verdicts());
        RESULTS_RENDERER.render(view).forEach(player::sendSystemMessage);
        if (result.total() == 0L) {
            InvestigationSessions.REGISTRY.revokeLookup(player.getUUID());
        }
    }

    static java.util.Optional<String> createNavigationTarget(
            UUID viewerId, AuditRecord record, long now) {
        if (record == null || record.location() == null
                || record.location().dimensionId() == null
                || record.location().position() == null) {
            return java.util.Optional.empty();
        }
        BlockPos position = record.location().position();
        return java.util.Optional.of(createTeleportCommand(
                viewerId,
                record.location().dimensionId().toString(),
                position.getX(), position.getY(), position.getZ(),
                now));
    }

    private static int handleTeleportLink(CommandSourceStack source, ServerPlayer player, String query) {
        TeleportLink link = resolveTeleportLink(player.getUUID(), query, System.currentTimeMillis());
        if (link == null) {
            source.sendFailure(Component.literal("§cThat EventTrail teleport link is no longer valid."));
            return 0;
        }
        if (!hasTeleportPermission(
                node -> EventTrailPermissions.hasExactPermission(source, node))) {
            source.sendFailure(Component.literal("§cYou don't have permission to teleport to EventTrail results."));
            return 0;
        }

        String worldId = link.worldId();
        ServerLevel target = findLoadedWorld(source.getServer(), worldId);
        if (target == null) {
            source.sendFailure(Component.literal("§cThat dimension is not currently loaded: " + worldId));
            return 0;
        }

        int x = link.x();
        int y = link.y();
        int z = link.z();
        BlockPos safe = findSafeTeleportPosition(target, player, new BlockPos(x, y, z));
        if (safe == null) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "§cCould not find a safe teleport position near %d,%d,%d in %s.", x, y, z, worldId)));
            return 0;
        }

        double targetX = safe.getX() + 0.5D;
        double targetY = safe.getY();
        double targetZ = safe.getZ() + 0.5D;
        try {
            player.teleportTo(target, targetX, targetY, targetZ,
                    player.getYRot(), player.getXRot());
        } catch (RuntimeException failure) {
            LOGGER.warn("EventTrail result teleport did not complete ({}).",
                    EventTrailDiagnostics.safeFailureSummary(failure));
            source.sendFailure(Component.literal(
                    "§cEventTrail could not complete that teleport. The link remains available."));
            return 0;
        }

        boolean arrived = reachedTeleportDestination(
                worldId, targetX, targetY, targetZ,
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ());
        if (!completeTeleportLink(player.getUUID(), query, link, arrived)) {
            source.sendFailure(Component.literal(
                    "§cEventTrail could not complete that teleport. The link remains available."));
            return 0;
        }
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "§6EventTrail §8» §7Teleported to §f%d,%d,%d §7in §b%s§7.",
                safe.getX(), safe.getY(), safe.getZ(), worldId)));
        return 1;
    }

    static boolean reachedTeleportDestination(
            String expectedWorld,
            double expectedX,
            double expectedY,
            double expectedZ,
            String actualWorld,
            double actualX,
            double actualY,
            double actualZ) {
        return expectedWorld != null
                && expectedWorld.equals(actualWorld)
                && Math.abs(actualX - expectedX) <= TELEPORT_COMPLETION_TOLERANCE
                && Math.abs(actualY - expectedY) <= TELEPORT_COMPLETION_TOLERANCE
                && Math.abs(actualZ - expectedZ) <= TELEPORT_COMPLETION_TOLERANCE;
    }

    static boolean completeTeleportLink(
            UUID playerId, String query, TeleportLink link, boolean arrived) {
        if (!arrived) return false;
        removeTeleportLink(playerId, query, link);
        return true;
    }

    private static void beginTeleportResultPage(UUID playerId, long now) {
        pruneExpiredTeleportLinks(now);
        TELEPORT_LINKS.put(playerId, new ConcurrentHashMap<>());
    }

    private static String createTeleportCommand(UUID playerId, String worldId,
                                                int x, int y, int z, long now) {
        ConcurrentHashMap<String, TeleportLink> playerLinks =
                TELEPORT_LINKS.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        if (playerLinks.size() >= MAX_TELEPORT_LINKS_PER_PLAYER) {
            String oldestToken = null;
            long oldestCreation = Long.MAX_VALUE;
            for (var entry : playerLinks.entrySet()) {
                if (entry.getValue().createdAt() < oldestCreation) {
                    oldestCreation = entry.getValue().createdAt();
                    oldestToken = entry.getKey();
                }
            }
            if (oldestToken != null) playerLinks.remove(oldestToken);
        }

        String token;
        TeleportLink link = new TeleportLink(playerId, worldId, x, y, z,
                now, now + TELEPORT_LINK_TTL_MS);
        do {
            token = UUID.randomUUID().toString();
        } while (playerLinks.putIfAbsent(token, link) != null);
        return EventTrailCommands.CANONICAL_PREFIX + " lookup " + INTERNAL_TELEPORT_PREFIX + token;
    }

    static TeleportLink resolveTeleportLink(UUID playerId, String query, long now) {
        pruneExpiredTeleportLinks(now);
        String token = teleportToken(query);
        if (token == null) return null;

        ConcurrentHashMap<String, TeleportLink> playerLinks = TELEPORT_LINKS.get(playerId);
        if (playerLinks == null) return null;
        TeleportLink link = playerLinks.get(token);
        if (link == null || !playerId.equals(link.playerId()) || now >= link.expiresAt()) {
            if (link != null) playerLinks.remove(token, link);
            return null;
        }
        return link;
    }

    private static String teleportToken(String query) {
        if (query == null || !query.startsWith(INTERNAL_TELEPORT_PREFIX)) return null;
        String token = query.substring(INTERNAL_TELEPORT_PREFIX.length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) return null;
        try {
            UUID parsed = UUID.fromString(token);
            return parsed.toString().equals(token) ? token : null;
        } catch (IllegalArgumentException invalidToken) {
            return null;
        }
    }

    private static void removeTeleportLink(UUID playerId, String query, TeleportLink link) {
        String token = teleportToken(query);
        if (token == null) return;
        ConcurrentHashMap<String, TeleportLink> playerLinks = TELEPORT_LINKS.get(playerId);
        if (playerLinks == null) return;
        playerLinks.remove(token, link);
        if (playerLinks.isEmpty()) TELEPORT_LINKS.remove(playerId, playerLinks);
    }

    private static void pruneExpiredTeleportLinks(long now) {
        for (var playerEntry : TELEPORT_LINKS.entrySet()) {
            ConcurrentHashMap<String, TeleportLink> playerLinks = playerEntry.getValue();
            playerLinks.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
            if (playerLinks.isEmpty()) TELEPORT_LINKS.remove(playerEntry.getKey(), playerLinks);
        }
    }

    private static ServerLevel findLoadedWorld(MinecraftServer server, String worldId) {
        if (server == null || worldId == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (worldId.equalsIgnoreCase(level.dimension().location().toString())) return level;
        }
        return null;
    }

    private static BlockPos findSafeTeleportPosition(
            ServerLevel level, ServerPlayer player, BlockPos loggedPos) {
        // Prefer standing one block above the logged block, then search a small
        // vertical window while retaining the historical X/Z coordinate.
        return findSafeTeleportPosition(
                loggedPos,
                level.getMinBuildHeight(),
                level.getMaxBuildHeight(),
                feet -> isSafeStandingPosition(level, player, feet));
    }

    static BlockPos findSafeTeleportPosition(
            BlockPos loggedPos,
            int minBuildHeight,
            int maxBuildHeight,
            StandingPositionPredicate safety) {
        long preferredY = (long) loggedPos.getY() + 1L;
        int[] offsets = {0, 1, 2, 3, 4, 5, -1, -2, -3};
        for (int offset : offsets) {
            long candidateY = preferredY + offset;
            if (candidateY < minBuildHeight || candidateY >= maxBuildHeight) continue;
            BlockPos feet = new BlockPos(loggedPos.getX(), (int) candidateY, loggedPos.getZ());
            if (safety.test(feet)) return feet;
        }
        return null;
    }

    private static boolean isSafeStandingPosition(
            ServerLevel level, ServerPlayer player, BlockPos feet) {
        AABB standingBox = standingPlayerBox(player, feet);
        boolean withinBuildHeight = isStandingBoxWithinBuildHeight(
                standingBox, level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (!withinBuildHeight) {
            return false;
        }
        boolean withinWorldBorder = level.getWorldBorder().isWithinBounds(standingBox);
        if (!withinWorldBorder) {
            return false;
        }

        AABB collisionBox = standingBox.deflate(COLLISION_EPSILON);
        // getFluidState synchronously obtains only the chunk(s) overlapped by
        // this bounded player box before collision iteration examines them.
        boolean fluidFree = !containsFluid(collisionBox, level::getFluidState);
        if (!fluidFree) {
            return false;
        }
        // noCollision checks block shapes, collidable entities according to
        // player.canCollideWith, and the world-border collision shape.
        boolean collisionFree = level.noCollision(player, collisionBox);
        if (!collisionFree) {
            return false;
        }

        // The unshifted box is collision-free. Moving it down a tiny amount
        // must introduce block collision, proving actual support beneath the
        // player's footprint rather than merely finding a non-empty floor shape.
        AABB supportProbe = standingSupportProbe(standingBox);
        boolean supported = !level.noBlockCollision(player, supportProbe);
        return meetsStandingSafetyCriteria(
                withinBuildHeight, withinWorldBorder, collisionFree, fluidFree, supported);
    }

    static AABB standingPlayerBox(ServerPlayer player, BlockPos feet) {
        return standingPlayerBox(player.getDimensions(Pose.STANDING), feet);
    }

    static AABB standingPlayerBox(EntityDimensions standingDimensions, BlockPos feet) {
        return standingDimensions.makeBoundingBox(
                feet.getX() + 0.5D,
                feet.getY(),
                feet.getZ() + 0.5D);
    }

    static boolean isStandingBoxWithinBuildHeight(
            AABB standingBox, int minBuildHeight, int maxBuildHeight) {
        return standingBox.minY >= minBuildHeight && standingBox.maxY <= maxBuildHeight;
    }

    static AABB standingSupportProbe(AABB standingBox) {
        return standingBox.deflate(COLLISION_EPSILON)
                .move(0.0D, -SUPPORT_PROBE_DEPTH, 0.0D);
    }

    static boolean meetsStandingSafetyCriteria(
            boolean withinBuildHeight,
            boolean withinWorldBorder,
            boolean collisionFree,
            boolean fluidFree,
            boolean supported) {
        return withinBuildHeight
                && withinWorldBorder
                && collisionFree
                && fluidFree
                && supported;
    }

    static boolean fluidBlocksStandingSpace(FluidState fluid) {
        return fluid != null && !fluid.isEmpty();
    }

    static boolean containsFluid(AABB standingBox, FluidStateLookup fluidLookup) {
        int minX = net.minecraft.util.Mth.floor(standingBox.minX + COLLISION_EPSILON);
        int minY = net.minecraft.util.Mth.floor(standingBox.minY + COLLISION_EPSILON);
        int minZ = net.minecraft.util.Mth.floor(standingBox.minZ + COLLISION_EPSILON);
        int maxX = net.minecraft.util.Mth.floor(standingBox.maxX - COLLISION_EPSILON);
        int maxY = net.minecraft.util.Mth.floor(standingBox.maxY - COLLISION_EPSILON);
        int maxZ = net.minecraft.util.Mth.floor(standingBox.maxZ - COLLISION_EPSILON);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    FluidState fluid = fluidLookup.get(cursor);
                    if (fluidBlocksStandingSpace(fluid)) return true;
                }
            }
        }
        return false;
    }

    private static String currentWorld(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    private static String resolveWorld(String value, CommandSourceStack source) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("overworld") || v.equals("minecraft:overworld")) return "minecraft:overworld";
        if (v.equals("nether") || v.equals("the_nether") || v.equals("minecraft:nether")) return "minecraft:the_nether";
        if (v.equals("end") || v.equals("the_end") || v.equals("minecraft:end")) return "minecraft:the_end";

        List<String> worlds = loadedWorldIds(source);
        if (v.contains(":")) {
            for (String id : worlds) if (id.equalsIgnoreCase(v)) return id;
            // A namespaced dimension may be valid even if it is lazily loaded, so keep it usable.
            return v;
        }

        String match = null;
        for (String id : worlds) {
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            if (path.equalsIgnoreCase(v)) {
                if (match != null && !match.equalsIgnoreCase(id)) return null;
                match = id;
            }
        }
        return match;
    }

    private static List<String> loadedWorldIds(CommandSourceStack source) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        source.getServer().levelKeys().forEach(key -> ids.add(key.location().toString()));
        if (ids.isEmpty()) {
            ids.add("minecraft:overworld");
            ids.add("minecraft:the_nether");
            ids.add("minecraft:the_end");
        }
        List<String> out = new ArrayList<>(ids);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> onlinePlayerNames(CommandSourceStack source) {
        List<String> out = new ArrayList<>();
        source.getServer().getPlayerList().getPlayers().forEach(player ->
                out.add(player.getGameProfile().getName()));
        return out;
    }

    /**
     * Joins the words of a block:, item:, entity: or mod: value, so that quoted names keep their spaces and
     * quotes, e.g. item:"Lucky Pick" or block:"Vault: North".
     *
     * <ul>
     *   <li>Inside a quoted comma member ("..." or '...'), every word joins, even one holding ':'.</li>
     *   <li>After a trailing ',' the next word joins (the next member).</li>
     *   <li>After the key alone (block: "Vault: North"), a word that opens a quote joins, even one holding ':'.</li>
     *   <li>Otherwise a word holding ':' starts a new filter; a word without one joins a value that holds no quote
     *   yet (an unquoted multi-word value, which parse refuses with the quote hint), and is its own token after a
     *   closed quoted value. A quote opens a quoted value only at the start of a comma member, so an apostrophe
     *   inside a word is a plain character.</li>
     * </ul>
     */
    static String[] mergeLookupTokens(String[] raw) {
        if (raw == null || raw.length <= 2) return raw == null ? new String[0] : raw;

        List<StringBuilder> merged = new ArrayList<>();
        merged.add(new StringBuilder(raw[0]));
        if (raw.length > 1) merged.add(new StringBuilder(raw[1]));
        StringBuilder continuation = null;

        for (int i = 2; i < raw.length; i++) {
            String token = raw[i];
            if (token == null || token.isBlank()) continue;

            if (continuation != null && (insideQuotes(continuation) || endsWithComma(continuation)
                    || (valueIsEmpty(continuation) && isQuote(token.charAt(0))))) {
                continuation.append(' ').append(token);
                continue;
            }

            if (token.indexOf(':') > 0) {
                StringBuilder next = new StringBuilder(token);
                merged.add(next);
                String key = token.substring(0, token.indexOf(':')).toLowerCase(Locale.ROOT);
                continuation = THING_FILTER_KEYS.contains(key) ? next : null;
                continue;
            }

            if (continuation != null && !holdsQuote(continuation)) {
                continuation.append(' ').append(token);
                continue;
            }

            merged.add(new StringBuilder(token));
            continuation = null;
        }

        String[] result = new String[merged.size()];
        for (int i = 0; i < merged.size(); i++) result[i] = merged.get(i).toString();
        return result;
    }

    /** The filters whose values may hold spaces (quoted names, and unquoted words that parse then refuses). */
    private static final Set<String> THING_FILTER_KEYS = Set.of("block", "item", "entity", "mod");

    private static final int NO_QUOTE = 0;
    private static final int CLOSED_QUOTE = 1;
    private static final int OPEN_QUOTE = 2;

    /**
     * Whether the filter token's value holds a quoted member: none, one that is closed, or one that is still open
     * at the end. A quote ("..." or '...') opens a member only at the start of a comma member, so an apostrophe
     * inside an unquoted word is a plain character.
     */
    private static int quoteState(CharSequence filterToken) {
        String token = filterToken.toString();
        char open = 0;
        boolean held = false;
        boolean memberStart = true;
        for (int i = token.indexOf(':') + 1; i < token.length(); i++) {
            char character = token.charAt(i);
            if (open != 0) {
                if (character == open) open = 0;
            } else if (character == ',') {
                memberStart = true;
            } else if (character != ' ') {
                if (memberStart && isQuote(character)) {
                    open = character;
                    held = true;
                }
                memberStart = false;
            }
        }
        return open != 0 ? OPEN_QUOTE : held ? CLOSED_QUOTE : NO_QUOTE;
    }

    /** True when the filter token's value ends inside a quoted member. */
    private static boolean insideQuotes(CharSequence filterToken) {
        return quoteState(filterToken) == OPEN_QUOTE;
    }

    /** True when the filter token is only its key and ':' (spaces may follow), so its value has not started. */
    private static boolean valueIsEmpty(CharSequence filterToken) {
        String token = filterToken.toString();
        return token.substring(token.indexOf(':') + 1).isBlank();
    }

    private static boolean endsWithComma(CharSequence filterToken) {
        return filterToken.length() > 0 && filterToken.charAt(filterToken.length() - 1) == ',';
    }

    private static boolean holdsQuote(CharSequence filterToken) {
        return quoteState(filterToken) != NO_QUOTE;
    }

    static String validateLookupInput(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > MAX_LOOKUP_INPUT_LENGTH) {
            return "That EventTrail lookup is too large or complex. Narrow the search and try again.";
        }

        int tokens = 0;
        boolean inToken = false;
        for (int i = 0; i < raw.length(); i++) {
            boolean whitespace = Character.isWhitespace(raw.charAt(i));
            if (!whitespace && !inToken) {
                tokens++;
                if (tokens > MAX_LOOKUP_TOKENS) {
                    return "That EventTrail lookup is too large or complex. Narrow the search and try again.";
                }
            }
            inToken = !whitespace;
        }
        return null;
    }

    static String validateMergedLookupTokens(String[] tokens) {
        int filters = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            int colon = token.indexOf(':');
            if (colon > 0) {
                if (i >= 2 && ++filters > MAX_LOOKUP_FILTERS) {
                    return "That EventTrail lookup is too large or complex. Narrow the search and try again.";
                }
                if (token.length() - colon - 1 > MAX_FILTER_VALUE_LENGTH) {
                    return "That EventTrail lookup is too large or complex. Narrow the search and try again.";
                }
            }
        }
        return null;
    }

    static int lookupComplexity(String[] tokens, Scope scope, long durationMs) {
        int cost = switch (scope) {
            case RADIUS -> 10 + radiusCost(tokens[0]);
            case WORLD -> 25;
            case GLOBAL -> 40;
        };
        cost += durationMs <= 86_400_000L ? 5
                : durationMs <= QUERY_COMPLEXITY_MEDIUM_WINDOW_MS ? 15
                : durationMs <= 90L * 86_400_000L ? 30
                : durationMs <= 365L * 86_400_000L ? 50
                : 100;

        for (int i = 2; i < tokens.length; i++) {
            String token = tokens[i].toLowerCase(Locale.ROOT);
            int colon = token.indexOf(':');
            if (colon <= 0) continue;
            String key = token.substring(0, colon);
            cost += switch (key) {
                case "block", "item", "entity", "mod" -> 15;
                case "player" -> 8;
                case "action" -> 2;
                default -> 0;
            };
        }
        return cost;
    }

    static boolean isSupportedLookupFilter(String key) {
        return key != null && SUPPORTED_LOOKUP_FILTERS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static int radiusCost(String scopeToken) {
        int colon = scopeToken.indexOf(':');
        if (colon < 0) return 0;
        try {
            int radius = Integer.parseInt(scopeToken.substring(colon + 1));
            if (radius <= 100) return 0;
            if (radius <= 500) return 5;
            if (radius <= 2_000) return 15;
            return 30;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseDuration(String value) {
        Matcher m = DURATION.matcher(value);
        if (!m.matches()) return -1L;
        try {
            long n = Long.parseLong(m.group(1));
            if (n <= 0L) return -1L;
            long unit = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> -1L;
            };
            return unit <= 0L ? -1L : Math.multiplyExact(n, unit);
        } catch (Throwable t) { return -1L; }
    }

    static List<LookupActionSelector> activeActionTypes() {
        return ACTIVE_ACTION_TYPES;
    }

    static List<String> activeActionSuggestions() {
        return ACTIVE_ACTION_SUGGESTIONS;
    }

    static LookupActionSelector parseAction(String value) {
        return LookupActionSelector.parse(value);
    }

    static ActionFilterParseResult parseActionFilter(String value) {
        if (value == null || value.isEmpty()) {
            return ActionFilterParseResult.failure("action: requires at least one action.");
        }
        String[] members = value.split(",", -1);
        if (members.length > ACTIVE_ACTION_TYPES.size()) {
            return ActionFilterParseResult.failure(
                    "action: contains too many values; choose at most "
                            + ACTIVE_ACTION_TYPES.size() + ".");
        }

        LinkedHashSet<LookupActionSelector> selected = new LinkedHashSet<>();
        for (String member : members) {
            if (member.isEmpty() || !member.equals(member.trim())) {
                return ActionFilterParseResult.failure(
                        "action: contains an empty or whitespace-separated value.");
            }
            LookupActionSelector action = parseAction(member);
            if (action == null || !ACTIVE_ACTION_TYPES.contains(action)) {
                return ActionFilterParseResult.failure("Unknown action: " + member);
            }
            selected.add(action);
        }

        List<LookupActionSelector> canonical = ACTIVE_ACTION_TYPES.stream()
                .filter(selected::contains)
                .toList();
        return ActionFilterParseResult.success(LookupActionSelector.selection(canonical));
    }

    static String actionLabel(AuditAction action) {
        return ACTION_PRESENTATIONS.label(action);
    }

    private static String canonicalActions(ActionSelection selection) {
        return LookupActionSelector.canonical(selection);
    }

    record ActionFilterParseResult(ActionSelection selection, String error) {
        static ActionFilterParseResult success(ActionSelection selection) {
            return new ActionFilterParseResult(selection, null);
        }

        static ActionFilterParseResult failure(String error) {
            return new ActionFilterParseResult(ActionSelection.unrestricted(), error);
        }

        boolean successful() {
            return error == null && selection.restricted();
        }
    }

    record LookupFilterToken(String key, String value) {}

    record OptionalFilterTokenParseResult(List<LookupFilterToken> filters, String error) {
        OptionalFilterTokenParseResult {
            filters = List.copyOf(filters);
        }

        static OptionalFilterTokenParseResult success(List<LookupFilterToken> filters) {
            return new OptionalFilterTokenParseResult(filters, null);
        }

        static OptionalFilterTokenParseResult failure(String error) {
            return new OptionalFilterTokenParseResult(List.of(), error);
        }

        boolean successful() {
            return error == null;
        }
    }

    /**
     * Expensive lookup scopes require their exact permission node when LuckPerms
     * is installed. Parent permissions do not implicitly grant these scopes.
     * Without LuckPerms, EventTrailPermissions falls back to Minecraft OP status.
     */
    private static boolean hasExactPermission(CommandSourceStack source, String node) {
        return EventTrailPermissions.hasExactPermission(source, node);
    }

    record TeleportLink(UUID playerId, String worldId, int x, int y, int z,
                        long createdAt, long expiresAt) {}
    record StorageStatus(boolean healthy, long latencyMs, StorageFailureCategory failure) {}

    enum StorageFailureCategory {
        STORAGE_NOT_READY("Storage is not ready"),
        CONNECTION_FAILED("Connection failed"),
        TIMEOUT("Health check timed out"),
        HEALTH_CHECK_UNAVAILABLE("Health check is unavailable"),
        INTERNAL_ERROR("Internal health-check error");

        private final String userReason;

        StorageFailureCategory(String userReason) {
            this.userReason = userReason;
        }
    }

    @FunctionalInterface
    interface StandingPositionPredicate {
        boolean test(BlockPos feet);
    }

    @FunctionalInterface
    interface FluidStateLookup {
        FluidState get(BlockPos position);
    }

    enum Scope { RADIUS, WORLD, GLOBAL }

    static final class Parsed {
        Scope scope;
        int radius;
        long fromTime = -1L;
        long throughTime = -1L;
        long durationMs = -1L;
        String timeLabel;
        String currentWorld;
        String world;
        String player;
        ActionSelection actionSelection = ActionSelection.unrestricted();
        /** The block:, item: and entity: values (each an exact ID or a quoted name), and the mod: IDs. */
        List<ThingFilter> blocks = List.of();
        List<ThingFilter> items = List.of();
        List<ThingFilter> entities = List.of();
        List<String> mods = List.of();
        int perPage;
        int page;
        String baseQuery;
        String error;
    }

    private record QueryResult(long total, List<AuditRecord> entries, int page, int totalPages,
                               long elapsedMs, AdvancedQuery query, ExplosionVerdicts verdicts) {}

    static record LookupSession(
            Scope scope,
            int radius,
            long durationMs,
            String timeLabel,
            String world,
            String player,
            ActionSelection actionSelection,
            String block,
            String item,
            String entity,
            int perPage,
            String baseQuery,
            AdvancedQuery query
    ) {
        /**
         * The block, item and entity components are unused since the exact filters (their values live in
         * {@code query.things()} and, as typed, in {@code baseQuery}); they stay null here.
         */
        private static LookupSession from(Parsed parsed, AdvancedQuery query) {
            return new LookupSession(
                    parsed.scope, parsed.radius, parsed.durationMs, parsed.timeLabel,
                    parsed.world, parsed.player, parsed.actionSelection, null,
                    null, null, parsed.perPage, parsed.baseQuery, query);
        }

        LookupSession withQuery(AdvancedQuery updatedQuery) {
            return new LookupSession(
                    scope, radius, durationMs, timeLabel, world, player, actionSelection,
                    block, item, entity, perPage, baseQuery, updatedQuery);
        }
    }

    static record PerPageParseResult(Integer perPage, String error) {
        boolean successful() {
            return perPage != null && error == null;
        }
    }
}
