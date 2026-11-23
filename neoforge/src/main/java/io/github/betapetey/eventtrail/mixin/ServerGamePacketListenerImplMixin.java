package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime;
import io.github.betapetey.eventtrail.events.parts.PartRemovalAuditBridge;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.platform.NeoForgeItemDropAuditBridge;
import io.github.betapetey.eventtrail.events.platform.NeoForgeProjectileActionAuditBridge;
import io.github.betapetey.eventtrail.events.platform.NeoForgeTntActionAuditBridge;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Brackets authoritative ordinary-menu and explicit player-discard inputs. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @WrapMethod(
            method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeProjectileUseAction(
            ServerboundUseItemPacket packet,
            Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openUse(this.player, packet);
             NeoForgeProjectileActionAuditBridge.Boundary ignored =
                     NeoForgeProjectileActionAuditBridge.begin(this.player);
             GadgetAuditRuntime.InputBoundary gadget = GadgetAuditRuntime.beginInput(this.player)) {
            original.call(packet);
            gadget.success();
        }
    }

    @WrapMethod(
            method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeTntUseAction(
            ServerboundUseItemOnPacket packet,
            Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openUseOn(this.player, packet);
             NeoForgeTntActionAuditBridge.PacketBoundary ignored =
                     NeoForgeTntActionAuditBridge.beginPacket(
                             this.player,
                             packet.getHitResult().getBlockPos());
             GadgetAuditRuntime.InputBoundary gadget = GadgetAuditRuntime.beginInput(this.player);
             PartRemovalAuditBridge.UseBoundary parts = PartRemovalAuditBridge.beginUse(
                     this.player, packet.getHand(), packet.getHitResult().getBlockPos())) {
            if (parts.opened()) PlayerActionScopes.ownClickedForBridge(); // the part-removal readers' position
            original.call(packet);
            gadget.success();
            parts.success();
        }
    }

    @WrapMethod(
            method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeProjectileReleaseAction(
            ServerboundPlayerActionPacket packet,
            Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openPlayerAction(this.player, packet)) {
            if (packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                original.call(packet);
                return;
            }
            try (NeoForgeProjectileActionAuditBridge.Boundary ignored =
                         NeoForgeProjectileActionAuditBridge.begin(this.player)) {
                original.call(packet);
            }
        }
    }

    @WrapOperation(
            method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;drop(Z)Z"),
            require = 2,
            expect = 2,
            allow = 2)
    private boolean eventtrail$observeExplicitKeyDrop(
            ServerPlayer player,
            boolean dropAll,
            Operation<Boolean> original) {
        NeoForgeItemDropAuditBridge.Boundary boundary =
                NeoForgeItemDropAuditBridge.beginKeyDiscard(player, dropAll);
        boolean completedNormally = false;
        boolean discarded = false;
        try {
            discarded = original.call(player, dropAll);
            completedNormally = true;
            return discarded;
        } finally {
            if (completedNormally && discarded) {
                NeoForgeItemDropAuditBridge.finish(boundary);
            } else {
                NeoForgeItemDropAuditBridge.abort(boundary);
            }
        }
    }

    @WrapOperation(
            method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked"
                            + "(IILnet/minecraft/world/inventory/ClickType;"
                            + "Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeContainerAction(
            AbstractContainerMenu menu,
            int slot,
            int button,
            ClickType clickType,
            Player player,
            Operation<Void> original) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            original.call(menu, slot, button, clickType, player);
            return;
        }

        UniversalContainerTracker.ActionToken token =
                UniversalContainerTracker.beforeMenuAction(
                        serverPlayer, menu, slot, clickType);
        // P-1: the drop boundary begins inside the try, so a failure while it begins still ends the token
        // (abort accepts the null boundary that such a failure leaves). Both handlers catch every throwable, an
        // undeclared checked one from mod code included, and rethrow it unchanged. The token ends first, because
        // menuActionFailed never throws and a failing abort must not leave the token open.
        NeoForgeItemDropAuditBridge.Boundary dropBoundary = null;
        try {
            dropBoundary = NeoForgeItemDropAuditBridge.beginMenuDiscard(
                    serverPlayer, menu, slot, button, clickType);
            original.call(menu, slot, button, clickType, player);
        } catch (Throwable failure) {
            UniversalContainerTracker.menuActionFailed(token);
            NeoForgeItemDropAuditBridge.abort(dropBoundary);
            throw failure;
        }
        // P-1: the click has run. If finishing the drop fails, the token still ends and the action is not completed.
        try {
            NeoForgeItemDropAuditBridge.finish(dropBoundary);
        } catch (Throwable failure) {
            UniversalContainerTracker.menuActionFailed(token);
            throw failure;
        }
        UniversalContainerTracker.afterSuccessfulMenuAction(serverPlayer, menu, token);
    }

    @WrapOperation(
            method = "handleSetCreativeModeSlot("
                    + "Lnet/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;drop("
                            + "Lnet/minecraft/world/item/ItemStack;Z)"
                            + "Lnet/minecraft/world/entity/item/ItemEntity;"),
            require = 1,
            expect = 1,
            allow = 1)
    private ItemEntity eventtrail$observeExplicitCreativeDrop(
            ServerPlayer player,
            ItemStack stack,
            boolean includeThrowerName,
            Operation<ItemEntity> original) {
        NeoForgeItemDropAuditBridge.Boundary boundary =
                NeoForgeItemDropAuditBridge.beginCreativeDiscard(player, stack);
        boolean completedNormally = false;
        try {
            ItemEntity result = original.call(player, stack, includeThrowerName);
            completedNormally = true;
            return result;
        } finally {
            if (completedNormally) {
                NeoForgeItemDropAuditBridge.finish(boundary);
            } else {
                NeoForgeItemDropAuditBridge.abort(boundary);
            }
        }
    }
}
