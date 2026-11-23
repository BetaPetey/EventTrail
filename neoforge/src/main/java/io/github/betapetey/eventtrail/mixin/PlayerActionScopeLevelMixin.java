package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GF-SCOPE: the committed block-change observer. At the head of every {@code Level#setBlock} the first unmasked
 * preimage of the position is handed to the player-action scope open on this thread; with no scope open anywhere
 * the hook returns at once.
 */
@Mixin(Level.class)
abstract class PlayerActionScopeLevelMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeSetBlock(
            BlockPos position,
            BlockState state,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callback) {
        PlayerActionScopes.beforeSetBlock((Level) (Object) this, position, state);
    }
}
