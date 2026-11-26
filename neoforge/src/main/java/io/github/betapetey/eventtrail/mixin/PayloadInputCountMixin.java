package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * D-674 (KL-168, ct-01 check-1 M1): a mod's custom payload is one of its sender's inputs. NeoForge runs a payload's
 * main-thread handler, and the main-thread work of a network-thread handler, through the server payload context's
 * {@code enqueueWork}; both overloads hand their task through
 * {@link UniversalContainerTracker#countPayloadInputFirst}, which counts the sender's input on the server thread right
 * before the task runs. Nothing else changes: the task still runs, now or queued, exactly as NeoForge decides. A
 * network-thread handler that moves its work to the server thread another way is not counted (the known residual).
 */
@Mixin(ServerPayloadContext.class)
abstract class PayloadInputCountMixin {
    @WrapMethod(method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", require = 1,
            expect = 1, allow = 1)
    private CompletableFuture<Void> eventtrail$countPayloadInput(Runnable task,
            Operation<CompletableFuture<Void>> original) {
        return original.call(UniversalContainerTracker.countPayloadInputFirst(
                ((ServerPayloadContext) (Object) this).listener(), task));
    }

    @WrapMethod(method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            require = 1, expect = 1, allow = 1)
    private <T> CompletableFuture<T> eventtrail$countPayloadValueInput(Supplier<T> task,
            Operation<CompletableFuture<T>> original) {
        return original.call(UniversalContainerTracker.countPayloadInputFirst(
                ((ServerPayloadContext) (Object) this).listener(), task));
    }
}
