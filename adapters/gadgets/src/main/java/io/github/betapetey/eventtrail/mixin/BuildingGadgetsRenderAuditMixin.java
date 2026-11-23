package io.github.betapetey.eventtrail.mixin;

import com.direwolf20.buildinggadgets2.common.blockentities.RenderBlockBE;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.GadgetAuditRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderBlockBE.class, remap = false)
abstract class BuildingGadgetsRenderAuditMixin {
    @Unique private boolean eventtrail$commitActive;
    @Unique private boolean eventtrail$mutationSucceeded;

    @Inject(method = {"setRenderData", "loadAdditional", "setRemoved"}, at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private void eventtrail$invalidateReconfiguredPlaceholder(CallbackInfo callback) {
        HookGuard.observe(AdapterKey.BUILDING_GADGETS,
                "hook:BuildingGadgetsRenderAuditMixin.invalidateReconfiguredPlaceholder",
                () -> {
                    GadgetAuditRuntime.invalidate(this);
                    // GADGET-DELAYED (review-01 MINOR-1): a placeholder removed before its own final write is counted
                    // when the gadget had taken away a real block; at a reconfiguration, a load or a chunk unload the
                    // placeholder block is still there (or there is no chunk), so nothing is counted.
                    RenderBlockBE render = (RenderBlockBE) (Object) this;
                    GadgetAuditRuntime.noteRemoved(render, render.getBlockState().getBlock(), render.sourceBlock,
                            eventtrail$commitActive);
                });
    }

    @WrapMethod(method = "setRealBlock", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$observeTerminalMutation(BlockState target, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.BUILDING_GADGETS, "hook:BuildingGadgetsRenderAuditMixin.observeTerminalMutation",
                original, once -> {
                    GadgetAuditRuntime.Claim claim = GadgetAuditRuntime.beginCommit((BlockEntity) (Object) this);
                    RenderBlockBE render = (RenderBlockBE) (Object) this;
                    // GADGET-DELAYED: what the placeholder was and replaced, read before the native write.
                    Block placeholderBlock = render.getBlockState().getBlock();
                    BlockState source = render.sourceBlock;
                    if (claim != null && (target != claim.expectedTarget() || render.targetBlock != claim.expectedTarget()
                            || (claim.kind() == GadgetAuditRuntime.Kind.PLACE
                                ? render.sourceBlock == null || !render.sourceBlock.isAir()
                                : render.sourceBlock != claim.preimage().blockState()))) {
                        GadgetAuditRuntime.finish(claim, false, false);
                        claim = null;
                    }
                    boolean previousActive = eventtrail$commitActive;
                    boolean previousSucceeded = eventtrail$mutationSucceeded;
                    eventtrail$commitActive = true;
                    eventtrail$mutationSucceeded = false;
                    boolean returnedNormally = false;
                    try {
                        once.call();
                        returnedNormally = true;
                    } finally {
                        boolean succeeded = eventtrail$mutationSucceeded;
                        eventtrail$commitActive = previousActive;
                        eventtrail$mutationSucceeded = previousSucceeded;
                        boolean handed = GadgetAuditRuntime.finish(claim, succeeded, returnedNormally);
                        GadgetAuditRuntime.noteTerminal((BlockEntity) (Object) this, placeholderBlock, source,
                                handed, succeeded);
                    }
                }, target);
    }

    @WrapOperation(method = "setRealBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private boolean eventtrail$observeActualWrite(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.BUILDING_GADGETS, "hook:BuildingGadgetsRenderAuditMixin.observeActualWrite",
                original, once -> {
                    boolean result = once.call();
                    BlockEntity self = (BlockEntity) (Object) this;
                    if (eventtrail$commitActive && level == self.getLevel() && pos.equals(self.getBlockPos())) {
                        eventtrail$mutationSucceeded |= result;
                    }
                }, level, pos, state);
    }
}
