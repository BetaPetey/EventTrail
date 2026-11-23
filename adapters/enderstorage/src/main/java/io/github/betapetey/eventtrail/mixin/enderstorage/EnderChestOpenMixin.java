package io.github.betapetey.eventtrail.mixin.enderstorage;

import codechicken.enderstorage.tile.TileEnderChest;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderOpeningScope;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "codechicken.enderstorage.tile.TileEnderChest", remap = false)
public abstract class EnderChestOpenMixin {
    @WrapMethod(method = "activate", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$opening(Player player, int subHit, InteractionHand hand, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderChestOpenMixin.opening", original,
                once -> EnderOpeningScope.chest((TileEnderChest)(Object)this, player, once::call), player, subHit, hand);
    }
}
