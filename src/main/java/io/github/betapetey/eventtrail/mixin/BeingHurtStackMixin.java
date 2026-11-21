package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.RetaliationScope;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kill credit B signal 3 (design-05c 2.2): while a real player's {@code LivingEntity#hurt} runs on this thread, that
 * player is on the being-hurt stack, so a death his being hurt sets off (a mod's hit-back) is not credited to him. Any
 * other entity's hurt opens nothing. ServerPlayerBeingHurtMixin covers ServerPlayer's own override.
 */
@Mixin(LivingEntity.class)
abstract class BeingHurtStackMixin {
    @WrapMethod(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$trackBeingHurt(DamageSource source, float amount, Operation<Boolean> original) {
        RetaliationScope.Entry entry = RetaliationScope.openBeingHurt((LivingEntity) (Object) this);
        if (entry == null) return original.call(source, amount);
        try {
            return original.call(source, amount);
        } finally {
            entry.close();
        }
    }
}
