package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsNegativeExplosionScope;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
abstract class ArsExplosionConstructionMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Explosion$BlockInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)V", at = @At("RETURN"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$constructed(Level level, Entity sourceEntity, DamageSource source, ExplosionDamageCalculator calculator,
            double x, double y, double z, float radius, boolean fire, Explosion.BlockInteraction interaction,
            ParticleOptions small, ParticleOptions large, Holder<SoundEvent> sound, CallbackInfo ci) {
        // Denial-only: binds the Windburst scope's explosion while ARS_CAST is OFF or stopped too (HookGuard.observeDenial).
        HookGuard.observeDenial(AdapterKey.ARS_CAST, "hook:ArsExplosionConstructionMixin.constructed",
                () -> ArsNegativeExplosionScope.constructed((Explosion) (Object) this, level, sourceEntity));
    }
}
