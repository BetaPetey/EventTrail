package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
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

@Mixin(targets = "com.hollingsworth.arsnouveau.common.spell.effect.EffectWindburst", remap = false)
abstract class ArsWindburstMixin {
    @WrapOperation(method = "onResolve", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/Explosion;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private Explosion eventtrail$nativeExplosion(Level level, Entity sourceEntity, DamageSource source, ExplosionDamageCalculator calculator,
            double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction,
            ParticleOptions small, ParticleOptions large, Holder<SoundEvent> sound, Operation<Explosion> original) {
        // Denial-only: the body runs while ARS_CAST is OFF or stopped (HookGuard.wrapDenial). The negative scope is
        // closed in a finally after the mod's call, not by try-with-resources (HookGuard class note). If opening the
        // scope fails, the body still makes the mod's call, then hands the failure on (an explosion has no single
        // victim whose credit could be excluded here).
        return HookGuard.wrapDenial(AdapterKey.ARS_CAST, "hook:ArsWindburstMixin.nativeExplosion", original,
                once -> {
                    ArsNegativeExplosionScope.Boundary boundary;
                    try {
                        boundary = ArsNegativeExplosionScope.begin(level, sourceEntity);
                    } catch (Throwable scopeFailure) {
                        // The guard re-throws a failure of the call as the mod's own exception.
                        try {
                            once.call();
                        } catch (Throwable alsoFailed) { }
                        throw scopeFailure;
                    }
                    try {
                        once.call();
                    } finally {
                        boundary.close();
                    }
                }, level, sourceEntity, source, calculator, x, y, z, radius, fire, interaction, small, large, sound);
    }
}
