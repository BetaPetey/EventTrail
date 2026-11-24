package io.github.betapetey.eventtrail.mixin.enderstorage;

import codechicken.enderstorage.item.ItemEnderPouch;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderOpeningScope;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "codechicken.enderstorage.item.ItemEnderPouch", remap = false)
public abstract class EnderPouchOpenMixin {
    @WrapMethod(method = "use", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResultHolder<ItemStack> eventtrail$opening(Level level, Player player, InteractionHand hand,
            Operation<InteractionResultHolder<ItemStack>> original) {
        return HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderPouchOpenMixin.opening", original,
                once -> EnderOpeningScope.pouch((ItemEnderPouch)(Object)this, player, player.getItemInHand(hand), once::call),
                level, player, hand);
    }
    @WrapMethod(method = "onItemUseFirst", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResult eventtrail$retune(ItemStack stack, UseOnContext context, Operation<InteractionResult> original) {
        return HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderPouchOpenMixin.retune", original,
                once -> EnderOpeningScope.retunePouch(stack, context.getPlayer(), once::call), stack, context);
    }
}
