package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.platform.PlayerActionScopes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GF-SCOPE Q30: the steered-vehicle tick scope, around {@code Entity#tick} of a non-living vehicle whose controlling
 * passenger is a real player who moved it this tick. A living mount, pet, summon or dragon never opens one.
 */
@Mixin(ServerLevel.class)
abstract class PlayerActionScopeSteeredMixin {
    @WrapOperation(
            method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$steeredVehicleScope(Entity entity, Operation<Void> original) {
        try (PlayerActionScopes.Scope scope = PlayerActionScopes.openSteered(entity)) {
            original.call(entity);
        }
    }
}
