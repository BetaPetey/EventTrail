package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/** NeoForge-owned synthetic-player classification and immutable actor capture. */
public final class NeoForgePlayerProvenance {
    /** GF-FAKEPLAYER-ID (B51): admissions refused by {@link #isAdmittedPlayer}, since this process started. */
    private static final LongAdder UNLISTED_REFUSALS = new LongAdder();

    private NeoForgePlayerProvenance() {
    }

    public static Optional<ActorProvenance> capture(
            Player player,
            ActorProvenance.Causality causality,
            String detail) {
        Objects.requireNonNull(player, "player");
        if (!isAdmittedPlayer(player)) return Optional.empty();
        return ActorProvenance.forPlayerIdentity(
                player.getUUID(),
                player.getName().getString(),
                false,
                causality,
                detail);
    }

    /** Uses NeoForge's explicit FakePlayer contract, not subclass/name heuristics. */
    public static boolean isSynthetic(Player player) {
        return player != null && isSyntheticType(player.getClass());
    }

    static boolean isSyntheticType(Class<?> playerType) {
        return playerType != null && FakePlayer.class.isAssignableFrom(playerType);
    }

    /**
     * GF-FAKEPLAYER-ID (B51): whether this player may be credited when an action or a session is admitted. A player
     * is real only when it is not a NeoForge FakePlayer and {@link #isListedPlayer} holds. A mod's ServerPlayer
     * subclass that is not a FakePlayer can carry a real player's UUID; it is not the server's own object for that
     * UUID, so it is refused here. Use it where an action or session is admitted. A commit-time check may also call it,
     * but only after an isRemoved test, as the gadget live-use check (Mining Gadgets commits), the Iron's Spells and Ars
     * Nouveau damage checks, the Iron's cast and effect checks and the part-removal finish do: PlayerList.remove and
     * PlayerList.respawn mark a real player's object removed before they delist or replace it, so isRemoved refuses
     * that object first and it is never counted. Never use it to decide whether an action admitted earlier may still be
     * recorded after its player may have left (a real player's menu can close, and a Building Gadgets placement he
     * ordered can finish, after PlayerList removal), and never for a stored identity. The Iron's Spells and Ars Nouveau
     * delayed-effect checks still do; that is TEMPORARY until the kill-credit fix (KL-211). Every refusal of a player
     * that is not a FakePlayer is counted ({@link #unlistedPlayerRefusals}).
     */
    public static boolean isAdmittedPlayer(Player player) {
        if (player == null || isSynthetic(player)) return false;
        if (player instanceof ServerPlayer serverPlayer && isListedPlayer(serverPlayer)) return true;
        UNLISTED_REFUSALS.increment();
        return false;
    }

    /**
     * GF-FAKEPLAYER-ID (B51): the server's PlayerList holds exactly this object under its UUID, read on the server
     * thread. With no server, no player list, off the server thread or on any failure the answer is false (not proven).
     */
    public static boolean isListedPlayer(ServerPlayer player) {
        try {
            if (player == null) return false;
            MinecraftServer server = player.server;
            if (server == null || !server.isSameThread()) return false;
            PlayerList players = server.getPlayerList();
            return players != null && players.getPlayer(player.getUUID()) == player;
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    /**
     * GF-FAKEPLAYER-ID (review-01 M3): the object the server's PlayerList holds under this player's UUID, when that is
     * a different object, read on the server thread. Null when the list holds this object or nothing under the UUID,
     * and when that is not provable (no server, no player list, off the server thread or any failure). It admits
     * nothing and is never counted.
     */
    public static ServerPlayer otherListedPlayer(ServerPlayer player) {
        try {
            if (player == null) return null;
            MinecraftServer server = player.server;
            if (server == null || !server.isSameThread()) return null;
            PlayerList players = server.getPlayerList();
            if (players == null) return null;
            ServerPlayer listed = players.getPlayer(player.getUUID());
            return listed == player ? null : listed;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * Player checks refused because the player was not a FakePlayer but was not the PlayerList's own object (or that
     * was not provable), since this process started. One per refused check, not per action: one action can be refused
     * at more than one check, so it is an upper bound on the actions.
     */
    public static long unlistedPlayerRefusals() {
        return UNLISTED_REFUSALS.sum();
    }
}
