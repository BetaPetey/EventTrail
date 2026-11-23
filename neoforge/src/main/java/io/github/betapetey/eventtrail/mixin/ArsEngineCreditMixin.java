package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditCarrier;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No optional API linkage; safe exclusion when Ars is present, even if unadmitted. */
@Mixin(LivingEntity.class)
abstract class ArsEngineCreditMixin implements ArsEngineCreditCarrier {
    @Unique
    private boolean eventtrail$arsEngineCreditExcluded;

    @Inject(
            method = "setLastHurtByMob(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$clearReplacedArsEngineCredit(
            LivingEntity attacker, CallbackInfo callback) {
        ArsEngineCreditGuard.clear(this);
    }

    @WrapOperation(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setLastHurtByMob("
                            + "Lnet/minecraft/world/entity/LivingEntity;)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeArsEngineCreditSource(
            LivingEntity victim,
            LivingEntity attacker,
            Operation<Void> original,
            DamageSource source,
            float amount) {
        original.call(victim, attacker);
        if (victim == (Object) this) ArsEngineCreditGuard.observeCreditSource(this, source);
    }

    @Override
    public boolean eventtrail$arsEngineCreditExcluded() {
        return this.eventtrail$arsEngineCreditExcluded;
    }

    @Override
    public void eventtrail$setArsEngineCreditExcluded(boolean excluded) {
        this.eventtrail$arsEngineCreditExcluded = excluded;
    }
}
