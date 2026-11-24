package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateEntityCarrier;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No registry or scanning: only exact optional entity markers observe their native setters.
 *
 * <p>These setters run for every entity, so each handler first returns unless the entity carries an EventTrail
 * moving-storage marker (an EventTrail interface test, no mod member). The guard key is the adapter whose epoch it
 * is: a Create contraption entity (it carries {@link CreateEntityCarrier}) is CREATE, any other marked entity is a
 * Storage in Motion cart or boat.</p>
 *
 * <p>A producer failure fails closed: every guarded body invalidates the entity's epoch on any failure before the
 * failure reaches the guard, so an epoch that may have missed a change can never keep an access guard open.</p>
 */
@Mixin(Entity.class)
abstract class MovingStorageEntityMotionMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite
    private void eventtrail$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!(this instanceof MovingStorageMotion motion)) return;
        HookGuard.observe(motion instanceof CreateEntityCarrier ? AdapterKey.CREATE : AdapterKey.STORAGE_IN_MOTION,
                "hook:MovingStorageEntityMotionMixin.removed", () -> {
                    try {
                        motion.eventtrail$movingStorageEpoch().invalidate();
                    } catch (Throwable failure) {
                        eventtrail$failClosed(motion);
                        throw failure;
                    }
                });
    }

    @Inject(method = "setPosRaw", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite
    private void eventtrail$position(double x, double y, double z, CallbackInfo ci) {
        if (!(this instanceof MovingStorageMotion motion)) return;
        HookGuard.observe(motion instanceof CreateEntityCarrier ? AdapterKey.CREATE : AdapterKey.STORAGE_IN_MOTION,
                "hook:MovingStorageEntityMotionMixin.position", () -> {
                    try {
                        Entity entity = (Entity) (Object) this;
                        if (entity.level() != null && !entity.level().isClientSide
                                && (entity.getX() != x || entity.getY() != y || entity.getZ() != z)) {
                            motion.eventtrail$movingStorageEpoch().changed();
                        }
                    } catch (Throwable failure) {
                        eventtrail$failClosed(motion);
                        throw failure;
                    }
                });
    }

    @Inject(method = "setYRot", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite
    private void eventtrail$yaw(float yaw, CallbackInfo ci) {
        if (!(this instanceof MovingStorageMotion motion)) return;
        HookGuard.observe(motion instanceof CreateEntityCarrier ? AdapterKey.CREATE : AdapterKey.STORAGE_IN_MOTION,
                "hook:MovingStorageEntityMotionMixin.yaw", () -> {
                    try {
                        Entity entity = (Entity) (Object) this;
                        if (entity.level() != null && !entity.level().isClientSide && entity.getYRot() != yaw) {
                            motion.eventtrail$movingStorageEpoch().changed();
                        }
                    } catch (Throwable failure) {
                        eventtrail$failClosed(motion);
                        throw failure;
                    }
                });
    }

    @Inject(method = "setXRot", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite
    private void eventtrail$pitch(float pitch, CallbackInfo ci) {
        if (!(this instanceof MovingStorageMotion motion)) return;
        HookGuard.observe(motion instanceof CreateEntityCarrier ? AdapterKey.CREATE : AdapterKey.STORAGE_IN_MOTION,
                "hook:MovingStorageEntityMotionMixin.pitch", () -> {
                    try {
                        Entity entity = (Entity) (Object) this;
                        if (entity.level() != null && !entity.level().isClientSide && entity.getXRot() != pitch) {
                            motion.eventtrail$movingStorageEpoch().changed();
                        }
                    } catch (Throwable failure) {
                        eventtrail$failClosed(motion);
                        throw failure;
                    }
                });
    }

    /**
     * Invalidates the entity's epoch after its producer failed, before the failure goes on to the guard. It never
     * throws, so the guard still reports the original failure.
     */
    @Unique
    private static void eventtrail$failClosed(MovingStorageMotion motion) {
        try {
            motion.eventtrail$movingStorageEpoch().invalidate();
        } catch (Throwable ignored) {
            // The guard reports the producer's own failure; this invalidation must never replace it.
        }
    }
}
