package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.item.inv.ExtractedStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** These hooks only observe a consumed token from the exact Toss handler consumer. */
@Mixin(value = ExtractedStack.class, remap = false)
abstract class ArsItemExtractedStackMixin {
    @WrapMethod(method = "returnOrDrop", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemReturnBody(Level level, BlockPos position, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemExtractedStackMixin.itemReturnBody", original,
                once -> ArsItemCastAuditBridge.returnBody((ExtractedStack) (Object) this, level, position, once::call),
                level, position);
    }

    @WrapOperation(method = "returnOrDrop", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/item/inv/ExtractedStack;returnStack()Lnet/minecraft/world/item/ItemStack;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$itemReturnedStack(ExtractedStack extracted, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemExtractedStackMixin.itemReturnedStack", original,
                once -> ArsItemCastAuditBridge.returnedStack(extracted, once::call), extracted);
    }

    @WrapOperation(method = "returnOrDrop", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$itemRemainderWorld(Level level, Entity entity, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemExtractedStackMixin.itemRemainderWorld", original,
                once -> ArsItemCastAuditBridge.remainderSpawn(level, entity, once::call), level, entity);
    }
}
