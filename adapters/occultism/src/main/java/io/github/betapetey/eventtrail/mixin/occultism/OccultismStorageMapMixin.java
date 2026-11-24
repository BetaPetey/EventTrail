package io.github.betapetey.eventtrail.mixin.occultism;

import com.klikli_dev.occultism.common.misc.MapItemStackHandler;
import com.klikli_dev.occultism.common.misc.ItemStackKey;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageOperations;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageMapAccess;
import io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageMutationWitness;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.misc.MapItemStackHandler", remap = false)
public abstract class OccultismStorageMapMixin implements OccultismStorageMapAccess {
    @Unique private final OccultismStorageMutationWitness eventtrail$occultismWitness = new OccultismStorageMutationWitness();
    @Override public OccultismStorageMutationWitness eventtrail$occultismMutations() { return eventtrail$occultismWitness; }
    @WrapMethod(method = "insertItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$insert(ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageMapMixin.insert", original,
                once -> OccultismStorageOperations.insert((MapItemStackHandler) (Object) this, stack, simulate, once::call),
                stack, simulate);
    }
    @WrapMethod(method = "extractItem(Lcom/klikli_dev/occultism/common/misc/ItemStackKey;IZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$extract(ItemStackKey key, int count, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageMapMixin.extract", original,
                once -> OccultismStorageOperations.extract((MapItemStackHandler) (Object) this, key, count, simulate, once::call),
                key, count, simulate);
    }
    @WrapMethod(method = "setStackInSlot", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$set(int slot, ItemStack stack, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageMapMixin.set", original,
                once -> OccultismStorageOperations.unexpectedMutation((MapItemStackHandler) (Object) this, once::call),
                slot, stack);
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$load(HolderLookup.Provider provider, CompoundTag data, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.OCCULTISM, "hook:OccultismStorageMapMixin.load", original,
                once -> OccultismStorageOperations.unexpectedMutation((MapItemStackHandler) (Object) this, once::call),
                provider, data);
    }
}
