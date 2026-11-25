package io.github.betapetey.eventtrail.mixin.gravestone;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.gravestone.GraveStoneGraves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gravestone Mod's grave block (graves design r6, 2.1 hooks 1 to 3). Each handler hands the mod's call to the guard
 * once; the work is in {@link GraveStoneGraves}.
 *
 * <ol>
 *   <li>The take on a break: around the grave block's whole player-break method, which hands the grave over to the
 *   player ({@code sortItems}) and then removes the block. The hook names only the game's own types; the grave is read
 *   from the block entity at the position, by its block-entity type. The sneak's take is the sneak hook's (L-u8).</li>
 *   <li>The spill: each drop-helper call in the mod's drop loop ({@code dropItems}), during a hand-over (the
 *   leftovers) and at a removal (all that was left).</li>
 *   <li>The removal: around the mod's removal handler, which drops all that is left in the grave.</li>
 * </ol>
 *
 * <p>No hook is on the mod's restore command: it never reaches the grave block.</p>
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.gravestone.blocks.GraveStoneBlock", remap = false)
abstract class GraveStoneBlockMixin {
    @WrapMethod(method = "onDestroyedByPlayer(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/level/material/FluidState;)Z",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$take(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                    FluidState fluid, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.GRAVESTONE, "hook:GraveStoneBlockMixin.take", original,
                once -> GraveStoneGraves.take(level, pos, player, once), state, level, pos, player, willHarvest, fluid);
    }

    @WrapOperation(method = "dropItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V",
                    remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$spill(Level level, double x, double y, double z, ItemStack stack,
                                  Operation<Void> original) {
        HookGuard.wrap(AdapterKey.GRAVESTONE, "hook:GraveStoneBlockMixin.spill", original,
                once -> GraveStoneGraves.spill(stack, once::call), level, x, y, z, stack);
    }

    @WrapMethod(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$removal(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving,
                                    Operation<Void> original) {
        HookGuard.wrap(AdapterKey.GRAVESTONE, "hook:GraveStoneBlockMixin.removal", original,
                once -> GraveStoneGraves.removal(state, level, pos, newState, once::call),
                state, level, pos, newState, moving);
    }
}
