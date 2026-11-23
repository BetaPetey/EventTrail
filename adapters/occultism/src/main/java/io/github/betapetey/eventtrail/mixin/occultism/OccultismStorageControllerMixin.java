package io.github.betapetey.eventtrail.mixin.occultism;

import com.klikli_dev.occultism.common.blockentity.StorageControllerBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOperations;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.blockentity.StorageControllerBlockEntity", remap = false)
public abstract class OccultismStorageControllerMixin {
    @WrapMethod(method = "insertStack", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private int eventtrail$insert(ItemStack stack, boolean simulate, Operation<Integer> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageControllerMixin.insert", original,
                once -> OccultismStorageOperations.controllerInsert((StorageControllerBlockEntity) (Object) this, stack, simulate,
                        once::call), stack, simulate);
    }
    @WrapMethod(method = "getItemStack", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$extract(Predicate<ItemStack> predicate, int count, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageControllerMixin.extract", original,
                once -> OccultismStorageOperations.controllerExtract((StorageControllerBlockEntity) (Object) this, count, simulate,
                        once::call), predicate, count, simulate);
    }
}
