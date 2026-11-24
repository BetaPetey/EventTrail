package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.spells.fire.FireboltSpell;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = FireboltSpell.class, remap = false)
abstract class IronsFireboltSpellMixin {
    @WrapMethod(method = "onCast", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$body(Level level, int spellLevel, LivingEntity player, CastSource source,
            MagicData data, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsFireboltSpellMixin.body", original,
                once -> IronsCastAuditBridge.spellBody((AbstractSpell) (Object) this, player, once::call),
                level, spellLevel, player, source, data);
    }
    @WrapOperation(method = "onCast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$spawn(Level level, Entity entity, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsFireboltSpellMixin.spawn", original,
                once -> IronsCastAuditBridge.spawn(level, entity, once::call), level, entity);
    }
}
