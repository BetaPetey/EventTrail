package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GF-SCOPE lead ruling 1 (r1's open boat item): the one {@code Entity#move(MoverType, Vec3)} call of
 * {@code handleMoveVehicle}. A client-steered vehicle (a boat) is moved there, not in its own tick, and the move's
 * {@code tryCheckInsideBlocks} breaks a lily pad. A separate class, so the pinned call count of
 * {@link ServerGamePacketListenerImplMixin} is unchanged. The vehicle guards are
 * {@link PlayerActionScopes#openSteeredMove}'s.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerActionScopeMoveVehicleMixin {
    @Shadow
    public ServerPlayer player;

    @WrapOperation(
            method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$steeredMoveScope(Entity vehicle, MoverType type, Vec3 movement,
                                             Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openSteeredMove(this.player, vehicle, type)) {
            original.call(vehicle, type, movement);
        }
    }
}
