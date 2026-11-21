package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.CommandCreditGuard;
import io.github.betapetey.eventtrail.events.platform.CommandEngineCreditCarrier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command credit guard (review-01 F2): one command-origin bit per live victim, re-decided at every last-attacker
 * assignment. Set only when a command names a real player as the attacker; any assignment outside a command clears
 * it. Never copied into NBT or another entity.
 */
@Mixin(LivingEntity.class)
abstract class CommandEngineCreditMixin implements CommandEngineCreditCarrier {
    @Unique
    private boolean eventtrail$commandEngineCreditExcluded;

    @Inject(
            method = "setLastHurtByMob(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$observeCommandCreditAssignment(
            LivingEntity attacker, CallbackInfo callback) {
        CommandCreditGuard.observeCreditAssignment(this, attacker);
    }

    @Override
    public boolean eventtrail$commandEngineCreditExcluded() {
        return this.eventtrail$commandEngineCreditExcluded;
    }

    @Override
    public void eventtrail$setCommandEngineCreditExcluded(boolean excluded) {
        this.eventtrail$commandEngineCreditExcluded = excluded;
    }
}
