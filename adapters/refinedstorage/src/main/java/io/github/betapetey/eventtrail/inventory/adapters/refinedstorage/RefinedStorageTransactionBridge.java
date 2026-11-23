package io.github.betapetey.eventtrail.inventory.adapters.refinedstorage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Correlates Refined Storage's exact committed transfer with its enclosing
 * server-side player menu action. The thread-local never owns network state.
 */
public final class RefinedStorageTransactionBridge {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private RefinedStorageTransactionBridge() {
    }

    /** Begins a custom RS packet action and owns the EventTrail action token. */
    public static Scope beginAuthoritative(
            ServerPlayer player, AbstractContainerMenu menu) {
        return begin(player, menu, true);
    }

    /** Binds the actor for a vanilla shift-click already bracketed by EventTrail. */
    public static Scope bindExisting(
            ServerPlayer player, AbstractContainerMenu menu) {
        return begin(player, menu, false);
    }

    private static Scope begin(
            ServerPlayer player, AbstractContainerMenu menu, boolean ownsTrackerToken) {
        if (player == null
                || menu == null
                || player.containerMenu != menu
                || ACTIVE.get() != null) {
            return null;
        }
        UniversalContainerTracker.ActionToken token = ownsTrackerToken
                ? UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu)
                : null;
        Scope scope = new Scope(player, menu, token, ownsTrackerToken);
        ACTIVE.set(scope);
        return scope;
    }

    public static void complete(Scope scope) {
        if (scope == null) return;
        if (ACTIVE.get() != scope) {
            fail(scope);
            return;
        }
        ACTIVE.remove();
        if (scope.ownsTrackerToken && scope.token != null) {
            UniversalContainerTracker.afterSuccessfulMenuAction(
                    scope.player, scope.menu, scope.token);
        }
    }

    public static void fail(Scope scope) {
        if (scope == null) return;
        if (ACTIVE.get() == scope) ACTIVE.remove();
        if (scope.ownsTrackerToken && scope.token != null) {
            UniversalContainerTracker.menuActionFailed(scope.token);
        }
    }

    /** Records only a positive item transfer attributed to the scoped player. */
    public static void recordCommittedTransfer(
            ResourceKey resource,
            Actor actor,
            long committedQuantity,
            TransactionDirection direction) {
        Scope scope = ACTIVE.get();
        if (scope == null
                || committedQuantity <= 0L
                || direction == null
                || !(resource instanceof ItemResource itemResource)
                || !actorMatchesPlayer(actor, scope.player.getGameProfile().getName())) {
            return;
        }
        ItemStack stack = itemResource.toItemStack(1L);
        if (stack.isEmpty()) return;
        UniversalContainerTracker.recordAuthoritativeTransfer(
                scope.player, stack, direction, committedQuantity);
    }

    static boolean actorMatchesPlayer(Actor actor, String expectedPlayerName) {
        return actor instanceof PlayerActor playerActor
                && expectedPlayerName != null
                && expectedPlayerName.equals(playerActor.name());
    }

    public static final class Scope {
        private final ServerPlayer player;
        private final AbstractContainerMenu menu;
        private final UniversalContainerTracker.ActionToken token;
        private final boolean ownsTrackerToken;

        private Scope(
                ServerPlayer player,
                AbstractContainerMenu menu,
                UniversalContainerTracker.ActionToken token,
                boolean ownsTrackerToken) {
            this.player = player;
            this.menu = menu;
            this.token = token;
            this.ownsTrackerToken = ownsTrackerToken;
        }
    }
}
