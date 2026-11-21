package io.github.betapetey.eventtrail.mixin.arsitem;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsItemCastAuditBridge;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MethodTouch.class, remap = false)
abstract class ArsItemTouchMixin {
    @WrapMethod(method = "onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$itemTouchBlock(UseOnContext use, SpellStats stats, SpellContext context,
            SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTouchMixin.itemTouchBlock", original,
                once -> ArsItemCastAuditBridge.touchBody(resolver, use, stats, context, once::call),
                use, stats, context, resolver);
    }

    @WrapOperation(method = "onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;onResolveEffect(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$itemTouchResolve(SpellResolver resolver, Level world, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_ITEM, "hook:ArsItemTouchMixin.itemTouchResolve", original,
                once -> ArsItemCastAuditBridge.resolve(resolver, world, hit, once::call), resolver, world, hit);
    }
}
