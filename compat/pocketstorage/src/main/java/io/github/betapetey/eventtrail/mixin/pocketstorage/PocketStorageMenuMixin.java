package io.github.betapetey.eventtrail.mixin.pocketstorage;

import com.flanks255.pocketstorage.gui.PSUContainer;
import com.flanks255.pocketstorage.inventory.PSUItemHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;
import static io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageOperations.NativeMethod.*;

@Pseudo
@Mixin(targets = "com.flanks255.pocketstorage.gui.PSUContainer", remap = false)
public abstract class PocketStorageMenuMixin implements PocketStorageMenuAccess {
    @Shadow @Final private Inventory playerInv;
    @Shadow @Final private UUID uuid;
    @Unique private PocketStorageBinding eventtrail$pocketBinding;
    @Override public Inventory eventtrail$pocketPlayerInventory() { return playerInv; }
    @Override public UUID eventtrail$pocketUuid() { return uuid; }
    @Override public PocketStorageBinding eventtrail$pocketBinding() { return eventtrail$pocketBinding; }
    @Override public void eventtrail$pocketBinding(PocketStorageBinding binding) { eventtrail$pocketBinding = binding; }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Ljava/util/UUID;Lcom/flanks255/pocketstorage/inventory/PSUItemHandler;)V",
            at = @At("TAIL"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$bindItem(int id, Inventory inventory, UUID storageId, PSUItemHandler handler, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.POCKET_STORAGE, "hook:PocketStorageMenuMixin.bindItem",
                () -> PocketStorageOpening.bind((PSUContainer) (Object) this, inventory, storageId, handler));
    }

    @WrapMethod(method = "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$vanillaAction(int slot, int drag, ClickType click, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageMenuMixin.vanillaAction", original,
                once -> PocketStorageOperations.clicked((PSUContainer) (Object) this, slot, click, player, once::call),
                slot, drag, click, player);
    }

    @WrapOperation(method = "networkSlotClick", at = @At(value = "INVOKE",
            target = "Lcom/flanks255/pocketstorage/inventory/PSUItemHandler;insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private ItemStack eventtrail$insert(PSUItemHandler handler, int slot, ItemStack input, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageMenuMixin.insert", original,
                once -> PocketStorageOperations.transfer((PSUContainer) (Object) this, handler, slot, input, 0, simulate,
                        INSERT, once::call), handler, slot, input, simulate);
    }

    @WrapOperation(method = "networkSlotClick", at = @At(value = "INVOKE",
            target = "Lcom/flanks255/pocketstorage/inventory/PSUItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private ItemStack eventtrail$extract(PSUItemHandler handler, int slot, int requested, boolean simulate, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageMenuMixin.extract", original,
                once -> PocketStorageOperations.transfer((PSUContainer) (Object) this, handler, slot, ItemStack.EMPTY, requested, simulate,
                        EXTRACT, once::call), handler, slot, requested, simulate);
    }

    @WrapOperation(method = "quickMoveStack", at = @At(value = "INVOKE",
            target = "Lcom/flanks255/pocketstorage/inventory/PSUItemHandler;insertItemSlotless(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/item/ItemStack;",
            remap = false), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$slotless(PSUItemHandler handler, ItemStack input, boolean allowEmpty, boolean allowVoid, Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.POCKET_STORAGE, "hook:PocketStorageMenuMixin.slotless", original,
                once -> PocketStorageOperations.transfer((PSUContainer) (Object) this, handler, -1, input, 0, false,
                        SLOTLESS, once::call), handler, input, allowEmpty, allowVoid);
    }
}
