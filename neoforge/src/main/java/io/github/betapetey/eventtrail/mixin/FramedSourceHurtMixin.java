package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.FramedSources;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item 2 F4 option A (KL-4): at the head of every {@code LivingEntity.hurt}, marks a damage source naming a real player
 * when a frame rule would withhold a death by it there (FramedSources). Observes only; the hurt step runs unchanged.
 */
@Mixin(LivingEntity.class)
abstract class FramedSourceHurtMixin {
    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$observeFramedSource(
            DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        FramedSources.observeHurt((LivingEntity) (Object) this, source);
    }
}
