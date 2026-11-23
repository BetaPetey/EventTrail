package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.NeoForgeTntActionAuditBridge;
import io.github.betapetey.eventtrail.events.platform.TntActionEvidenceCarrier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Holds bounded runtime-only evidence for TNT created by an exact player action. */
@Mixin(PrimedTnt.class)
abstract class PrimedTntActionEvidenceMixin implements TntActionEvidenceCarrier {
    @Unique
    private UUID eventtrail$actionIgniterId;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("TAIL"),
            require = 1)
    private void eventtrail$observeActionConstruction(
            Level level,
            double x,
            double y,
            double z,
            LivingEntity owner,
            CallbackInfo callback) {
        NeoForgeTntActionAuditBridge.observeConstruction(
                (PrimedTnt) (Object) this,
                owner);
    }

    @Override
    public UUID eventtrail$getActionIgniterId() {
        return eventtrail$actionIgniterId;
    }

    @Override
    public void eventtrail$setActionIgniterId(UUID igniterId) {
        this.eventtrail$actionIgniterId = igniterId;
    }
}
