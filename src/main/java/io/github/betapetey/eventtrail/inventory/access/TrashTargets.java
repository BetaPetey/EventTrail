package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuAccessTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * GF-TRASHTARGET (Question 15, ruling U12): the one target builder for a mod's trash or void slot. Recognising a trash
 * screen, slot or event stays with the adapter that calls this; this class names no mod class.
 *
 * <ul>
 *   <li>Every trash target carries {@link BackingContextKind#TRASH} and the trash's own technical id, and is located
 *   at its access point, because every row needs a location: the trash block's position ({@link #perBlock}), or the
 *   player's block position for a per-player, per-opened-bin or per-terminal-host trash, as
 *   {@link AccessPointTargets#portable} does. It is never a block's container at that position.</li>
 *   <li>The private key names the trash: {@code <family>:trash:player:<player UUID>} (one per player, so two players'
 *   trash never share a target), {@code <family>:trash:opened:<random UUID>} (one per opened bin),
 *   {@code <family>:trash:host:<host key>} (one per terminal host) or {@code <family>:trash:block:<dim>@x,y,z}. Only
 *   its digest is stored.</li>
 *   <li>The label is the game's own name for the trash; a per-player trash adds {@code " of <player>"}, so lookups show
 *   it as that player's trash.</li>
 *   <li>A missing player, host, block or family, a missing id, a missing or untranslated name, a trash block off a
 *   stationary current frame, or a failed read gives empty and reports {@link AdapterFailSafe#invalid}: the trash move
 *   is not recorded, and it is counted. EventTrail never blocks the mod's trash.</li>
 *   <li>A null adapter key is a caller's programming error: the public factories throw, so the refusal is never
 *   uncounted.</li>
 * </ul>
 */
public final class TrashTargets {
    public static final String NO_PLAYER = "trash-no-player";
    public static final String NO_HOST = "trash-no-host";
    public static final String NO_BLOCK = "trash-no-block";
    public static final String NO_FAMILY = "trash-no-family";
    public static final String NO_ID = "trash-no-id";
    public static final String NO_NAME = "trash-no-name";
    public static final String NOT_STATIONARY = "trash-not-stationary";
    public static final String READ_FAILED = "trash-read-failed";

    /** What one trash target stands for. */
    public enum Scope {
        /** One trash per player (for example a trash slot in the player's own screen). */
        PLAYER,
        /** One trash per opened bin (a fresh trash screen each time it is opened). */
        OPENED_BIN,
        /** One trash per terminal host (a trash kept by the host of a carried terminal's screen). */
        TERMINAL_HOST,
        /** One trash per trash block. */
        BLOCK
    }

    /** A trash target: the target its rows carry, the trash's own technical id, and what it stands for. */
    public record TrashTarget(ContainerTarget target, String technicalTargetId, Scope scope) {
        public TrashTarget {
            target = Objects.requireNonNull(target, "target");
            scope = Objects.requireNonNull(scope, "scope");
            if (target.backingContextKind() != BackingContextKind.TRASH || !target.hasAccessPosition()) {
                throw new IllegalArgumentException("A trash target is a TRASH target at its access point");
            }
            if (AccessPointTargets.exactId(technicalTargetId) == null) {
                throw new IllegalArgumentException("technicalTargetId must be an exact namespaced registry identifier");
            }
        }

        /** The access-point target for this trash's open and close rows. */
        public MenuAccessTarget accessTarget() {
            return new MenuAccessTarget(target, technicalTargetId, true);
        }
    }

    private TrashTargets() {
    }

    /** One trash per player, at the player's block position; the key holds the player's UUID. */
    public static Optional<TrashTarget> perPlayer(AdapterKey key, String site, String family, ServerPlayer player,
            String technicalId, String label, String translationKey) {
        Objects.requireNonNull(key, "key");
        try {
            if (player == null) return refuse(key, site, NO_PLAYER);
            return perPlayer(key, site, family, dimension(player), player.blockPosition(), player.getUUID(),
                    player.getGameProfile().getName(), technicalId, label, translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code perPlayer}, after the player reads (test seam). */
    static Optional<TrashTarget> perPlayer(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, UUID playerId, String playerName, String technicalId, String label, String translationKey) {
        if (playerId == null || playerName == null || playerName.isBlank()) return refuse(key, site, NO_PLAYER);
        return build(key, site, family, Scope.PLAYER, dimensionId, pos, AccessKind.PORTABLE_MENU,
                "player:" + playerId, technicalId, label, translationKey, playerName);
    }

    /** One trash per opened bin, at the player's block position, with a fresh key per open. */
    public static Optional<TrashTarget> perOpenedBin(AdapterKey key, String site, String family, ServerPlayer player,
            String technicalId, String label, String translationKey) {
        Objects.requireNonNull(key, "key");
        try {
            if (player == null) return refuse(key, site, NO_PLAYER);
            return perOpenedBin(key, site, family, dimension(player), player.blockPosition(), technicalId, label,
                    translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code perOpenedBin}, after the player reads (test seam). */
    static Optional<TrashTarget> perOpenedBin(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, String technicalId, String label, String translationKey) {
        return build(key, site, family, Scope.OPENED_BIN, dimensionId, pos, AccessKind.PORTABLE_MENU,
                "opened:" + UUID.randomUUID(), technicalId, label, translationKey, null);
    }

    /**
     * One trash per terminal host, at the player's block position. {@code hostKey} is the adapter's stable, non-secret
     * identity of the trash store itself, not of a network, terminal block or item two players can share: it must
     * include the player's UUID when the store is per player, and an adapter must use {@link #perOpenedBin} instead
     * when the store does not survive the screen. Otherwise two players' trash rows would share one target.
     */
    public static Optional<TrashTarget> perTerminalHost(AdapterKey key, String site, String family,
            ServerPlayer player, String hostKey, String technicalId, String label, String translationKey) {
        Objects.requireNonNull(key, "key");
        try {
            if (player == null) return refuse(key, site, NO_PLAYER);
            return perTerminalHost(key, site, family, dimension(player), player.blockPosition(), hostKey, technicalId,
                    label, translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code perTerminalHost}, after the player reads (test seam). */
    static Optional<TrashTarget> perTerminalHost(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, String hostKey, String technicalId, String label, String translationKey) {
        if (hostKey == null || hostKey.isBlank()) return refuse(key, site, NO_HOST);
        return build(key, site, family, Scope.TERMINAL_HOST, dimensionId, pos, AccessKind.PORTABLE_MENU,
                "host:" + hostKey, technicalId, label, translationKey, null);
    }

    /** One trash per trash block, at its position, which must be loaded and on a stationary, current frame. */
    public static Optional<TrashTarget> perBlock(AdapterKey key, String site, String family, Level level,
            BlockPos pos, String technicalId, String label, String translationKey) {
        Objects.requireNonNull(key, "key");
        try {
            if (level == null || pos == null) return refuse(key, site, NO_BLOCK);
            if (!AccessPointTargets.stationary(level, pos)) return refuse(key, site, NOT_STATIONARY);
            return perBlock(key, site, family, level.dimension().location().toString(), pos.immutable(),
                    technicalId, label, translationKey);
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    /** As the public {@code perBlock}, after the level checks (test seam). */
    static Optional<TrashTarget> perBlock(AdapterKey key, String site, String family, String dimensionId,
            BlockPos pos, String technicalId, String label, String translationKey) {
        if (pos == null) return refuse(key, site, NO_BLOCK);
        return build(key, site, family, Scope.BLOCK, dimensionId, pos, AccessKind.PHYSICAL,
                "block:" + dimensionId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                technicalId, label, translationKey, null);
    }

    private static Optional<TrashTarget> build(AdapterKey key, String site, String family, Scope scope,
            String dimensionId, BlockPos pos, AccessKind accessKind, String keySuffix, String technicalId,
            String label, String translationKey, String ownerName) {
        try {
            if (family == null || family.isBlank()) return refuse(key, site, NO_FAMILY);
            if (pos == null) return refuse(key, site, scope == Scope.BLOCK ? NO_BLOCK : NO_PLAYER);
            String id = AccessPointTargets.exactId(technicalId);
            if (id == null) return refuse(key, site, NO_ID);
            String name = AccessPointTargets.gameName(label, translationKey);
            if (name == null) return refuse(key, site, NO_NAME);
            String shown = ownerName == null ? name : name + " of " + ownerName;
            ContainerTarget target = new ContainerTarget(dimensionId, pos.getX(), pos.getY(), pos.getZ(), shown,
                    accessKind, BackingContextKind.TRASH, family + ":trash:" + keySuffix);
            return Optional.of(new TrashTarget(target, id, scope));
        } catch (RuntimeException failure) {
            return refuse(key, site, READ_FAILED);
        }
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    /** Reports {@code reason} (the trash move is not recorded; counted) and gives empty. */
    private static Optional<TrashTarget> refuse(AdapterKey key, String site, String reason) {
        AdapterFailSafe.invalid(key, site, reason);
        return Optional.empty();
    }
}
