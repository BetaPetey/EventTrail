package io.github.betapetey.eventtrail.mixin.integrated;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsOperations;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = InvWrapper.class, remap = false)
public abstract class IntegratedInvWrapperMixin {
    @WrapMethod(method = "insertItem", remap = false, require = 0, expect = 0)
    @HookSite
    private ItemStack eventtrail$insert(int slot, ItemStack input, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedInvWrapperMixin.insert", original,
                once -> IntegratedTerminalsOperations.cell((InvWrapper) (Object) this, slot, input, 0, simulate, true,
                        once::call), slot, input, simulate);
    }
    @WrapMethod(method = "extractItem", remap = false, require = 0, expect = 0)
    @HookSite
    private ItemStack eventtrail$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedInvWrapperMixin.extract", original,
                once -> IntegratedTerminalsOperations.cell((InvWrapper) (Object) this, slot, ItemStack.EMPTY, amount, simulate, false,
                        once::call), slot, amount, simulate);
    }
}
