package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.NeoForgeTntActionAuditBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Commits TNT action evidence only after canonical use-on-block succeeds. */
@Mixin(TntBlock.class)
abstract class TntBlockActionEvidenceMixin {
    @WrapMethod(
            method = "useItemOn(Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;"
                    + "Lnet/minecraft/world/phys/BlockHitResult;)"
                    + "Lnet/minecraft/world/ItemInteractionResult;",
            require = 1,
            expect = 1,
            allow = 1)
    private ItemInteractionResult eventtrail$recordCommittedTntIgnition(
            ItemStack usedStack,
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            InteractionHand hand,
            BlockHitResult hit,
            Operation<ItemInteractionResult> original) {
        NeoForgeTntActionAuditBridge.IgnitionBoundary boundary =
                NeoForgeTntActionAuditBridge.beginIgnition(
                        player, level, position, usedStack);
        ItemInteractionResult result = null;
        boolean completedNormally = false;
        try {
            result = original.call(usedStack, state, level, position, player, hand, hit);
            completedNormally = true;
            return result;
        } finally {
            if (completedNormally) {
                boundary.complete(result);
            } else {
                boundary.close();
            }
        }
    }
}
