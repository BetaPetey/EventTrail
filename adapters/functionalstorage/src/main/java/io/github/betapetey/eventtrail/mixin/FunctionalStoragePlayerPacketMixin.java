package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.functionalstorage.FunctionalStorageDirectActions;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Installed only for the exact supported optional pair; no absent-mod packet work. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class FunctionalStoragePlayerPacketMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$drawerUse(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStoragePlayerPacketMixin.drawerUse", original,
                once -> {
                    var scope = FunctionalStorageDirectActions.beginPacket(player, packet.getHitResult().getBlockPos(),
                            packet.getHand(), TransactionDirection.DEPOSIT);
                    boolean completed = false;
                    try {
                        once.call();
                        completed = true;
                    } finally {
                        FunctionalStorageDirectActions.finishPacket(scope, completed);
                    }
                }, packet);
    }

    /**
     * One guard call for both paths (work map R2-hook-ii, one of the six files with more call sites than handlers):
     * a packet that is not a drawer dig only runs the mod's call; a drawer dig runs it inside the drawer scope.
     */
    @WrapMethod(method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$drawerDig(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStoragePlayerPacketMixin.drawerDig", original,
                once -> {
                    if (!FunctionalStorageDirectActions.isDrawerDig(packet.getAction())) {
                        once.call();
                        return;
                    }
                    var scope = FunctionalStorageDirectActions.beginPacket(player, packet.getPos(),
                            InteractionHand.MAIN_HAND, TransactionDirection.WITHDRAWAL);
                    boolean completed = false;
                    try {
                        once.call();
                        completed = true;
                    } finally {
                        FunctionalStorageDirectActions.finishPacket(scope, completed);
                    }
                }, packet);
    }
}
