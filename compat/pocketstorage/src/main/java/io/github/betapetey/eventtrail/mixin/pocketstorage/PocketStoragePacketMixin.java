package io.github.betapetey.eventtrail.mixin.pocketstorage;

import com.flanks255.pocketstorage.network.SlotClickPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageOperations;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.concurrent.CompletableFuture;

/**
 * Wrap the actual queued work; enqueueing is not a committed player action.
 *
 * <p>The guard always calls the mod with the arguments it was given, so the queued work is handed to Pocket Storage's
 * {@code enqueueWork} already wrapped, as the guard's argument. The wrapper is guarded again when the server thread
 * runs it: Pocket Storage's work runs exactly once there, whatever EventTrail's observation does, and only the work
 * runs while the key is not active.</p>
 */
@Pseudo
@Mixin(targets = "com.flanks255.pocketstorage.network.SlotClickPacket", remap = false)
public abstract class PocketStoragePacketMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/handling/IPayloadContext;enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static CompletableFuture<Void> eventtrail$serverWork(IPayloadContext receiver, Runnable work,
            Operation<CompletableFuture<Void>> original, SlotClickPacket packet, IPayloadContext context) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStoragePacketMixin.serverWork", original,
                once -> once.call(), receiver, (Runnable) () -> eventtrail$queuedWork(packet, context, work));
    }

    /** Runs on the server thread: Pocket Storage's queued work runs exactly once, through the guard. */
    @Unique
    private static void eventtrail$queuedWork(SlotClickPacket packet, IPayloadContext context, Runnable work) {
        HookGuard.<Void>wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStoragePacketMixin.serverWork",
                arguments -> {
                    work.run();
                    return null;
                },
                once -> PocketStorageOperations.packet(packet, context, once::call));
    }
}
