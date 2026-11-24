package io.github.betapetey.eventtrail.mixin.create;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateMountedBinding;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOpeningScope;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateStorageCarrier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = "com.simibubi.create.api.contraption.storage.item.menu.StorageInteractionWrapper", remap = false)
public abstract class CreateStorageWrapperMixin implements CreateStorageCarrier {
    @Unique private CreateMountedBinding eventtrail$binding;
    public CreateMountedBinding eventtrail$createBinding() { return eventtrail$binding; }
    public void eventtrail$createBinding(CreateMountedBinding binding) { if (eventtrail$binding == null) eventtrail$binding = binding; }
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$bind(IItemHandlerModifiable handler, Predicate<Player> valid, Consumer<Player> close, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.CREATE, "hook:CreateStorageWrapperMixin.bind",
                () -> CreateOpeningScope.bindWrapper(this, handler));
    }
}
