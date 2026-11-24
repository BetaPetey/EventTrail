package io.github.betapetey.eventtrail.mixin.ae2inplace;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.parts.Ae2InPlaceMoves;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * Parts readers design-03 5.4 (P-AE2-CU): a crafting-unit upgrade added or swapped ({@code upgrade}, not
 * {@code useItemOn}, so a memory-card paste on a crafting block is never inside this window) or taken out
 * ({@code useWithoutItem} in alternate use mode). MEGA Cells' crafting blocks are AE2's own classes (C-T8 read).
 * AE2's call runs exactly once through {@link HookGuard} ({@link Ae2InPlaceMoves#craftingChange}).
 */
@Pseudo
@Mixin(targets = "appeng.block.crafting.AbstractCraftingUnitBlock", remap = false)
public abstract class Ae2CraftingUnitUpgradeMixin {
    @Unique
    private static final String UPGRADE_SITE = "hook:Ae2CraftingUnitUpgradeMixin.craftingUpgrade";
    @Unique
    private static final String TAKE_OUT_SITE = "hook:Ae2CraftingUnitUpgradeMixin.craftingTakeOut";

    @WrapMethod(
            method = "upgrade(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Z",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$craftingUpgrade(ItemStack heldItem, BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.PARTS_AE2_INPLACE, UPGRADE_SITE, original,
                once -> Ae2InPlaceMoves.craftingChange(AdapterKey.PARTS_AE2_INPLACE, UPGRADE_SITE, once, level, pos,
                        player),
                heldItem, state, level, pos, player, hit);
    }

    @WrapMethod(
            method = "useWithoutItem(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)"
                    + "Lnet/minecraft/world/InteractionResult;",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResult eventtrail$craftingTakeOut(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit, Operation<InteractionResult> original) {
        return HookGuard.wrap(AdapterKey.PARTS_AE2_INPLACE, TAKE_OUT_SITE, original,
                once -> Ae2InPlaceMoves.craftingChange(AdapterKey.PARTS_AE2_INPLACE, TAKE_OUT_SITE, once, level, pos,
                        player),
                state, level, pos, player, hit);
    }
}
