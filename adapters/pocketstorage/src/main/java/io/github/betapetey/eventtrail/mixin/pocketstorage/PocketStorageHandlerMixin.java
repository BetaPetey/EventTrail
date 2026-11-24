package io.github.betapetey.eventtrail.mixin.pocketstorage;

import com.flanks255.pocketstorage.inventory.PSUItemHandler;
import com.flanks255.pocketstorage.items.PSUTier;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageOperations;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import static io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageOperations.NativeMethod.*;

/** Actorless native capability calls remain gameplay-only; interleaved mutations invalidate observation. */
@Pseudo
@Mixin(targets = "com.flanks255.pocketstorage.inventory.PSUItemHandler", remap = false)
public abstract class PocketStorageHandlerMixin {
    @WrapMethod(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$insert(int slot, ItemStack input, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageHandlerMixin.insert", original,
                once -> PocketStorageOperations.nativeMutation((PSUItemHandler) (Object) this, INSERT, slot, simulate,
                        once::call), slot, input, simulate);
    }
    @WrapMethod(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageHandlerMixin.extract", original,
                once -> PocketStorageOperations.nativeMutation((PSUItemHandler) (Object) this, EXTRACT, slot, simulate,
                        once::call), slot, amount, simulate);
    }
    @WrapMethod(method = "insertItemSlotless(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$slotless(ItemStack input, boolean allowEmpty, boolean allowVoid, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageHandlerMixin.slotless", original,
                once -> PocketStorageOperations.nativeMutation((PSUItemHandler) (Object) this, SLOTLESS, -1, false,
                        once::call), input, allowEmpty, allowVoid);
    }
    @WrapMethod(method = "upgrade(Lcom/flanks255/pocketstorage/items/PSUTier;)V",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$upgrade(PSUTier tier, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageHandlerMixin.upgrade", original,
                once -> PocketStorageOperations.nativeMutation((PSUItemHandler) (Object) this, OTHER, -1, false,
                        once::call), tier);
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$reload(HolderLookup.Provider provider, CompoundTag tag, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageHandlerMixin.reload", original,
                once -> PocketStorageOperations.nativeMutation((PSUItemHandler) (Object) this, OTHER, -1, false,
                        once::call), provider, tag);
    }
}
