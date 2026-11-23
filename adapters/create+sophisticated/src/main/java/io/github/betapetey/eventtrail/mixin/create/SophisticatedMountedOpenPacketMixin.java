package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOpeningScope;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedstoragecreateintegration.storage.OpenMountedStorageInventoryPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedstoragecreateintegration.storage.OpenMountedStorageInventoryPayload", remap = false)
public abstract class SophisticatedMountedOpenPacketMixin {
    @WrapMethod(method = "handlePayload", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$opening(OpenMountedStorageInventoryPayload packet, IPayloadContext context, Operation<Void> original) {
        // Both paths call the mod through the one guarded handle, so the payload runs exactly once on each.
        HookGuard.wrap(AdapterKey.SOPHISTICATED_STORAGE_CREATE, "hook:SophisticatedMountedOpenPacketMixin.opening",
                original, call -> {
                    if (context.player() instanceof ServerPlayer player) {
                        CreateOpeningScope.packet(player, packet.contraptionEntityId(), packet.localPos(), call::call);
                    } else call.call();
                }, packet, context);
    }
}
