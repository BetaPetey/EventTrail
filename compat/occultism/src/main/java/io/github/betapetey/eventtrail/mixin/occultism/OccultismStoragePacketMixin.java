package io.github.betapetey.eventtrail.mixin.occultism;

import com.klikli_dev.occultism.network.messages.MessageInsertMouseHeldItem;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOperations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {"com.klikli_dev.occultism.network.messages.MessageInsertMouseHeldItem",
        "com.klikli_dev.occultism.network.messages.MessageTakeItem"}, remap = false)
public abstract class OccultismStoragePacketMixin {
    @WrapMethod(method = "onServerReceived", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$operation(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStoragePacketMixin.operation", original,
                once -> OccultismStorageOperations.packet(player, (Object) this instanceof MessageInsertMouseHeldItem,
                        once::call), server, player);
    }
}
