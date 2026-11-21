package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.spells.IronsEngineCreditCarrier;
import io.github.betapetey.eventtrail.events.spells.IronsEngineCreditGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional-mod-independent exclusion; admitted whenever Iron's is present. */
@Mixin(LivingEntity.class)
abstract class IronsEngineCreditMixin implements IronsEngineCreditCarrier {
    /** One bit per live victim; never copied into NBT or another entity. */
    @Unique
    private boolean eventtrail$ironsEngineCreditExcluded;

    @Inject(
            method = "setLastHurtByMob(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$clearReplacedEngineCredit(
            LivingEntity attacker, CallbackInfo callback) {
        IronsEngineCreditGuard.clear(this);
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
    private void eventtrail$observeEngineCreditSource(
            LivingEntity victim,
            LivingEntity attacker,
            Operation<Void> original,
            DamageSource source,
            float amount) {
        original.call(victim, attacker);
        if (victim == (Object) this) {
            IronsEngineCreditGuard.observeCreditSource(this, source);
        }
    }

    @Override
    public boolean eventtrail$ironsEngineCreditExcluded() {
        return this.eventtrail$ironsEngineCreditExcluded;
    }

    @Override
    public void eventtrail$setIronsEngineCreditExcluded(boolean excluded) {
        this.eventtrail$ironsEngineCreditExcluded = excluded;
    }
}
