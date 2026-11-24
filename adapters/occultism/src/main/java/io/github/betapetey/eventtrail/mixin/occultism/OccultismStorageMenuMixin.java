package io.github.betapetey.eventtrail.mixin.occultism;

import com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase", remap = false)
public abstract class OccultismStorageMenuMixin implements OccultismStorageMenuAccess {
    @Unique private OccultismStorageBinding eventtrail$occultismBinding;
    @Override public OccultismStorageBinding eventtrail$occultismBinding() { return eventtrail$occultismBinding; }
    @Override public void eventtrail$occultismBinding(OccultismStorageBinding binding) { eventtrail$occultismBinding = binding; }
    @WrapMethod(method = "quickMoveStack", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$quickMove(Player player, int slot, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageMenuMixin.quickMove", original,
                once -> OccultismStorageOperations.quickMove((StorageControllerContainerBase) (Object) this, player, slot,
                        once::call), player, slot);
    }
}
