package io.github.betapetey.eventtrail.mixin;

import appeng.helpers.InventoryAction;
import appeng.menu.me.common.MEStorageMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Brackets AE2's custom terminal packet action when AE2 is installed. The
 * terminal action runs exactly once through {@link HookGuard}, whatever
 * EventTrail's bracketing does.
 */
@Pseudo
@Mixin(targets = "appeng.menu.me.common.MEStorageMenu", remap = false)
abstract class Ae2MEStorageMenuMixin {
    @WrapMethod(
            method = "handleInteraction(JLappeng/helpers/InventoryAction;)V",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$observeAe2TerminalAction(
            long serial,
            InventoryAction action,
            Operation<Void> original) {
        HookGuard.wrap(AdapterKey.AE2, "hook:Ae2MEStorageMenuMixin.observeAe2TerminalAction", original, once -> {
            // getPlayer() is declared on AEBaseMenu rather than the concrete target;
            // call the exact public API instead of shadowing an inherited method.
            Player actor = ((MEStorageMenu) (Object) this).getPlayer();
            if (!(actor instanceof ServerPlayer player)) {
                once.call();
                return;
            }

            AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
            UniversalContainerTracker.ActionToken token =
                    UniversalContainerTracker.beforeAuthoritativeMenuAction(player, menu);
            try {
                once.call();
            } catch (Throwable failure) {
                // Every throwable, including an undeclared checked one (a sneaky throw), ends the token.
                UniversalContainerTracker.menuActionFailed(token);
                throw failure;
            }
            UniversalContainerTracker.afterSuccessfulMenuAction(player, menu, token);
        }, serial, action);
    }
}
