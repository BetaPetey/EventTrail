package io.github.betapetey.eventtrail.mixin.occultism;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOpening;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.InteractionResult;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.item.storage.StorageRemoteItem", remap = false)
public abstract class OccultismStorageRemoteOpeningMixin {
    @Inject(method = "useOn", at = @At("HEAD"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$relink(UseOnContext context, CallbackInfoReturnable<InteractionResult> ci) {
        HookGuard.observe(AdapterKey.OCCULTISM, "hook:OccultismStorageRemoteOpeningMixin.relink",
                () -> OccultismStorageOpening.relink(context.getPlayer(), context.getItemInHand()));
    }
    @WrapMethod(method = "use", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResultHolder<ItemStack> eventtrail$open(Level level, Player player, InteractionHand hand,
            Operation<InteractionResultHolder<ItemStack>> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageRemoteOpeningMixin.open", original,
                once -> OccultismStorageOpening.remote(player, once::call), level, player, hand);
    }
}
