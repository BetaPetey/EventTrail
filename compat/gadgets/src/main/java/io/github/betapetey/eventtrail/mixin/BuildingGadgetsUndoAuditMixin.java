package io.github.betapetey.eventtrail.mixin;

import com.direwolf20.buildinggadgets2.common.network.handler.PacketUndo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;

/** The enqueued server work, rather than initial packet scheduling, owns undo input. */
@Mixin(value = PacketUndo.class, remap = false)
abstract class BuildingGadgetsUndoAuditMixin {
    @WrapMethod(method = "lambda$handle$0", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$observeHumanUndo(IPayloadContext context, Operation<Void> original) {
        // Two exclusive paths in one guarded body; each calls the mod once (work map section 1 item 3).
        // The input boundary is closed in a finally after the mod's call, not by try-with-resources, so a
        // close() failure never becomes a suppressed exception of the mod's own exception: the guard
        // reports it as EventTrail's failure and re-throws the mod's exception unchanged (HookGuard class note).
        HookGuard.wrap(AdapterKey.BUILDING_GADGETS, "hook:BuildingGadgetsUndoAuditMixin.observeHumanUndo", original,
                once -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        once.call();
                        return;
                    }
                    GadgetAuditRuntime.InputBoundary boundary = GadgetAuditRuntime.beginInput(player);
                    try {
                        once.call();
                        boundary.success();
                    } finally {
                        boundary.close();
                    }
                }, context);
    }
}
