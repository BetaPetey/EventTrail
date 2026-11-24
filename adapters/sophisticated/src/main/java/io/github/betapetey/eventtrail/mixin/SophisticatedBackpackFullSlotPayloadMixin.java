package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.BackpackMenuActions;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.network.TransferFullSlotPayload", remap = false)
public abstract class SophisticatedBackpackFullSlotPayloadMixin {
    @WrapMethod(method = "handlePayload", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$manualFullSlot(TransferFullSlotPayload packet, IPayloadContext context, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.SOPHISTICATED_BACKPACKS, "hook:SophisticatedBackpackFullSlotPayloadMixin.manualFullSlot", original,
                call -> BackpackMenuActions.run(context.player(), packet.slotId(), call::call),
                packet, context);
    }
}
