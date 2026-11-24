package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MagicData.class, remap = false)
abstract class IronsMagicDataMixin {
    @Inject(method = {"initiateCast", "resetCastingState", "loadNBTData", "setSyncedData"},
            at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 4, expect = 4, allow = 4)
    private void eventtrail$invalidate(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.IRONS, "hook:IronsMagicDataMixin.invalidate",
                () -> IronsCastAuditBridge.invalidateData(this));
    }
}
