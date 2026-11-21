package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SpellContext.class, remap = false)
abstract class ArsSpellContextMixin {
    @Inject(method = {"stop", "setCaster", "setCasterTool", "setCurrentIndex", "delay", "rehydrate"}, at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 6, expect = 6, allow = 6)
    private void eventtrail$invalidate(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsSpellContextMixin.invalidate",
                () -> ArsCastAuditBridge.invalidateContext(this));
    }
    @Inject(method = "setCanceled(ZLcom/hollingsworth/arsnouveau/api/spell/CancelReason;)Z", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$cancel(CallbackInfoReturnable<Boolean> cir) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsSpellContextMixin.cancel",
                () -> ArsCastAuditBridge.invalidateContext(this));
    }
    @Inject(method = {"withSpell", "withWrappedCaster", "withParent"}, at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private void eventtrail$replacement(CallbackInfoReturnable<SpellContext> cir) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsSpellContextMixin.replacement",
                () -> ArsCastAuditBridge.invalidateContext(this));
    }
    @Inject(method = "resetCastCounter", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$reset(CallbackInfoReturnable<SpellContext> cir) {
        HookGuard.observe(AdapterKey.ARS_CAST, "hook:ArsSpellContextMixin.reset",
                () -> ArsCastAuditBridge.resetContext(this));
    }
}
