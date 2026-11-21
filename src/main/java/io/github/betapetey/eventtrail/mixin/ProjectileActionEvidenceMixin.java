package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.NeoForgeProjectileActionAuditBridge;
import io.github.betapetey.eventtrail.events.platform.ProjectileActionEvidenceCarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Holds bounded runtime-only proof for a projectile created by a player packet action. */
@Mixin(Projectile.class)
abstract class ProjectileActionEvidenceMixin implements ProjectileActionEvidenceCarrier {
    @Unique
    private UUID eventtrail$actionLauncherId;

    @Inject(
            method = "setOwner(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("TAIL"),
            require = 1)
    private void eventtrail$observeOwnerAssignment(Entity owner, CallbackInfo callback) {
        NeoForgeProjectileActionAuditBridge.observeOwnerAssignment(
                (Projectile) (Object) this,
                owner);
    }

    @Override
    public UUID eventtrail$getActionLauncherId() {
        return eventtrail$actionLauncherId;
    }

    @Override
    public void eventtrail$setActionLauncherId(UUID launcherId) {
        this.eventtrail$actionLauncherId = launcherId;
    }
}
