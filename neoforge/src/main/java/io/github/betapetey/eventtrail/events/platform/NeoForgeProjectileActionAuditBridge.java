package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.UUID;

/**
 * NeoForge 1.21.1 boundary proving that projectile ownership was assigned
 * synchronously inside a real ServerGamePacketListenerImpl use/release action.
 * No candidate outlives that packet call; the retained projectile marker is a
 * single UUID and is cleared whenever ownership changes without that proof.
 */
public final class NeoForgeProjectileActionAuditBridge {
    private static final ThreadLocal<ActionContext> ACTIVE_ACTION = new ThreadLocal<>();

    private NeoForgeProjectileActionAuditBridge() {
    }

    public static Boundary begin(ServerPlayer player) {
        if (player == null
                || !player.server.isSameThread()
                || player.isRemoved()
                || player.serverLevel().getEntity(player.getUUID()) != player) {
            return Boundary.NOOP;
        }

        ActionContext previous = ACTIVE_ACTION.get();
        if (previous != null) {
            // Nested packet handling is not one unambiguous player operation.
            previous.valid = false;
            return Boundary.NOOP;
        }

        ActionContext context = new ActionContext(player);
        // N-1: the boundary is built before ACTIVE_ACTION is set, so a failure while building it leaves nothing open.
        Boundary boundary = new Boundary(context);
        ACTIVE_ACTION.set(context);
        return boundary;
    }

    public static void observeOwnerAssignment(Projectile projectile, Entity owner) {
        if (!(projectile instanceof ProjectileActionEvidenceCarrier carrier)) return;

        UUID launcherId = null;
        ActionContext context = ACTIVE_ACTION.get();
        if (context != null
                && context.valid
                && owner == context.player
                && context.player.server.isSameThread()
                && !context.player.isRemoved()
                && projectile.level() == context.player.level()
                && !projectile.isAddedToLevel()) {
            launcherId = context.player.getUUID();
        }
        // Any unproven reassignment invalidates earlier action evidence.
        carrier.eventtrail$setActionLauncherId(launcherId);
    }

    public static boolean wasLaunchedByPlayerAction(Entity projectile, UUID playerId) {
        // Optional spell projectiles require their own accepted-cast and exact impact proof.
        if (io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime.isIronEntity(projectile)) return false;
        if (io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime.isArsEntity(projectile)) return false;
        return projectile instanceof ProjectileActionEvidenceCarrier carrier
                && playerId != null
                && playerId.equals(carrier.eventtrail$getActionLauncherId());
    }

    public static final class Boundary implements AutoCloseable {
        private static final Boundary NOOP = new Boundary(null);
        private final ActionContext context;
        private boolean closed;

        private Boundary(ActionContext context) {
            this.context = context;
        }

        @Override
        public void close() {
            if (closed || context == null) return;
            closed = true;
            ActionContext active = ACTIVE_ACTION.get();
            if (active != context) {
                if (active != null) active.valid = false;
                ACTIVE_ACTION.remove();
                context.valid = false;
                return;
            }
            context.valid = false;
            ACTIVE_ACTION.remove();
        }
    }

    private static final class ActionContext {
        private final ServerPlayer player;
        private boolean valid = true;

        private ActionContext(ServerPlayer player) {
            this.player = player;
        }
    }
}
