package io.github.betapetey.eventtrail.mixin.occultism;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOpening;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.network.messages.MessageOpenStorageRemote", remap = false)
public abstract class OccultismStorageRemotePacketMixin {
    @WrapMethod(method = "onServerReceived", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$open(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageRemotePacketMixin.open", original,
                once -> OccultismStorageOpening.remote(player, once::call), server, player);
    }
}
