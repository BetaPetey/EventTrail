package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.platform.NeoForgeItemPickupAuditBridge;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Owns the exact NeoForge 1.21.1 player-inventory pickup insertion. */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @WrapOperation(
            method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add("
                            + "Lnet/minecraft/world/item/ItemStack;)Z"),
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$verifyAccountablePlayerPickup(
            Inventory inventory,
            ItemStack sourceStack,
            Operation<Boolean> original) {
        ItemEntity itemEntity = (ItemEntity) (Object) this;
        NeoForgeItemPickupAuditBridge.Boundary boundary =
                NeoForgeItemPickupAuditBridge.begin(itemEntity, inventory, sourceStack);
        boolean committed = original.call(inventory, sourceStack);
        NeoForgeItemPickupAuditBridge.finish(
                boundary, itemEntity, inventory, sourceStack);
        return committed;
    }
}
