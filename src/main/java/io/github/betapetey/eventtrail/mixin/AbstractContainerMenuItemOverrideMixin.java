package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.ItemClickOverrideProof;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GF-NESTED: records that vanilla's item-click override step (ItemStackedOnOtherEvent, then
 * {@code ItemStack#overrideStackedOnOther} and {@code #overrideOtherStackedOnMe}) handled a click. The wrap never
 * changes the step's result, always calls the original exactly once, and never throws into vanilla: both recording
 * calls catch every failure themselves.
 */
@Mixin(AbstractContainerMenu.class)
abstract class AbstractContainerMenuItemOverrideMixin {
    @WrapMethod(
            method = "tryItemClickBehaviourOverride("
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/inventory/ClickAction;"
                    + "Lnet/minecraft/world/inventory/Slot;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemStack;)Z",
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$proveItemClickOverride(
            Player player,
            ClickAction action,
            Slot slot,
            ItemStack clicked,
            ItemStack carried,
            Operation<Boolean> original) {
        ItemClickOverrideProof.Recording recording = ItemClickOverrideProof.begin(
                player, (AbstractContainerMenu) (Object) this, slot, clicked, carried);
        boolean handled = original.call(player, action, slot, clicked, carried);
        ItemClickOverrideProof.end(recording, handled);
        return handled;
    }
}
