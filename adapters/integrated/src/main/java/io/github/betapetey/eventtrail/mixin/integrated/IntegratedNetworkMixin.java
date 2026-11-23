package io.github.betapetey.eventtrail.mixin.integrated;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedNetworkCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;
@Pseudo
@Mixin(targets = "org.cyclops.integrateddynamics.core.network.Network", remap = false)
public abstract class IntegratedNetworkMixin implements IntegratedNetworkCarrier {
    @Unique private UUID eventtrail$integratedIdentity;
    @Override public UUID eventtrail$integratedNetworkIdentity() {
        if (eventtrail$integratedIdentity == null) eventtrail$integratedIdentity = UUID.randomUUID();
        return eventtrail$integratedIdentity;
    }
}
