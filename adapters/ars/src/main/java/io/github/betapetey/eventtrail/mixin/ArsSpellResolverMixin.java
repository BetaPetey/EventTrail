package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SpellResolver.class, remap = false)
abstract class ArsSpellResolverMixin {
    @WrapOperation(method = "onCast", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractCastMethod;onCast(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$acceptedAir(AbstractCastMethod method, ItemStack stack, LivingEntity player, Level level,
            SpellStats stats, SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.acceptedAir", original,
                once -> ArsCastAuditBridge.accepted(resolver, method, context, stats, once::call),
                method, stack, player, level, stats, context, resolver);
    }
    @WrapOperation(method = "onCastOnEntity", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractCastMethod;onCastOnEntity(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$acceptedEntity(AbstractCastMethod method, ItemStack stack, LivingEntity player, Entity target,
            InteractionHand hand, SpellStats stats, SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.acceptedEntity", original,
                once -> ArsCastAuditBridge.accepted(resolver, method, context, stats, once::call),
                method, stack, player, target, hand, stats, context, resolver);
    }
    @WrapOperation(method = "onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;)Z", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractCastMethod;onCastOnBlock(Lnet/minecraft/world/item/context/UseOnContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)Lcom/hollingsworth/arsnouveau/api/spell/CastResolveType;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private CastResolveType eventtrail$acceptedBlock(AbstractCastMethod method, UseOnContext use, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<CastResolveType> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.acceptedBlock", original,
                once -> ArsCastAuditBridge.accepted(resolver, method, context, stats, once::call),
                method, use, stats, context, resolver);
    }
    @WrapMethod(method = "onResolveEffect", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$resolve(Level level, HitResult hit, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.resolve", original,
                once -> ArsCastAuditBridge.resolveBody((SpellResolver) (Object) this, level, hit, once::call), level, hit);
    }
    @WrapMethod(method = "resume", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$resume(Level level, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.resume", original,
                once -> ArsCastAuditBridge.resumeBody((SpellResolver) (Object) this, level, once::call), level);
    }
    @WrapOperation(method = "resume", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractEffect;onResolve(Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hollingsworth/arsnouveau/api/spell/SpellStats;Lcom/hollingsworth/arsnouveau/api/spell/SpellContext;Lcom/hollingsworth/arsnouveau/api/spell/SpellResolver;)V"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$effect(AbstractEffect effect, HitResult hit, Level level, LivingEntity player, SpellStats stats,
            SpellContext context, SpellResolver resolver, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsSpellResolverMixin.effect", original,
                once -> ArsCastAuditBridge.effect(effect, hit, level, player, stats, context, resolver, once::call),
                effect, hit, level, player, stats, context, resolver);
    }
}
