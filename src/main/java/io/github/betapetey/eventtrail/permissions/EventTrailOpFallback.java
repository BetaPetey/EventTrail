package io.github.betapetey.eventtrail.permissions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseCommandBlock;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Classifies command sources and applies EventTrail's operator check (used when no permission mod decides). */
public final class EventTrailOpFallback {
    private EventTrailOpFallback() {}

    static SourceKind classify(CommandSourceStack source) {
        if (source == null) return SourceKind.UNKNOWN;

        Object origin = source.source;
        Entity entity = source.getEntity();
        return classify(new SourceFacts(
                true,
                origin instanceof BaseCommandBlock,
                origin instanceof Entity && !(origin instanceof ServerPlayer),
                origin instanceof FakePlayer || entity instanceof FakePlayer,
                entity instanceof ServerPlayer,
                entity != null && !(entity instanceof ServerPlayer),
                source.isSilent(),
                origin instanceof RconConsoleSource,
                origin instanceof MinecraftServer));
    }

    static SourceKind classify(SourceFacts facts) {
        if (facts == null || !facts.stackPresent()) return SourceKind.UNKNOWN;

        // Preserve the originating trust boundary. A command block or other
        // entity must not become trusted merely by changing the execution entity.
        if (facts.commandBlockSource()) return SourceKind.COMMAND_BLOCK;
        if (facts.nonPlayerEntitySource()) return SourceKind.OTHER_ENTITY;

        // NeoForge FakePlayer extends ServerPlayer, so this must precede the
        // generic player branch. Synthetic actors never gain player command trust.
        if (facts.fakePlayerEntity()) return SourceKind.FAKE_PLAYER;

        // A server/RCON function may deliberately execute as a real player. In
        // that case the final player identity uses the normal player policy.
        if (facts.playerEntity()) return SourceKind.PLAYER;
        if (facts.nonPlayerEntity()) return SourceKind.OTHER_ENTITY;

        // In 1.21.1, both directly invoked and scheduled functions suppress
        // output before their commands execute. Check this before console/RCON.
        if (facts.silent()) return SourceKind.FUNCTION;
        if (facts.rconSource()) return SourceKind.RCON;
        if (facts.serverSource()) return SourceKind.SERVER_CONSOLE;
        return SourceKind.UNKNOWN;
    }

    static boolean allowsOperatorFallback(CommandSourceStack source) {
        SourceKind kind = classify(source);
        if (kind == SourceKind.SERVER_CONSOLE || kind == SourceKind.RCON) return true;
        if (kind != SourceKind.PLAYER) return false;

        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().getPlayerList().isOp(player.getGameProfile());
    }

    /**
     * Today's operator answer for an online player who is not a fake player.
     * Used by EventTrail's NeoForge default resolver; false on any doubt.
     */
    static boolean isRealPlayerOperator(ServerPlayer player) {
        if (player == null || player instanceof FakePlayer) return false;
        MinecraftServer server = player.getServer();
        return server != null && server.getPlayerList().isOp(player.getGameProfile());
    }

    static boolean allowsOperatorFallback(SourceKind kind, boolean playerOperator) {
        return switch (kind) {
            case PLAYER -> playerOperator;
            case SERVER_CONSOLE, RCON -> true;
            case FAKE_PLAYER, COMMAND_BLOCK, FUNCTION, OTHER_ENTITY, UNKNOWN -> false;
        };
    }

    enum SourceKind {
        PLAYER,
        FAKE_PLAYER,
        SERVER_CONSOLE,
        RCON,
        COMMAND_BLOCK,
        FUNCTION,
        OTHER_ENTITY,
        UNKNOWN
    }

    record SourceFacts(
            boolean stackPresent,
            boolean commandBlockSource,
            boolean nonPlayerEntitySource,
            boolean fakePlayerEntity,
            boolean playerEntity,
            boolean nonPlayerEntity,
            boolean silent,
            boolean rconSource,
            boolean serverSource) {
    }
}
