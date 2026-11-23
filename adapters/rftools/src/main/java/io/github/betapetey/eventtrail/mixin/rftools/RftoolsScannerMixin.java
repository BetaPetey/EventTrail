package io.github.betapetey.eventtrail.mixin.rftools;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsScannerCarrier;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsScannerOperations;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

/**
 * The reload epoch is kept only while the RFTOOLS_STORAGE key is active; {@code RftoolsScannerBinding} is current only
 * then, so a skipped reload can never keep a binding open.
 */
@Pseudo
@Mixin(targets = "mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerTileEntity", remap = false)
public abstract class RftoolsScannerMixin implements RftoolsScannerCarrier {
    @Unique private final UUID eventtrail$runtimeIdentity = UUID.randomUUID();
    @Unique private long eventtrail$loadEpoch;
    public UUID eventtrail$rftoolsRuntimeIdentity() { return eventtrail$runtimeIdentity; }
    public long eventtrail$rftoolsLoadEpoch() { return eventtrail$loadEpoch; }
    @Inject(method = "loadAdditional", at = @At("HEAD"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$nativeReload(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsScannerMixin.nativeReload", () -> {
            if (eventtrail$loadEpoch != Long.MAX_VALUE) eventtrail$loadEpoch++;
        });
    }
    @WrapMethod(method = "requestStack", remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$nativeRequest(BlockPos pos, ItemStack requested, int amount, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsScannerMixin.nativeRequest", original,
                once -> RftoolsScannerOperations.request((StorageScannerTileEntity)(Object)this, pos, requested, amount, player,
                        once::call), pos, requested, amount, player);
    }
    @WrapOperation(method = "requestStack", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$committedDelivery(Inventory inventory, ItemStack stack, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsScannerMixin.committedDelivery", original,
                once -> RftoolsScannerOperations.deliver((StorageScannerTileEntity)(Object)this, inventory, stack,
                        once::call), inventory, stack);
    }
}
