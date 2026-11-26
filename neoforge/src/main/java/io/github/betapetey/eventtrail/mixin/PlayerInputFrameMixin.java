package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.PlayerInputFrames;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Item 2 F4 option A, the Owner's Q427 A: marks "this real player's own click or key press is being handled" for the
 * synchronous extent of each input handler below, so the direct-player counter counts only credits outside it. A
 * separate class, so the pinned shapes of ServerGamePacketListenerImplMixin and PlayerActionScopeInteractMixin are
 * unchanged. It only counts: no credit, row or scope changes. The frame holds the player only during the call;
 * nothing is stored on any entity.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerInputFrameMixin {
    @Shadow
    public ServerPlayer player;

    @WrapMethod(
            method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameInteract(ServerboundInteractPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameUseItemOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameUseItem(ServerboundUseItemPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$framePlayerAction(ServerboundPlayerActionPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameContainerClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handlePlayerCommand(Lnet/minecraft/network/protocol/game/ServerboundPlayerCommandPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$framePlayerCommand(ServerboundPlayerCommandPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }

    @WrapMethod(
            method = "handleAnimate(Lnet/minecraft/network/protocol/game/ServerboundSwingPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameSwing(ServerboundSwingPacket packet, Operation<Void> original) {
        PlayerInputFrames.Frame frame = PlayerInputFrames.frame(this.player);
        try {
            frame.open();
            original.call(packet);
        } finally {
            frame.close();
        }
    }
}
