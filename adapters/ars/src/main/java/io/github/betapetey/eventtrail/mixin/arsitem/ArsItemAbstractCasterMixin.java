package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.spell.AbstractCaster;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.spell.SpellResolver;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractCaster.class, remap = false)
abstract class ArsItemAbstractCasterMixin {
    @WrapOperation(method = "castSpell(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/network/chat/Component;Lcom/hollingsworth/arsnouveau/api/spell/Spell;)Lnet/minecraft/world/InteractionResultHolder;", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractCaster;getSpellResolver(Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;)Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private SpellResolver eventtrail$itemResolver(AbstractCaster<?> caster, SpellContext context, Level level,
            LivingEntity player, InteractionHand hand, Operation<SpellResolver> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemAbstractCasterMixin.itemResolver", original,
                once -> ArsItemCastAuditBridge.resolver(caster, context, level, player, hand, once::call),
                caster, context, level, player, hand);
    }
}
