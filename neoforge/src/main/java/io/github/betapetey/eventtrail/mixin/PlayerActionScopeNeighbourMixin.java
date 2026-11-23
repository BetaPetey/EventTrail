package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GF-SCOPE mask: a neighbour-changed callback of any block other than the clicked one suspends the observer (a lever
 * reaching TNT, a mod machine reacting to a redstone change). Shape updates are never masked: a survival break
 * removes a door's or bed's partner half through one (lead ruling p2gf-5 #2).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class PlayerActionScopeNeighbourMixin {
    @WrapMethod(
            method = "handleNeighborChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$maskNeighbourUpdate(
            Level level,
            BlockPos position,
            Block neighbour,
            BlockPos from,
            boolean movedByPiston,
            Operation<Void> original) {
        boolean suspended = PlayerActionScopes.suspendForNeighbourUpdate(level, position);
        try {
            original.call(level, position, neighbour, from, movedByPiston);
        } finally {
            if (suspended) PlayerActionScopes.resume();
        }
    }
}
