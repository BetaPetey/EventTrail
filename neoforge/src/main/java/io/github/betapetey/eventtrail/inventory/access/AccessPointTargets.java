package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuAccessTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Access rows B65/B66 (Owner decision 4 A, 11-14; the access-row identity rule): the one builder every family uses for
 * an access-point target ({@link MenuAccessTarget#accessPoint()}), the exact terminal block, part or carried item a
 * player opened.
 *
 * <ul>
 *   <li>A null player, a null menu or a player that is not a {@link ServerPlayer} gives empty, with no line.</li>
 *   <li>A terminal is named at its native block position, which must be loaded and on a stationary, current frame
 *   with a current location ({@code not-stationary} otherwise). Its private key is
 *   {@code <family>:access-point:<dim>@x,y,z}, plus {@code :<side>} for a part on a cable block; its digest is the
 *   row's backing reference. It holds no player id and no container id.</li>
 *   <li>A carried item (a portable cell) is named at the player's block position, with a fresh per-open key
 *   {@code <family>:portable-open:<random UUID>}.</li>
 *   <li>The technical id must be an exact namespaced registry id ({@code no-id} otherwise). The label is the game's
 *   own name; null, blank, or equal to or starting with the translation key it came from (a server that did not load
 *   the mod's language file) refuses with {@code no-name}.</li>
 *   <li>A {@link RuntimeException} in here, or in the family's resolver run through
 *   {@link #resolve(AdapterKey, String, Player, AbstractContainerMenu, Function)}, refuses with {@code read-failed}
 *   and gives empty, so it never reaches the adapter guard and never stops item tracking. A {@link LinkageError} is
 *   left to the fail-safe; the member probe ({@link AccessPointMembers}) keeps access code from meeting one.</li>
 * </ul>
 *
 * <p>Every refusal writes the OPEN text once per server start through
 * {@link AdapterFailSafe#accessRefusedOnce(AdapterKey, String, AdapterFailSafe.AccessPhase, String)} and is counted.
 * EventTrail never closes or blocks the menu.</p>
 */
public final class AccessPointTargets {
    public static final String NOT_STATIONARY = "not-stationary";
    public static final String NO_ID = "no-id";
    public static final String NO_NAME = "no-name";
    public static final String READ_FAILED = "read-failed";
    public static final String TERMINAL_REMOVED = "terminal-removed";
    public static final String MEMBER_MISSING = "member-missing";
    public static final String ACCESS_GUARD_MISSING = "access-guard-missing";
    public static final String TERMINAL_GONE = "terminal-gone";
    public static final String CELL_GONE = "cell-gone";
    public static final String SESSION_LOST = "session-lost";
    public static final String ADAPTER_OFF = "adapter-off";

    private AccessPointTargets() {
    }

    /**
     * Runs one family's access-point resolver for this open. Null inputs and non-server players give empty with no
     * line; a {@link RuntimeException} (or a null answer) refuses with {@code read-failed}.
     */
    public static Optional<MenuAccessTarget> resolve(AdapterKey key, String site, Player player,
            AbstractContainerMenu menu, Function<ServerPlayer, Optional<MenuAccessTarget>> resolver) {
        if (player == null || menu == null || resolver == null || !(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }
        try {
            Optional<MenuAccessTarget> resolved = resolver.apply(serverPlayer);
            if (resolved == null) return refuse(key, site, READ_FAILED);
            return resolved;
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** Refuses this open's access rows with {@code reason} (the OPEN text, once per server start) and gives empty. */
    public static Optional<MenuAccessTarget> refuse(AdapterKey key, String site, String reason) {
        AdapterFailSafe.accessRefusedOnce(key, site, AdapterFailSafe.AccessPhase.OPEN, reason);
        return Optional.empty();
    }

    /**
     * The access-point target of a terminal block or part at {@code pos} in {@code level}. {@code side} is the face of
     * a part on a cable block, or null for a whole block; {@code backing} is the backing kind of the same block's item
     * rows (NETWORK or PROXY).
     */
    public static Optional<MenuAccessTarget> terminal(AdapterKey key, String site, String family, Level level,
            BlockPos pos, Direction side, BackingContextKind backing, String technicalId, String label,
            String translationKey) {
        try {
            if (level == null || pos == null) return refuse(key, site, TERMINAL_REMOVED);
            if (!stationary(level, pos)) return refuse(key, site, NOT_STATIONARY);
            return terminal(key, site, family, level.dimension().location().toString(), pos.immutable(), side,
                    backing, technicalId, label, translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code terminal}, after the level checks (test seam). */
    static Optional<MenuAccessTarget> terminal(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, Direction side, BackingContextKind backing, String technicalId, String label,
            String translationKey) {
        try {
            String id = exactId(technicalId);
            if (id == null) return refuse(key, site, NO_ID);
            String name = gameName(label, translationKey);
            if (name == null) return refuse(key, site, NO_NAME);
            ContainerTarget target = new ContainerTarget(dimensionId, pos.getX(), pos.getY(), pos.getZ(), name,
                    AccessKind.WIRED_NETWORK, backing, terminalKey(family, dimensionId, pos, side));
            return Optional.of(new MenuAccessTarget(target, id, true));
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** The access-point target of a portable cell the player carries, at the player's block position. */
    public static Optional<MenuAccessTarget> portable(AdapterKey key, String site, String family, ServerPlayer player,
            String technicalId, String label, String translationKey) {
        try {
            if (player == null) return Optional.empty();
            return portable(key, site, family, player.level().dimension().location().toString(),
                    player.blockPosition(), technicalId, label, translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code portable}, after the player reads (test seam). */
    static Optional<MenuAccessTarget> portable(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, String technicalId, String label, String translationKey) {
        try {
            String id = exactId(technicalId);
            if (id == null) return refuse(key, site, NO_ID);
            String name = gameName(label, translationKey);
            if (name == null) return refuse(key, site, NO_NAME);
            ContainerTarget target = new ContainerTarget(dimensionId, pos.getX(), pos.getY(), pos.getZ(), name,
                    AccessKind.PORTABLE_MENU, BackingContextKind.ITEM_BOUND, portableKey(family));
            return Optional.of(new MenuAccessTarget(target, id, true));
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /**
     * Whether {@code pos} is loaded and on a stationary, current frame with a current location. The chunk check comes
     * first, so this never loads a chunk.
     */
    public static boolean stationary(Level level, BlockPos pos) {
        return level != null && pos != null && level.hasChunkAt(pos)
                && AuditBlockLocations.capture(level, pos)
                        .filter(frame -> !frame.moving() && frame.isCurrent())
                        .flatMap(AuditBlockLocations.Frame::currentLocation).isPresent();
    }

    /** {@code <family>:access-point:<dim>@x,y,z}, plus {@code :<side>} for a part. */
    static String terminalKey(String family, String dimensionId, BlockPos pos, Direction side) {
        String key = family + ":access-point:" + dimensionId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return side == null ? key : key + ":" + side.getSerializedName();
    }

    /** {@code <family>:portable-open:<random UUID>}: one fresh key per open. */
    static String portableKey(String family) {
        return family + ":portable-open:" + UUID.randomUUID();
    }

    /** The site of an access-point refusal: {@code adapter:<simple class name>.<part>}. */
    public static String site(String implementationClassName, String part) {
        String name = implementationClassName == null ? "unknown" : implementationClassName;
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return "adapter:" + name.substring(cut + 1) + "." + part;
    }

    /** The exact namespaced registry id, or null. */
    static String exactId(String technicalId) {
        if (technicalId == null || technicalId.isBlank()) return null;
        ResourceLocation parsed = ResourceLocation.tryParse(technicalId);
        return parsed != null && parsed.toString().equals(technicalId) ? technicalId : null;
    }

    /**
     * The game's own name, or null when it is null, blank, or equal to or starting with the translation key it came
     * from (a raw key means the server did not resolve the mod's language file).
     */
    static String gameName(String label, String translationKey) {
        if (label == null || label.isBlank()) return null;
        if (translationKey != null && !translationKey.isBlank()
                && (label.equals(translationKey) || label.startsWith(translationKey))) {
            return null;
        }
        return label;
    }
}
