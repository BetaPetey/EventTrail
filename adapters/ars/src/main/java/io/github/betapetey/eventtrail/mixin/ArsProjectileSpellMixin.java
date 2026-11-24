package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.hollingsworth.arsnouveau.common.entity.EntityProjectileSpell;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityProjectileSpell.class, remap = false)
abstract class ArsProjectileSpellMixin {
    @WrapMethod(method = "tick", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$tick(Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.tick", original,
                once -> ArsCastAuditBridge.projectileTick((Entity) (Object) this, once::call));
    }
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/common/entity/EntityProjectileSpell;traceAnyHit(Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$nativeTrace(EntityProjectileSpell entity, HitResult result, Vec3 from, Vec3 to, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.nativeTrace", original,
                once -> ArsCastAuditBridge.trace(entity, once::call), entity, result, from, to);
    }
    @WrapMethod(method = "traceAnyHit", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$traceBody(HitResult result, Vec3 from, Vec3 to, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.traceBody", original,
                once -> ArsCastAuditBridge.traceBody((Entity) (Object) this, once::call), result, from, to);
    }
    @WrapOperation(method = "traceAnyHit", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/common/entity/EntityProjectileSpell;onHit(Lnet/minecraft/world/phys/HitResult;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$nativeHit(EntityProjectileSpell entity, HitResult result, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.nativeHit", original,
                once -> ArsCastAuditBridge.hit(entity, result, once::call), entity, result);
    }
    @WrapMethod(method = "onHit", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$hitBody(HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.hitBody", original,
                once -> ArsCastAuditBridge.hitBody((Entity) (Object) this, hit, once::call), hit);
    }
    @WrapOperation(method = "onHit", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;onResolveEffect(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;)V"), require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private void eventtrail$resolve(SpellResolver resolver, Level level, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.resolve", original,
                once -> ArsCastAuditBridge.resolve(resolver, level, hit, (Entity) (Object) this, once::call),
                resolver, level, hit);
    }
    @Inject(method = {"setResolver", "setOwner", "load", "readAdditionalSaveData", "setRemoved", "redirect"}, at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 6, expect = 6, allow = 6)
    private void eventtrail$invalidate(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.invalidate",
                () -> ArsCastAuditBridge.invalidateEntity(this));
    }
    @Inject(method = "changeDimension", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$dimension(CallbackInfoReturnable<Entity> cir) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsProjectileSpellMixin.dimension",
                () -> ArsCastAuditBridge.invalidateEntity(this));
    }
}
