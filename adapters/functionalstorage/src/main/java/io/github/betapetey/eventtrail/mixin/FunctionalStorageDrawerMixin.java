package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.functionalstorage.FunctionalStorageDirectActions;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.buuz135.functionalstorage.block.tile.ItemControllableDrawerTile", remap = false)
abstract class FunctionalStorageDrawerMixin {
    @WrapMethod(method = "onSlotActivated(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/core/Direction;DDDI)Lnet/minecraft/world/InteractionResult;",
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResult eventtrail$drawerDeposit(Player player, InteractionHand hand, Direction facing,
                                                      double x, double y, double z, int slot, Operation<InteractionResult> original) {
        return HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStorageDrawerMixin.drawerDeposit", original,
                once -> {
                    var scope = FunctionalStorageDirectActions.beginDrawer(this, player, hand, slot,
                            TransactionDirection.DEPOSIT);
                    boolean completed = false;
                    try {
                        InteractionResult result = once.call();
                        completed = result != null && result.consumesAction();
                    } finally {
                        FunctionalStorageDirectActions.finishDrawer(scope, completed);
                    }
                }, player, hand, facing, x, y, z, slot);
    }

    @WrapMethod(method = "onClicked(Lnet/minecraft/world/entity/player/Player;I)V", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$drawerWithdrawal(Player player, int slot, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStorageDrawerMixin.drawerWithdrawal", original,
                once -> {
                    var scope = FunctionalStorageDirectActions.beginDrawer(this, player, InteractionHand.MAIN_HAND,
                            slot, TransactionDirection.WITHDRAWAL);
                    boolean completed = false;
                    try {
                        once.call();
                        completed = true;
                    } finally {
                        FunctionalStorageDirectActions.finishDrawer(scope, completed);
                    }
                }, player, slot);
    }

    @WrapOperation(method = "onSlotActivated", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/items/IItemHandler;insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 4, expect = 4, allow = 4)
    private ItemStack eventtrail$exactInsert(IItemHandler handler, int slot, ItemStack stack, boolean simulate,
                                           Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStorageDrawerMixin.exactInsert", original,
                once -> FunctionalStorageDirectActions.insert(handler, slot, stack, simulate, once::call),
                handler, slot, stack, simulate);
    }

    @WrapOperation(method = "onClicked", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$exactExtract(IItemHandler handler, int slot, int amount, boolean simulate,
                                            Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.FUNCTIONAL_STORAGE, "hook:FunctionalStorageDrawerMixin.exactExtract", original,
                once -> FunctionalStorageDirectActions.extract(handler, slot, amount, simulate, once::call),
                handler, slot, amount, simulate);
    }
}
