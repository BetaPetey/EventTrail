package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.sync.ContraptionInteractionPacket;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOpeningScope;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.sync.ContraptionInteractionPacket", remap = false)
public abstract class CreateInteractionPacketMixin {
    @WrapMethod(method = "handle", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$opening(ServerPlayer player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateInteractionPacketMixin.opening", original, call -> {
            var packet = (ContraptionInteractionPacket)(Object)this;
            CreateOpeningScope.packet(player, packet.target(), packet.localPos(), call::call);
        }, player);
    }
}
