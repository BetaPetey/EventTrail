package io.github.betapetey.eventtrail.mixin.occultism;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOpening;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {"com.klikli_dev.occultism.common.block.storage.StorageControllerBlock",
        "com.klikli_dev.occultism.common.block.storage.StableWormholeBlock"}, remap = false)
public abstract class OccultismStorageBlockOpeningMixin {
    @WrapMethod(method = "useItemOn", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemInteractionResult eventtrail$open(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageBlockOpeningMixin.open", original,
                once -> OccultismStorageOpening.block(level, pos, player, once::call),
                stack, state, level, pos, player, hand, hit);
    }
}
