package io.github.betapetey.eventtrail.permissions;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * EventTrail's NeoForge permission nodes. The names are exactly the strings
 * EventTrail has always checked, so existing LuckPerms grants keep working:
 * each node is built as {@code ("command", "eventtrail...")}, which NeoForge
 * joins to {@code command.eventtrail...}.
 *
 * <p>The default resolver is what a permission handler answers for a node it
 * has no value for. It returns today's operator answer only when the selected
 * source lets unset nodes fall back to the operator check; under LuckPerms it
 * returns false, so an unset node stays "no". Before a source is selected, or
 * if anything fails, it returns false.</p>
 */
final class EventTrailPermissionNodes {
    static final String NAMESPACE = "command";
    static final String ROOT_PERMISSION = "command.eventtrail";

    /** Every node EventTrail checks, with today's names. */
    static final List<String> NAMES = List.of(
            ROOT_PERMISSION,
            "command.eventtrail.inspect",
            "command.eventtrail.lookup",
            "command.eventtrail.lookup.extended",
            "command.eventtrail.lookup.global",
            "command.eventtrail.lookup.world",
            "command.eventtrail.page",
            "command.eventtrail.purge",
            "command.eventtrail.status",
            "command.eventtrail.teleport");

    private static final Map<String, PermissionNode<Boolean>> NODES = createNodes();

    private EventTrailPermissionNodes() {}

    /** The registered node for {@code name}, or null for a name EventTrail does not register. */
    static PermissionNode<Boolean> node(String name) {
        return name == null ? null : NODES.get(name);
    }

    /** All nodes, in a stable order, for {@code PermissionGatherEvent.Nodes}. */
    static List<PermissionNode<?>> all() {
        return List.copyOf(NODES.values());
    }

    /**
     * The default resolver's decision.
     *
     * @param selection      the current server run's selection, or null before one is made
     * @param playerPresent  an online player was passed (offline queries are denied)
     * @param realOperator   today's operator answer for that player
     */
    static boolean defaultAnswer(
            EventTrailPermissionSelection.Selection selection, boolean playerPresent, BooleanSupplier realOperator) {
        try {
            if (selection == null || !selection.unsetFallsBackToOperator() || !playerPresent) return false;
            return realOperator != null && realOperator.getAsBoolean();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static Boolean resolveDefault(ServerPlayer player, UUID playerId, PermissionDynamicContext<?>... context) {
        return defaultAnswer(EventTrailPermissions.currentSelection(), player != null,
                () -> EventTrailOpFallback.isRealPlayerOperator(player));
    }

    private static Map<String, PermissionNode<Boolean>> createNodes() {
        Map<String, PermissionNode<Boolean>> nodes = new LinkedHashMap<>();
        String prefix = NAMESPACE + ".";
        for (String name : NAMES) {
            if (!name.startsWith(prefix)) throw new IllegalStateException("Unexpected EventTrail node " + name);
            nodes.put(name, new PermissionNode<>(NAMESPACE, name.substring(prefix.length()),
                    PermissionTypes.BOOLEAN, EventTrailPermissionNodes::resolveDefault));
        }
        return Collections.unmodifiableMap(nodes);
    }
}
