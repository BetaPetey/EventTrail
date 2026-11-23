package io.github.betapetey.eventtrail.mixin.pocketstorage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageOpening;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.flanks255.pocketstorage.items.PocketStorageUnit", remap = false)
public abstract class PocketStorageItemMixin {
    @WrapMethod(method = "openGUI(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)V",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$nativeOpen(Level level, Player player, InteractionHand hand, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageItemMixin.nativeOpen", original,
                once -> PocketStorageOpening.open(level, player, hand, once::call), level, player, hand);
    }
}
