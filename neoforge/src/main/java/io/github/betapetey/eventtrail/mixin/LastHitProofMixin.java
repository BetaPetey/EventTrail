package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.platform.LastHitProof;
import io.github.betapetey.eventtrail.events.platform.LastHitProofCarrier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Item 2 F1: the last-hit proof, a core hook. Every {@code setLastHurtByMob} clears it at its head; the hurt step's own
 * call sets it afterwards only for a proven own hit of a real player ({@link LastHitProof}).
 */
@Mixin(LivingEntity.class)
abstract class LastHitProofMixin implements LastHitProofCarrier {
    /** One UUID and one tick per live victim; never copied into NBT or another entity. */
    @Unique
    private UUID eventtrail$provenLastHitPlayer;
    @Unique
    private int eventtrail$provenLastHitTick;

    @Inject(
            method = "setLastHurtByMob(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"),
            require = 1)
    private void eventtrail$clearLastHitProof(LivingEntity attacker, CallbackInfo callback) {
        LastHitProof.clear((LivingEntity) (Object) this);
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
    private void eventtrail$proveLastHit(
            LivingEntity victim,
            LivingEntity attacker,
            Operation<Void> original,
            DamageSource source,
            float amount) {
        original.call(victim, attacker);
        if (victim == (Object) this) {
            LastHitProof.observe(victim, attacker, source);
        }
    }

    @Override
    public UUID eventtrail$provenLastHitPlayer() {
        return this.eventtrail$provenLastHitPlayer;
    }

    @Override
    public int eventtrail$provenLastHitTick() {
        return this.eventtrail$provenLastHitTick;
    }

    @Override
    public void eventtrail$setProvenLastHit(UUID player, int tick) {
        this.eventtrail$provenLastHitPlayer = player;
        this.eventtrail$provenLastHitTick = tick;
    }
}
