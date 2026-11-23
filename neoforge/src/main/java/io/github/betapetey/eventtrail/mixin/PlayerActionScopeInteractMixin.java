package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * GF-SCOPE: the entity-interact scope (the new handleInteract wrap the plan names). A separate class, so the pinned
 * {@code original.call(} count of {@link ServerGamePacketListenerImplMixin} is unchanged. No kill credit: a kill or
 * death row is never written by the scope (Owner, One Killer).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerActionScopeInteractMixin {
    @Shadow
    public ServerPlayer player;

    @WrapMethod(
            method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$interactScope(ServerboundInteractPacket packet, Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openInteract(this.player, packet)) {
            original.call(packet);
        }
    }
}
