package io.github.betapetey.eventtrail.mixin.create;

import io.github.betapetey.eventtrail.inventory.adapters.create.CreateMountedBinding;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateStorageCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.compat.create.MountedStorageContainerMenuBase", remap = false)
public abstract class SophisticatedMountedMenuCarrierMixin implements CreateStorageCarrier {
    @Unique private CreateMountedBinding eventtrail$binding;
    public CreateMountedBinding eventtrail$createBinding() { return eventtrail$binding; }
    public void eventtrail$createBinding(CreateMountedBinding binding) { if (eventtrail$binding == null) eventtrail$binding = binding; }
}
