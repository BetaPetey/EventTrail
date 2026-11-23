package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

/**
 * Two actual manual Core packet boundaries, shared by independently admitted concrete menus.
 *
 * <p>An observation failure leaves the packet unobserved (the mod's call still runs exactly once) and goes to the
 * fail-safe under the key of the two payload hooks, {@link AdapterKey#SOPHISTICATED_BACKPACKS} (lead decision L16
 * H2): a missing member switches that key off until restart; any other error skips only this packet.</p>
 */
public final class BackpackMenuActions {
    private BackpackMenuActions() {}

    public static void bulk(Player actor, boolean toPlayer, Runnable original) {
        int slot = -1;
        boolean observable = true;
        try {
            if (eligible(actor) && actor.containerMenu instanceof StorageContainerMenuBase<?> menu) {
                if (menu.slots.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) {
                    observable = false;
                } else {
                    InventorySlotRole role = toPlayer ? InventorySlotRole.STORAGE : InventorySlotRole.PLAYER;
                    // Representative role for this explicitly directional bulk command,
                    // not backing-owner discovery. The complete descriptor is still mandatory.
                    for (int i = 0; i < menu.slots.size(); i++) {
                        if (SophisticatedMenuSlots.classify(menu, actor.getInventory(), menu.slots.get(i)) == role) {
                            slot = i;
                            break;
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError invalidObservation) {
            observable = false;
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:BackpackMenuActions.bulk", invalidObservation);
        }
        if (!observable) { original.run(); return; }
        run(actor, slot, original);
    }

    public static void run(Player actor, int slot, Runnable original) {
        ServerPlayer player = actor instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        StorageContainerMenuBase<?> menu = null;
        UniversalContainerTracker.ActionToken token = null;
        try {
            if (player != null && eligible(actor) && player.level() != null
                    && player.containerMenu instanceof StorageContainerMenuBase<?> exactMenu
                    && player.getServer() != null && player.getServer().isSameThread()) {
                menu = exactMenu;
                token = UniversalContainerTracker.beforeMenuAction(player, menu, slot, ClickType.QUICK_MOVE);
            }
        } catch (RuntimeException | LinkageError observationFailure) {
            // A broken observer must not consume, retry, or replace the gameplay call.
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:BackpackMenuActions.run", observationFailure);
        }
        if (token == null) {
            original.run();
            return;
        }
        try {
            original.run();
        } catch (Throwable failure) {
            // Every throwable, including an undeclared checked one (a sneaky throw), ends the token: an open token
            // would leave this physical session's escrow to resolve at close.
            UniversalContainerTracker.menuActionFailed(token);
            throw failure;
        }
        UniversalContainerTracker.afterSuccessfulMenuAction(player, menu, token);
    }

    private static boolean eligible(Player actor) {
        if (actor == null || actor.containerMenu == null) return false;
        // Namespace checks only select this native packet bracket. Actual version,
        // actor, owner, role and committed custody still require the existing session.
        return OptionalMenuAdapterLoader.isNonStationarySophisticatedMenu(actor.containerMenu.getClass());
    }
}
