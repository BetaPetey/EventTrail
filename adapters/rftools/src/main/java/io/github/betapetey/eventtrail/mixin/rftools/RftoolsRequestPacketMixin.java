package io.github.betapetey.eventtrail.mixin.rftools;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsScannerOperations;
import mcjty.rftoolsstorage.modules.scanner.network.PacketRequestItem;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.concurrent.CompletableFuture;

/**
 * The guard always calls the mod with the arguments it was given, so the queued work is handed to RFTools Storage's
 * {@code enqueueWork} already wrapped, as the guard's argument. The wrapper is guarded again when the server thread
 * runs it: the scanner request runs exactly once there, whatever EventTrail's observation does, and only the request
 * runs while the key is not active.
 */
@Pseudo
@Mixin(targets = "mcjty.rftoolsstorage.modules.scanner.network.PacketRequestItem", remap = false)
public abstract class RftoolsRequestPacketMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/network/handling/IPayloadContext;enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CompletableFuture<Void> eventtrail$requestWork(IPayloadContext receiver, Runnable work,
            Operation<CompletableFuture<Void>> original, IPayloadContext context) {
        return HookGuard.wrap(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsRequestPacketMixin.requestWork", original,
                once -> once.call(), receiver, (Runnable)() -> eventtrail$queuedWork(this, context, work));
    }

    /**
     * Runs on the server thread: the mod's queued request runs exactly once, through the guard. Static, with the packet
     * as {@code Object}, so the packet cast stays inside the guarded body and a test can call it with a real packet.
     */
    @Unique
    private static void eventtrail$queuedWork(Object packet, IPayloadContext context, Runnable work) {
        HookGuard.<Void>wrap(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsRequestPacketMixin.requestWork",
                arguments -> {
                    work.run();
                    return null;
                },
                once -> RftoolsScannerOperations.packet((PacketRequestItem) packet, context, once::call));
    }
}
