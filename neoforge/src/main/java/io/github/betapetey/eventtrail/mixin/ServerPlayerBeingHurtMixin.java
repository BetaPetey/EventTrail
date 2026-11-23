package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.RetaliationScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kill credit B signal 3 (design-05c 2.2): {@code ServerPlayer} overrides {@code hurt}, and its override can return
 * before reaching {@code LivingEntity#hurt}, so the being-hurt push is also made here, for a real player only. The
 * nested push from BeingHurtStackMixin, when the override does reach it, pops first.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerBeingHurtMixin {
    @WrapMethod(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$trackPlayerBeingHurt(DamageSource source, float amount, Operation<Boolean> original) {
        RetaliationScope.Entry entry = RetaliationScope.openBeingHurt((ServerPlayer) (Object) this);
        if (entry == null) return original.call(source, amount);
        try {
            return original.call(source, amount);
        } finally {
            entry.close();
        }
    }
}
