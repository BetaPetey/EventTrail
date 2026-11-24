package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "io.redspace.ironsspellbooks.network.casting.CastPacket", remap = false)
abstract class IronsCastPacketMixin {
    @WrapOperation(method = "lambda$handle$0", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/util/Utils;serverSideInitiateCast(Lnet/minecraft/server/level/ServerPlayer;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static boolean eventtrail$input(ServerPlayer player, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsCastPacketMixin.input", original,
                once -> IronsCastAuditBridge.input(player, once::call), player);
    }
}
