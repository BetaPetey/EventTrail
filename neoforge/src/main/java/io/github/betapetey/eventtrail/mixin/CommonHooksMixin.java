package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.NeoForgeItemDropAuditBridge;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlacementAuditBridge;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;

/** Verifies final world outcomes for placement and explicit item-discard hooks. */
@Mixin(value = CommonHooks.class, remap = false)
abstract class CommonHooksMixin {
    @WrapMethod(
            method = "onPlayerTossEvent("
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/ItemStack;Z)"
                    + "Lnet/minecraft/world/entity/item/ItemEntity;",
            remap = false,
            require = 1,
            expect = 1,
            allow = 1)
    private static ItemEntity eventtrail$observeWorldAdmittedItemDrop(
            Player player,
            ItemStack stack,
            boolean includeThrowerName,
            Operation<ItemEntity> original) {
        NeoForgeItemDropAuditBridge.observeTossCandidate(player, stack);
        ItemEntity entity = original.call(player, stack, includeThrowerName);
        NeoForgeItemDropAuditBridge.observeTossResult(player, entity);
        return entity;
    }

    @WrapMethod(
            method = "onPlaceItemIntoWorld("
                    + "Lnet/minecraft/world/item/context/UseOnContext;)"
                    + "Lnet/minecraft/world/InteractionResult;",
            remap = false,
            require = 1,
            expect = 1,
            allow = 1)
    private static InteractionResult eventtrail$verifyCommittedPlacement(
            UseOnContext context,
            Operation<InteractionResult> original) {
        NeoForgePlacementAuditBridge.Boundary boundary =
                NeoForgePlacementAuditBridge.begin(context);
        InteractionResult result = null;
        boolean completedNormally = false;
        try {
            result = original.call(context);
            completedNormally = true;
            return result;
        } finally {
            if (completedNormally) {
                NeoForgePlacementAuditBridge.finish(boundary, result);
            } else {
                NeoForgePlacementAuditBridge.abort(boundary);
            }
        }
    }
}
