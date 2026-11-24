package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.function.BooleanSupplier;

/**
 * Graves design r6, 4.1 (Q185): the sneak take's frame. A grave mod's sneak mixin wraps the grave block's sneak method
 * and arms this frame around the call; when the mod's take method is entered while it is armed, the frame opens the
 * GRAVE_SNEAK scope at the grave (the only caller of {@link PlayerActionScopes#openGraveSneak}); when the sneak method
 * exits, the handle closes the scope (in the caller's {@code finally}), so the scope covers the grave's removal after
 * the take.
 *
 * <p>The frame arms only when every guard holds, each asked only when the ones before it passed: the entity is a real
 * ServerPlayer (never a FakePlayer), the call is on the server thread, the player is sneaking (a cheap filter; the mod
 * still applies its own conditions), no scope is open on the thread, and no command is running (graves design 1.7:
 * inside a command it records nothing). A tick where the mod does nothing costs one push and pop, and no scope.</p>
 *
 * <p>Presence only: it keeps the armed player, the grave's level and position while the sneak method runs, and
 * nothing after. EventTrail's own failures are caught ({@link Throwable}); the mod's call always runs.</p>
 */
public final class GraveSneakFrame {

    /** The armed frame of one sneak call; closing it closes its scope, if one opened, once. Never throws. */
    public interface Armed extends AutoCloseable {
        @Override
        void close();
    }

    /** Opens the scope for an armed frame (the test seam stands in for the live opener). */
    interface Opener {
        PlayerActionScopes.Scope open(Object player, Object level, BlockPos grave);
    }

    /** How {@link #arm} answers, in its guard order. */
    enum Decision {
        ARM,
        NOT_A_REAL_PLAYER,
        OFF_THREAD,
        NOT_SNEAKING,
        SCOPE_OPEN,
        IN_COMMAND
    }

    private static final Armed NONE = () -> { };

    private static final Opener LIVE = (player, level, grave) -> player instanceof ServerPlayer taker
            && level instanceof Level gravesLevel ? PlayerActionScopes.openGraveSneak(taker, gravesLevel, grave) : null;

    private static final ThreadLocal<Frame> ARMED = new ThreadLocal<>();

    private static final class Frame implements Armed {
        final Object player;
        final Object level;
        final BlockPos grave;
        final Opener opener;
        PlayerActionScopes.Scope scope;
        boolean closed;

        Frame(Object player, Object level, BlockPos grave, Opener opener) {
            this.player = player;
            this.level = level;
            this.grave = grave.immutable();
            this.opener = opener;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (ARMED.get() == this) ARMED.remove();
            } catch (Throwable ignored) {
                // Disarming must never reach the game.
            }
            PlayerActionScopes.Scope open = scope;
            scope = null;
            if (open == null) return;
            try {
                open.close();
            } catch (Throwable ignored) {
                // The scope's close counts its own failures; nothing reaches the game.
            }
        }
    }

    private GraveSneakFrame() {
    }

    /**
     * The sneak mixin's call, around the grave block's sneak method: close the handle in a {@code finally}. A frame
     * already armed on this thread (a nested call) keeps the outer one.
     */
    public static Armed arm(Object entity, Level level, BlockPos grave) {
        try {
            if (!(entity instanceof ServerPlayer player) || level == null || grave == null) return NONE;
            MinecraftServer server = player.server;
            return arm(decide(() -> true, () -> NeoForgePlayerProvenance.isSynthetic(player),
                    () -> server != null && server.isSameThread(), player::isShiftKeyDown,
                    PlayerActionScopes::scopeOpenOnThread, CommandPresence::inCommand), player, level, grave, LIVE);
        } catch (Throwable failure) {
            return NONE;
        }
    }

    /** Arms a frame when the decision is ARM and none is armed on this thread. */
    static Armed arm(Decision decision, Object player, Object level, BlockPos grave, Opener opener) {
        if (decision != Decision.ARM || player == null || level == null || grave == null || opener == null) return NONE;
        if (ARMED.get() != null) return NONE;
        Frame frame = new Frame(player, level, grave, opener);
        ARMED.set(frame);
        return frame;
    }

    /** The guards in order, each asked only when the ones before it passed. */
    static Decision decide(BooleanSupplier serverPlayer, BooleanSupplier synthetic, BooleanSupplier serverThread,
                           BooleanSupplier sneaking, BooleanSupplier scopeOpen, BooleanSupplier inCommand) {
        if (!serverPlayer.getAsBoolean() || synthetic.getAsBoolean()) return Decision.NOT_A_REAL_PLAYER;
        if (!serverThread.getAsBoolean()) return Decision.OFF_THREAD;
        if (!sneaking.getAsBoolean()) return Decision.NOT_SNEAKING;
        if (scopeOpen.getAsBoolean()) return Decision.SCOPE_OPEN;
        if (inCommand.getAsBoolean()) return Decision.IN_COMMAND;
        return Decision.ARM;
    }

    /**
     * The take method's call: when a frame is armed on this thread for this very player (by identity) and has not
     * opened its scope yet, it opens the GRAVE_SNEAK scope at the armed grave. Otherwise it does nothing. Never throws.
     */
    public static void takeEntered(Object taker) {
        try {
            Frame frame = ARMED.get();
            if (frame == null || frame.closed || frame.scope != null || taker == null || taker != frame.player) return;
            frame.scope = frame.opener.open(frame.player, frame.level, frame.grave);
        } catch (Throwable failure) {
            // No scope: the take then fails step 0b and writes nothing.
        }
    }

    /** Test seam: whether a frame is armed on this thread. */
    static boolean armed() {
        return ARMED.get() != null;
    }
}
