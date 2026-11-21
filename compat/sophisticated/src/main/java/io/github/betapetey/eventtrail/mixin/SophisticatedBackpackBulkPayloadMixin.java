package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.BackpackMenuActions;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedcore.network.TransferItemsPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.network.TransferItemsPayload", remap = false)
public abstract class SophisticatedBackpackBulkPayloadMixin {
    @WrapMethod(method = "handlePayload", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$manualBulk(TransferItemsPayload packet, IPayloadContext context, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.SOPHISTICATED_BACKPACKS, "hook:SophisticatedBackpackBulkPayloadMixin.manualBulk", original,
                call -> BackpackMenuActions.bulk(context.player(), packet.transferToInventory(), call::call),
                packet, context);
    }
}
