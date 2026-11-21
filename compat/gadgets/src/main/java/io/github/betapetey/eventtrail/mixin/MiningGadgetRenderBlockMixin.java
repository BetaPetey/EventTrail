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
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.direwolf20.mininggadgets.common.tiles.RenderBlockTileEntity", remap = false)
abstract class MiningGadgetRenderBlockMixin {
    @Inject(method = "setRenderBlock(Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$replaceMiningPreimage(BlockState state, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.replaceMiningPreimage",
                () -> MiningGadgetAuditBridge.reset((RenderBlockTileEntity) (Object) this));
    }

    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$restoredMiningMember(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.restoredMiningMember",
                () -> MiningGadgetAuditBridge.reset((RenderBlockTileEntity) (Object) this));
    }

    @Inject(method = "setPlayer(Lnet/minecraft/world/entity/player/Player;)V", at = @At("RETURN"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$initializedMiningMember(Player player, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.initializedMiningMember",
                () -> MiningGadgetAuditBridge.initialized((RenderBlockTileEntity) (Object) this, player));
    }

    @WrapMethod(method = "removeBlock()V", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$committedMiningBreak(Operation<Void> original) {
        HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.committedMiningBreak", original,
                once -> {
                    var boundary = MiningGadgetAuditBridge.beginRemoval((RenderBlockTileEntity) (Object) this);
                    boolean returnedNormally = false;
                    try {
                        once.call();
                        returnedNormally = true;
                    } finally {
                        MiningGadgetAuditBridge.finishRemoval(boundary, returnedNormally);
                    }
                });
    }

    @WrapOperation(method = "removeBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$miningRemovalBoundary(Level level, BlockPos pos, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.miningRemovalBoundary", original,
                once -> {
                    MiningGadgetAuditBridge.removingBlockEntity(level, pos);
                    once.call();
                }, level, pos);
    }

    @WrapOperation(method = "removeBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$miningWorldCommit(Level level, BlockPos pos, BlockState target,
                                               Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.miningWorldCommit", original,
                once -> MiningGadgetAuditBridge.removeWorldBlock(level, pos, target, once::call),
                level, pos, target);
    }

    @Inject(method = "resetBlock()V", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$miningReset(CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetRenderBlockMixin.miningReset",
                () -> MiningGadgetAuditBridge.reset((RenderBlockTileEntity) (Object) this));
    }
}
