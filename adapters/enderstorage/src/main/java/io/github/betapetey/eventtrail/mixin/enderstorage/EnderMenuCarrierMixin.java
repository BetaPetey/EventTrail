package io.github.betapetey.eventtrail.mixin.enderstorage;

import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.storage.EnderItemStorage;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderMenuCarrier;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderOpeningScope;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderStorageBinding;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "codechicken.enderstorage.container.ContainerEnderItemStorage", remap = false)
public abstract class EnderMenuCarrierMixin implements EnderMenuCarrier {
    @Unique private EnderStorageBinding eventtrail$binding;
    public EnderStorageBinding eventtrail$enderBinding() { return eventtrail$binding; }
    public void eventtrail$enderBinding(EnderStorageBinding binding) { if (eventtrail$binding == null) eventtrail$binding = binding; }
    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lcodechicken/enderstorage/storage/EnderItemStorage;)V",
            at = @At("RETURN"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$bind(int id, Inventory inventory, EnderItemStorage storage, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.ENDER_STORAGE, "hook:EnderMenuCarrierMixin.bind",
                () -> EnderOpeningScope.bind((ContainerEnderItemStorage)(Object)this, inventory, storage));
    }
}
