package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Item 2 F4 option A, the Owner's Q427 A: "Count outside the player's own click or key press, with the status line
 * "outside that player's own click or key press"." Which real player's own click or key press is being handled on the
 * server thread, the innermost one, for the synchronous extent of that packet's handler (PlayerInputFrameMixin: an
 * entity click or attack, a block or item use, a block hit, release, drop or hand swap, a menu click, a player command
 * key, an arm swing).
 *
 * <p>{@link #noteCredit} is the status counter of DIRECT_PLAYER kill credits given outside that player's own click or
 * key press, the figure the Owner asked for before the B decision. It only counts; the credit is kept. A spell cast or
 * right-click ability inside the player's own use packet is inside the click; a credit in a later tick, in another
 * player's packet, or from a mod key sent as a custom payload is outside it.</p>
 *
 * <p>The frame holds the player only while the handler runs, and only on the server thread (a handler first called on
 * the network thread re-queues itself there); {@link Frame#close} always restores the frame it replaced, even after a
 * failed {@code set} (the KL-60 rule). It only counts: it never grants or withholds credit and changes no row. Nothing
 * is thrown into the game short of a {@link VirtualMachineError}.</p>
 */
public final class PlayerInputFrames {
    /** The innermost real player's own input handled on this thread; {@code null} outside every one. */
    private static final ThreadLocal<ServerPlayer> INNERMOST = new ThreadLocal<>();

    private static final Frame NONE = new Frame(null, null);

    private PlayerInputFrames() {
    }

    /**
     * One input's frame, made before the handler and opened inside its {@code try}; {@link #close} in the
     * {@code finally}. A frame for anyone but a real player on the server thread does nothing.
     */
    public static final class Frame {
        private final ServerPlayer player;
        private final ServerPlayer previous;

        private Frame(ServerPlayer player, ServerPlayer previous) {
            this.player = player;
            this.previous = previous;
        }

        /** Makes this player's input the innermost one. */
        public void open() {
            if (player == null) return;
            try {
                INNERMOST.set(player);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // A frame that could not open counts every credit as outside; close still restores the previous one.
            }
        }

        /** Restores the frame this one replaced, whether or not {@link #open} completed. */
        public void close() {
            if (player == null) return;
            try {
                if (previous == null) {
                    INNERMOST.remove();
                } else {
                    INNERMOST.set(previous);
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Closing never interrupts the handler's own result or exception.
            }
        }
    }

    /**
     * The frame for {@code player}'s own input: a real player (not a FakePlayer) whose handler runs on the server
     * thread. Never throws.
     */
    public static Frame frame(ServerPlayer player) {
        try {
            if (player == null) return NONE;
            MinecraftServer server = player.server;
            if (server == null || !server.isSameThread()) return NONE;
            if (!FakePlayerAttackGuard.isRealPlayer(player)) return NONE;
            return new Frame(player, INNERMOST.get());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    /** The innermost real player's own input on this thread, or {@code null}. Package-private for the tests. */
    static ServerPlayer innermost() {
        return INNERMOST.get();
    }

    /**
     * For capture(), once the death's attribution is final: a DIRECT_PLAYER credit to a real player made outside that
     * player's own click or key press is counted (option B would drop it). The credit is not changed. Never throws.
     */
    static void noteCredit(EntityKillAttribution attribution) {
        try {
            if (attribution == null
                    || attribution.provenance() != EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER) {
                return;
            }
            ActorProvenance player = attribution.accountablePlayer();
            if (player == null || player.actorKind() != ActorProvenance.ActorKind.PLAYER) return;
            ServerPlayer input = INNERMOST.get();
            if (input != null && player.playerId() != null && player.playerId().equals(input.getUUID())) return;
            OwnActionCreditGuard.countDirectPlayerOutsideOwnInput();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Measuring never changes the death row.
        }
    }
}
