package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.api.spell.SpellStats;
import com.hollingsworth.arsnouveau.common.spell.method.MethodProjectile;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MethodProjectile.class, remap = false)
abstract class ArsProjectileMethodMixin {
    @WrapMethod(method = "summonProjectiles(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)V", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$method(Level level, LivingEntity player, SpellStats stats, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileMethodMixin.method", original,
                once -> ArsCastAuditBridge.methodBody(resolver, player, stats, true, once::call), level, player, stats, resolver);
    }
    @WrapOperation(method = "summonProjectiles(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$registered(Level level, Entity entity, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileMethodMixin.registered", original,
                once -> ArsCastAuditBridge.spawn(level, entity, once::call), level, entity);
    }
}
