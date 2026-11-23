package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.spells.lightning.ShockwaveSpell;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ShockwaveSpell.class, remap = false)
abstract class IronsShockwaveSpellMixin {
    @WrapMethod(method = "onCast", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$body(Level level, int spellLevel, LivingEntity player, CastSource source,
            MagicData data, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsShockwaveSpellMixin.body", original,
                once -> IronsCastAuditBridge.spellBody((AbstractSpell) (Object) this, player, once::call),
                level, spellLevel, player, source, data);
    }
    @WrapOperation(method = "lambda$onCast$1", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/damage/DamageSources;applyDamage(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/damagesource/DamageSource;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$damage(Entity target, float damage, DamageSource source, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsShockwaveSpellMixin.damage", original,
                once -> IronsCastAuditBridge.directDamage((AbstractSpell) (Object) this, target, damage, source,
                        once::call),
                target, damage, source);
    }
}
