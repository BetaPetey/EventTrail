package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * NeoForge 1.21.1 boundary proving canonical TNT creation inside the exact
 * serverbound use-on-block operation that targeted a TNT block. Merely storing
 * a Player owner on PrimedTnt is not sufficient human-action evidence.
 */
public final class NeoForgeTntActionAuditBridge {
    private static final ThreadLocal<PacketContext> ACTIVE_PACKET = new ThreadLocal<>();
    private static final ThreadLocal<IgnitionContext> ACTIVE_IGNITION = new ThreadLocal<>();

    private NeoForgeTntActionAuditBridge() {
    }

    public static PacketBoundary beginPacket(ServerPlayer player, BlockPos target) {
        if (!validPlayer(player) || target == null) {
            return PacketBoundary.NOOP;
        }

        PacketContext previous = ACTIVE_PACKET.get();
        if (previous != null) {
            previous.valid = false;
            return PacketBoundary.NOOP;
        }

        PacketContext context = new PacketContext(player, target.immutable());
        // N-1: the boundary is built before ACTIVE_PACKET is set, so a failure while building it leaves nothing open.
        PacketBoundary boundary = new PacketBoundary(context);
        ACTIVE_PACKET.set(context);
        return boundary;
    }

    public static IgnitionBoundary beginIgnition(
            Player player,
            Level level,
            BlockPos target,
            ItemStack usedStack) {
        PacketContext packet = ACTIVE_PACKET.get();
        if (packet == null
                || !packet.valid
                || player != packet.player
                || level != packet.player.level()
                || target == null
                || !packet.target.equals(target)
                || usedStack == null
                || (!usedStack.is(Items.FLINT_AND_STEEL)
                && !usedStack.is(Items.FIRE_CHARGE))) {
            return IgnitionBoundary.NOOP;
        }
        try {
            if (!validPlayer(packet.player)
                    || !level.getBlockState(target).is(Blocks.TNT)) {
                return IgnitionBoundary.NOOP;
            }
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return IgnitionBoundary.NOOP;
        }

        IgnitionContext previous = ACTIVE_IGNITION.get();
        if (previous != null) {
            previous.valid = false;
            return IgnitionBoundary.NOOP;
        }

        IgnitionContext context = new IgnitionContext(packet);
        // N-1: the boundary is built before ACTIVE_IGNITION is set, so a failure while building it leaves nothing open.
        IgnitionBoundary boundary = new IgnitionBoundary(context);
        ACTIVE_IGNITION.set(context);
        return boundary;
    }

    private static boolean validPlayer(ServerPlayer player) {
        try {
            return player != null
                    && player.server.isSameThread()
                    && !player.isRemoved()
                    && player.serverLevel().getEntity(player.getUUID()) == player;
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return false;
        }
    }

    public static void observeConstruction(PrimedTnt tnt, LivingEntity owner) {
        if (!(tnt instanceof TntActionEvidenceCarrier carrier)) return;
        carrier.eventtrail$setActionIgniterId(null);

        IgnitionContext context = ACTIVE_IGNITION.get();
        if (context == null || !context.valid) return;
        try {
            if (owner != context.packet.player
                    || tnt.level() != context.packet.player.level()
                    || tnt.isAddedToLevel()
                    || !context.packet.target.equals(tnt.blockPosition())) {
                return;
            }
            if (context.candidate != null) {
                context.valid = false;
                context.candidate = null;
                return;
            }
            context.candidate = tnt;
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            context.valid = false;
            context.candidate = null;
        }
    }

    public static boolean wasIgnitedByPlayerAction(PrimedTnt tnt, UUID playerId) {
        return tnt instanceof TntActionEvidenceCarrier carrier
                && playerId != null
                && playerId.equals(carrier.eventtrail$getActionIgniterId());
    }

    public static final class PacketBoundary implements AutoCloseable {
        private static final PacketBoundary NOOP = new PacketBoundary(null);
        private final PacketContext context;
        private boolean closed;

        private PacketBoundary(PacketContext context) {
            this.context = context;
        }

        @Override
        public void close() {
            if (closed || context == null) return;
            closed = true;
            PacketContext active = ACTIVE_PACKET.get();
            if (active != context) {
                if (active != null) active.valid = false;
                ACTIVE_PACKET.remove();
                context.valid = false;
                return;
            }
            context.valid = false;
            ACTIVE_PACKET.remove();
        }
    }

    public static final class IgnitionBoundary implements AutoCloseable {
        private static final IgnitionBoundary NOOP = new IgnitionBoundary(null);
        private final IgnitionContext context;
        private boolean closed;

        private IgnitionBoundary(IgnitionContext context) {
            this.context = context;
        }

        public void complete(ItemInteractionResult result) {
            if (!closeContext()) return;
            try {
                PrimedTnt candidate = context.candidate;
                if (result != null
                        && result.consumesAction()
                        && context.valid
                        && candidate != null
                        && validPlayer(context.packet.player)
                        && candidate.getType() == net.minecraft.world.entity.EntityType.TNT
                        && candidate.isAddedToLevel()
                        && !candidate.isRemoved()
                        && !candidate.level().getBlockState(context.packet.target).is(Blocks.TNT)
                        && candidate instanceof TntActionEvidenceCarrier carrier) {
                    carrier.eventtrail$setActionIgniterId(context.packet.player.getUUID());
                }
            } catch (RuntimeException | LinkageError | StackOverflowError failure) {
                // A compatibility failure leaves the candidate explicitly unmarked.
            } finally {
                context.candidate = null;
                context.valid = false;
            }
        }

        @Override
        public void close() {
            if (!closeContext()) return;
            context.candidate = null;
            context.valid = false;
        }

        private boolean closeContext() {
            if (closed || context == null) return false;
            closed = true;
            IgnitionContext active = ACTIVE_IGNITION.get();
            if (active != context) {
                if (active != null) active.valid = false;
                ACTIVE_IGNITION.remove();
                context.valid = false;
                return false;
            }
            ACTIVE_IGNITION.remove();
            return true;
        }
    }

    private static final class PacketContext {
        private final ServerPlayer player;
        private final BlockPos target;
        private boolean valid = true;

        private PacketContext(ServerPlayer player, BlockPos target) {
            this.player = player;
            this.target = target;
        }
    }

    private static final class IgnitionContext {
        private final PacketContext packet;
        private PrimedTnt candidate;
        private boolean valid = true;

        private IgnitionContext(PacketContext packet) {
            this.packet = packet;
        }
    }
}
