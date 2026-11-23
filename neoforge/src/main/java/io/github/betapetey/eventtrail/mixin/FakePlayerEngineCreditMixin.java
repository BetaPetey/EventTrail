package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.FakePlayerAttackGuard;
import io.github.betapetey.eventtrail.events.platform.FakePlayerEngineCreditCarrier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fix 4a: one exclusion bit per live victim, re-decided at every last-attacker assignment. Set only when a FakePlayer
 * attack names a real player; any other assignment clears it. Never copied into NBT or another entity.
 */
@Mixin(LivingEntity.class)
abstract class FakePlayerEngineCreditMixin implements FakePlayerEngineCreditCarrier {
    @Unique
    private boolean eventtrail$fakePlayerEngineCreditExcluded;

    @Inject(
            method = "setLastHurtByMob(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$observeFakePlayerCreditAssignment(
            LivingEntity attacker, CallbackInfo callback) {
        FakePlayerAttackGuard.observeCreditAssignment(this, attacker);
    }

    @Override
    public boolean eventtrail$fakePlayerEngineCreditExcluded() {
        return this.eventtrail$fakePlayerEngineCreditExcluded;
    }

    @Override
    public void eventtrail$setFakePlayerEngineCreditExcluded(boolean excluded) {
        this.eventtrail$fakePlayerEngineCreditExcluded = excluded;
    }
}
