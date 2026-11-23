package io.github.betapetey.eventtrail.mixin.enderstorage;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderOpeningScope;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Only the exact Ender storage owner advances a witness; every native capability call is untouched. The witness
 * advance runs only while the ENDER_STORAGE key is active; {@code EnderStorageBinding} is current only then.
 */
@Mixin(value = InvWrapper.class, remap = false)
public abstract class EnderItemCapabilityMixin {
    @Shadow public abstract Container getInv();
    @WrapMethod(method = "insertItem", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$insert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderItemCapabilityMixin.insert", original, once -> {
            EnderOpeningScope.capabilityMutation(getInv(), simulate);
            once.call();
        }, slot, stack, simulate);
    }
    @WrapMethod(method = "extractItem", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderItemCapabilityMixin.extract", original, once -> {
            EnderOpeningScope.capabilityMutation(getInv(), simulate);
            once.call();
        }, slot, amount, simulate);
    }
    @WrapMethod(method = "setStackInSlot", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$set(int slot, ItemStack stack, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ENDER_STORAGE, "hook:EnderItemCapabilityMixin.set", original, once -> {
            EnderOpeningScope.capabilityMutation(getInv(), false);
            once.call();
        }, slot, stack);
    }
}
