package io.github.betapetey.eventtrail.mixin;

import com.direwolf20.mininggadgets.common.tiles.RenderBlockTileEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.MiningGadgetAuditBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.direwolf20.mininggadgets.common.items.MiningGadget", remap = false)
abstract class MiningGadgetItemMixin {
    @WrapMethod(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResultHolder<ItemStack> eventtrail$beginMiningUse(Level level, Player player,
            InteractionHand hand, Operation<InteractionResultHolder<ItemStack>> original) {
        return HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetItemMixin.beginMiningUse", original,
                once -> {
                    var boundary = MiningGadgetAuditBridge.beginUse(level, player, hand);
                    boolean returnedNormally = false;
                    try {
                        once.call();
                        returnedNormally = true;
                    } finally {
                        MiningGadgetAuditBridge.finishUse(boundary, returnedNormally);
                    }
                }, level, player, hand);
    }

    @WrapMethod(method = "onUseTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;I)V",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$miningTick(Level level, LivingEntity player, ItemStack stack, int count,
                                     Operation<Void> original) {
        HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetItemMixin.miningTick", original,
                once -> {
                    var boundary = MiningGadgetAuditBridge.beginTick(level, player, stack);
                    boolean returnedNormally = false;
                    try {
                        once.call();
                        returnedNormally = true;
                    } finally {
                        MiningGadgetAuditBridge.finishTick(boundary, returnedNormally);
                    }
                }, level, player, stack, count);
    }

    @WrapOperation(method = "onUseTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            ordinal = 0, remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$miningPlaceholder(Level level, BlockPos pos, BlockState target,
                                                Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetItemMixin.miningPlaceholder", original,
                once -> MiningGadgetAuditBridge.installPlaceholder(level, pos, target, once::call),
                level, pos, target);
    }

    @WrapOperation(method = "onUseTick", at = @At(value = "INVOKE",
            target = "Lcom/direwolf20/mininggadgets/common/tiles/RenderBlockTileEntity;setDurability(ILnet/minecraft/world/item/ItemStack;)V",
            ordinal = 1, remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$miningContinuation(RenderBlockTileEntity placeholder, int durability,
                                              ItemStack stack, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetItemMixin.miningContinuation", original,
                once -> {
                    MiningGadgetAuditBridge.continuing(placeholder, stack);
                    once.call();
                }, placeholder, durability, stack);
    }

    @WrapOperation(method = "onUseTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;stopUsingItem()V", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$precisionStop(Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetItemMixin.precisionStop", original,
                once -> MiningGadgetAuditBridge.precisionStop(player, once::call), player);
    }
}
