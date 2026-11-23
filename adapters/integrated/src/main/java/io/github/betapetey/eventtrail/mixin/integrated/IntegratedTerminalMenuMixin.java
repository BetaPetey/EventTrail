package io.github.betapetey.eventtrail.mixin.integrated;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedMenuCarrier;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsBinding;
import org.cyclops.integratedterminals.inventory.container.ContainerTerminalStoragePart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.inventory.container.ContainerTerminalStoragePart", remap = false)
public abstract class IntegratedTerminalMenuMixin implements IntegratedMenuCarrier {
    @Unique private IntegratedTerminalsBinding eventtrail$integratedBinding;
    // A bare <init> selector matches only the first overload: the client buffer constructor.
    // Both native server construction and client delegation finish these exact field assignments here.
    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lorg/cyclops/integrateddynamics/api/part/PartTarget;Ljava/util/Optional;Lorg/cyclops/integratedterminals/part/PartTypeTerminalStorage;Ljava/util/Optional;Lorg/cyclops/integratedterminals/inventory/container/TerminalStorageState;)V",
            at = @At("RETURN"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$capture(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedTerminalMenuMixin.capture", () -> {
            if (eventtrail$integratedBinding == null) eventtrail$integratedBinding =
                    IntegratedTerminalsBinding.capture((ContainerTerminalStoragePart) (Object) this);
        });
    }
    @Override public IntegratedTerminalsBinding eventtrail$integratedBinding() { return eventtrail$integratedBinding; }
}
