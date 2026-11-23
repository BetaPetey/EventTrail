package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GF-SCOPE mask: a chunk task the main thread runs inside a player's call (a chunk loaded by the action, its
 * post-processing) suspends the observer.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
abstract class PlayerActionScopeChunkTaskMixin {
    @WrapMethod(method = "pollTask()Z", require = 1, expect = 1, allow = 1)
    private boolean eventtrail$maskChunkTask(Operation<Boolean> original) {
        boolean suspended = PlayerActionScopes.suspendForChunkTask();
        try {
            return original.call();
        } finally {
            if (suspended) PlayerActionScopes.resume();
        }
    }
}
