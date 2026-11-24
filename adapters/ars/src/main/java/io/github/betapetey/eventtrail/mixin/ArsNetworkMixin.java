package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.common.network.AbstractPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.hollingsworth.arsnouveau.common.network.Networking", remap = false)
abstract class ArsNetworkMixin {
    @WrapOperation(method = "handleServer", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/common/network/AbstractPacket;onServerReceived(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$nativeInput(AbstractPacket packet, MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsNetworkMixin.nativeInput", original,
                once -> ArsCastAuditBridge.network(packet, player, once::call), packet, server, player);
    }
}
