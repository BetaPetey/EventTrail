package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.MiningGadgetAuditBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enabled only with the exact admitted Mining Gadgets artifact. */
@Mixin(LivingEntity.class)
abstract class MiningGadgetUseLifecycleMixin {
    @Inject(method = "startUsingItem(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$replaceMiningUse(InteractionHand hand, CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetUseLifecycleMixin.replaceMiningUse",
                () -> MiningGadgetAuditBridge.stopUsing((LivingEntity) (Object) this));
    }

    @Inject(method = "stopUsingItem()V", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$stopMiningUse(CallbackInfo callback) {
        HookGuard.observe(AdapterKey.MINING_GADGETS, "hook:MiningGadgetUseLifecycleMixin.stopMiningUse",
                () -> MiningGadgetAuditBridge.stopUsing((LivingEntity) (Object) this));
    }
}
