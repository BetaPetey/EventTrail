package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "io.redspace.ironsspellbooks.network.casting.QuickCastPacket", remap = false)
abstract class IronsQuickCastPacketMixin {
    @WrapOperation(method = "lambda$handle$0", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/util/Utils;serverSideInitiateQuickCast(Lnet/minecraft/server/level/ServerPlayer;I)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static boolean eventtrail$input(ServerPlayer player, int slot, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsQuickCastPacketMixin.input", original,
                once -> IronsCastAuditBridge.input(player, once::call), player, slot);
    }
}
