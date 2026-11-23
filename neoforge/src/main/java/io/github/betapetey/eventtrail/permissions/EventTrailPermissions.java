package io.github.betapetey.eventtrail.permissions;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Permission bridge for EventTrail commands.
 *
 * <p>EventTrail registers its nodes with NeoForge's permission API, with the
 * same names it has always checked. At each server start exactly one source is
 * chosen to decide player permissions (see {@link EventTrailPermissionSelection}).
 * The server setting {@code permissions.source} picks it; its default,
 * {@code auto}, uses this fixed order: LuckPerms (or another permission mod
 * that is NeoForge's active handler), then FTB Ranks, then the operator check.
 * A setting that names a source which is not installed uses the operator check
 * with a warning, never another permission mod. One INFO line at server start
 * names the chosen source and whether the setting or auto chose it. A decision
 * never consults a second source.</p>
 *
 * <p>When LuckPerms decides, it is authoritative for player permissions: being
 * a Minecraft operator does not grant EventTrail access on its own, an unset
 * node is "no", and parent EventTrail permission nodes do not implicitly grant
 * child commands. Each EventTrail command/capability checks the permission node
 * assigned to that action. Under another NeoForge handler, that handler
 * decides; a node it has no value for falls back to the operator check through
 * EventTrail's default resolver, if the handler answers such nodes with the
 * node's default. Under FTB Ranks, a value a rank sets for exactly that node
 * decides and any other node uses the operator check. Without any of them,
 * EventTrail uses Minecraft's operator permissions so the mod remains usable
 * on servers without a permissions plugin. Non-player fallback is limited to
 * positively identified server-console and RCON sources, whichever source is
 * chosen.</p>
 *
 * <p>Before a source is chosen for the server run, player checks fail closed.
 * A LuckPerms or NeoForge-handler query that fails is denied. An FTB Ranks
 * query that fails uses the operator check for that check; if the failure
 * shows a missing member that the bridge uses (sorted by the adapter
 * {@code FailureClassifier}) or a wrong result shape, the FTB Ranks bridge
 * switches off and the operator check applies until the next server start.</p>
 */
public final class EventTrailPermissions {
    public static final String STATUS_PERMISSION = "command.eventtrail.status";
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final String[] ROOT_NODES = {
            "command.eventtrail.inspect",
            "command.eventtrail.lookup",
            "command.eventtrail.status",
            "command.eventtrail.page"
    };
    private static final String LUCKPERMS_PROVIDER = "net.luckperms.api.LuckPermsProvider";

    private static volatile boolean warnedLuckPermsReflection;
    private static volatile boolean warnedNeoForgeQuery;
    private static volatile boolean nodesRegistered;
    /** This server run's selection and FTB Ranks bridge, published together; null before selection. */
    private static volatile Activation activation;

    private EventTrailPermissions() {}

    /** Controls visibility/access to the root /eventtrail command. */
    public static boolean hasRootPermission(CommandSourceStack source) {
        Activation current = activation;
        return decideRoot(EventTrailOpFallback.classify(source), selectionOf(current),
                () -> playerQueries(source, current));
    }

    /**
     * Requires the requested permission node. Parent EventTrail nodes do not
     * implicitly grant it. LuckPerms wildcards still work because LuckPerms
     * resolves the exact query; without a permission mod, the operator check
     * applies.
     */
    public static boolean hasExactPermission(CommandSourceStack source, String node) {
        Activation current = activation;
        return decideExact(EventTrailOpFallback.classify(source), selectionOf(current),
                () -> playerQueries(source, current), node);
    }

    /**
     * Non-player sources keep today's rules whatever source is chosen (console
     * and RCON allowed; fake players, command blocks, functions, other entities
     * and unknown sources denied) and never reach a permission source.
     */
    static boolean decideExact(
            EventTrailOpFallback.SourceKind kind,
            EventTrailPermissionSelection.Selection chosen,
            Supplier<PlayerQueries> queries,
            String node) {
        if (kind != EventTrailOpFallback.SourceKind.PLAYER) {
            return EventTrailOpFallback.allowsOperatorFallback(kind, false);
        }
        return decideExact(chosen, queries == null ? null : queries.get(), node);
    }

    /** As {@link #decideExact(EventTrailOpFallback.SourceKind, EventTrailPermissionSelection.Selection, Supplier, String)} for the root. */
    static boolean decideRoot(
            EventTrailOpFallback.SourceKind kind,
            EventTrailPermissionSelection.Selection chosen,
            Supplier<PlayerQueries> queries) {
        if (kind != EventTrailOpFallback.SourceKind.PLAYER) {
            return EventTrailOpFallback.allowsOperatorFallback(kind, false);
        }
        return decideRoot(chosen, queries == null ? null : queries.get());
    }

    /** Registers EventTrail's nodes with NeoForge ({@code PermissionGatherEvent.Nodes}, game bus). */
    public static void onGatherPermissionNodes(PermissionGatherEvent.Nodes event) {
        try {
            event.addNodes(EventTrailPermissionNodes.all());
            nodesRegistered = true;
        } catch (Throwable failure) {
            nodesRegistered = false;
            LOGGER.warn("EventTrail permission nodes could not be registered with NeoForge ({}); a NeoForge "
                            + "permission handler other than LuckPerms will not be used, and EventTrail uses the "
                            + "operator check instead of it.",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    /**
     * Chooses this server run's permission source from the
     * {@code permissions.source} setting (read with the rest of the server
     * config at start-up) and writes the one startup line. Called at server
     * start, after NeoForge has chosen its handler. Never throws; if choosing
     * fails, player checks stay fail-closed.
     */
    public static void selectForServerStart() {
        warnedNeoForgeQuery = false;
        try {
            ClassLoader loader = EventTrailPermissions.class.getClassLoader();
            activation = activate(EventTrailConfig.permissionsSourceSetting(), isLuckPermsPresent(),
                    activeHandlerId(), nodesRegistered, name -> Class.forName(name, false, loader),
                    line -> LOGGER.info("{}", line), line -> LOGGER.warn("{}", line));
        } catch (Throwable failure) {
            activation = null;
            LOGGER.warn("EventTrail could not choose its permission source ({}); player permission checks are "
                    + "denied until the next server start.", EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    /** Forgets the server run's choice; player checks fail closed until the next start. */
    public static void clearSelection() {
        activation = null;
        nodesRegistered = false;
        warnedNeoForgeQuery = false;
    }

    static EventTrailPermissionSelection.Selection currentSelection() {
        return selectionOf(activation);
    }

    private static EventTrailPermissionSelection.Selection selectionOf(Activation current) {
        return current == null ? null : current.selection();
    }

    /** The chosen selection and the FTB Ranks bridge it uses (absent unless FTB Ranks was chosen). */
    record Activation(EventTrailPermissionSelection.Selection selection, FtbRanksPermissionBridge ftbRanksBridge) {}

    /** {@link #activate(String, boolean, String, boolean, FtbRanksPermissionBridge.ClassLookup, Consumer, Consumer)} with the setting absent (auto). */
    static Activation activate(
            boolean luckPermsPresent,
            String activeHandlerId,
            boolean registered,
            FtbRanksPermissionBridge.ClassLookup classes,
            Consumer<String> info,
            Consumer<String> warn) {
        return activate(null, luckPermsPresent, activeHandlerId, registered, classes, info, warn);
    }

    /**
     * Applies the {@code permissions.source} setting. FTB Ranks' bridge is
     * resolved only when FTB Ranks would be chosen; when it does not resolve,
     * the operator check is chosen and the bridge has written its one warning.
     * Writes exactly one INFO line, plus one WARN when the setting is unknown or
     * names a source that cannot be used (or a handler cannot answer).
     *
     * @param sourceSetting the raw {@code permissions.source} value, or null when the key is absent
     */
    static Activation activate(
            String sourceSetting,
            boolean luckPermsPresent,
            String activeHandlerId,
            boolean registered,
            FtbRanksPermissionBridge.ClassLookup classes,
            Consumer<String> info,
            Consumer<String> warn) {
        EventTrailPermissionSelection.Choice choice = EventTrailPermissionSelection.Choice.parse(sourceSetting);
        boolean ftbRanksInstalled = classes != null && FtbRanksPermissionBridge.installed(classes);
        FtbRanksPermissionBridge bridge = FtbRanksPermissionBridge.absent();
        boolean bridgeReady = false;
        if (EventTrailPermissionSelection.wouldChooseFtbRanks(
                choice, luckPermsPresent, activeHandlerId, ftbRanksInstalled)) {
            bridge = FtbRanksPermissionBridge.resolve(classes, warn);
            bridgeReady = bridge.ready();
        }
        EventTrailPermissionSelection.Selection chosen = EventTrailPermissionSelection.select(
                choice, luckPermsPresent, activeHandlerId, registered, ftbRanksInstalled, bridgeReady);
        if (chosen.source() != EventTrailPermissionSelection.Source.FTB_RANKS) {
            bridge = FtbRanksPermissionBridge.absent();
        }
        info.accept(chosen.startupLine());
        String warning = chosen.warning();
        if (warning != null) warn.accept(warning);
        return new Activation(chosen, bridge);
    }

    /** What one player decision may ask; only the chosen source's method is called. */
    interface PlayerQueries {
        /** LuckPerms' own answer, asked directly. */
        Result luckPerms(String node);

        /** NeoForge's permission API answer; may throw (for example for an unregistered node). */
        Boolean neoForge(String node) throws Exception;

        /** FTB Ranks' value for exactly this node; UNDEFINED when it has none, the query failed or its bridge is off. */
        Result ftbRanks(String node);

        /** Today's operator answer for this player. */
        boolean operator();
    }

    /** The exact-node decision for a real player under {@code chosen}. */
    static boolean decideExact(EventTrailPermissionSelection.Selection chosen, PlayerQueries queries, String node) {
        if (chosen == null || queries == null || node == null) return false;
        return switch (chosen.source()) {
            case LUCKPERMS -> chosen.viaNeoForgeApi()
                    ? neoForgeAnswer(queries, node)
                    : evaluateExactPermission(EventTrailOpFallback.SourceKind.PLAYER,
                            queries.luckPerms(node), true, false);
            case NEOFORGE_HANDLER -> neoForgeAnswer(queries, node);
            case FTB_RANKS -> {
                Result value = queries.ftbRanks(node);
                yield value == Result.UNDEFINED ? queries.operator() : value == Result.TRUE;
            }
            case OPERATOR -> evaluateExactPermission(EventTrailOpFallback.SourceKind.PLAYER,
                    Result.UNDEFINED, false, queries.operator());
        };
    }

    /** The root is visible when the root node or any public command node is granted. */
    static boolean decideRoot(EventTrailPermissionSelection.Selection chosen, PlayerQueries queries) {
        if (decideExact(chosen, queries, EventTrailPermissionNodes.ROOT_PERMISSION)) return true;
        return Arrays.stream(ROOT_NODES).anyMatch(node -> decideExact(chosen, queries, node));
    }

    static boolean evaluateRootPermission(
            EventTrailOpFallback.SourceKind kind,
            Result root,
            List<Result> commandResults,
            boolean luckPermsPresent,
            boolean playerOperator) {
        if (kind != EventTrailOpFallback.SourceKind.PLAYER) {
            return EventTrailOpFallback.allowsOperatorFallback(kind, false);
        }
        if (!luckPermsPresent) return playerOperator;
        if (root == Result.TRUE) return true;
        return commandResults.stream().anyMatch(result -> result == Result.TRUE);
    }

    static boolean evaluateExactPermission(
            EventTrailOpFallback.SourceKind kind,
            Result result,
            boolean luckPermsPresent,
            boolean playerOperator) {
        if (kind != EventTrailOpFallback.SourceKind.PLAYER) {
            return EventTrailOpFallback.allowsOperatorFallback(kind, false);
        }
        return luckPermsPresent ? result == Result.TRUE : playerOperator;
    }

    private static boolean neoForgeAnswer(PlayerQueries queries, String node) {
        try {
            return Boolean.TRUE.equals(queries.neoForge(node));
        } catch (Throwable failure) {
            if (!warnedNeoForgeQuery) {
                warnedNeoForgeQuery = true;
                LOGGER.warn("NeoForge permission handler could not answer an EventTrail permission query; that "
                        + "check is denied ({}).", EventTrailDiagnostics.safeFailureSummary(failure));
            }
            return false;
        }
    }

    private static PlayerQueries playerQueries(CommandSourceStack source, Activation current) {
        ServerPlayer player = source.getPlayer();
        FtbRanksPermissionBridge ftbRanksBridge = current == null
                ? FtbRanksPermissionBridge.absent()
                : current.ftbRanksBridge();
        return new PlayerQueries() {
            @Override
            public Result luckPerms(String node) {
                return luckPermsResult(player, node);
            }

            @Override
            public Boolean neoForge(String node) {
                PermissionNode<Boolean> registered = EventTrailPermissionNodes.node(node);
                if (player == null || registered == null) return Boolean.FALSE;
                return PermissionAPI.getPermission(player, registered);
            }

            @Override
            public Result ftbRanks(String node) {
                return player == null ? Result.UNDEFINED : ftbRanksBridge.query(player, node);
            }

            @Override
            public boolean operator() {
                return EventTrailOpFallback.allowsOperatorFallback(source);
            }
        };
    }

    private static String activeHandlerId() {
        try {
            ResourceLocation handler = PermissionAPI.getActivePermissionHandler();
            return handler == null ? null : handler.toString();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Query the exact LuckPerms node for a real player. */
    private static Result luckPermsResult(ServerPlayer player, String node) {
        if (player == null) return Result.UNDEFINED;

        try {
            ClassLoader loader = EventTrailPermissions.class.getClassLoader();
            Class<?> providerClass = Class.forName(LUCKPERMS_PROVIDER, false, loader);
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);

            UUID uuid = player.getUUID();
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            if (getUser == null) return Result.UNDEFINED;

            Object user = getUser.invoke(userManager, uuid);
            if (user == null) return Result.UNDEFINED;

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            Object tristate = permissionData.getClass().getMethod("checkPermission", String.class)
                    .invoke(permissionData, node);
            if (tristate == null) return Result.UNDEFINED;

            String value = tristate.toString().toUpperCase(Locale.ROOT);
            if (value.endsWith("TRUE")) return Result.TRUE;
            if (value.endsWith("FALSE")) return Result.FALSE;
            return Result.UNDEFINED;
        } catch (ClassNotFoundException noLuckPerms) {
            return Result.UNDEFINED;
        } catch (Throwable t) {
            if (!warnedLuckPermsReflection) {
                warnedLuckPermsReflection = true;
                LOGGER.warn(
                        "LuckPerms permission bridge could not be queried; EventTrail player permissions will fail " +
                                "closed while LuckPerms is present ({}).",
                        EventTrailDiagnostics.safeFailureSummary(t));
            }
            return Result.UNDEFINED;
        }
    }

    private static boolean isLuckPermsPresent() {
        try {
            Class.forName(LUCKPERMS_PROVIDER, false, EventTrailPermissions.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException noLuckPerms) {
            return false;
        } catch (Throwable linkageProblem) {
            // The API is at least present enough to resolve its class name. Fail
            // closed rather than allowing OP to bypass a broken LP bridge.
            return true;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> parameter) {
        try {
            return type.getMethod(name, parameter);
        } catch (NoSuchMethodException ignored) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1) return method;
            }
            return null;
        }
    }

    enum Result {
        TRUE,
        FALSE,
        UNDEFINED
    }
}
