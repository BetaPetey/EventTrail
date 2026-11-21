package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.OrientedContraptionEntity", remap = false)
public abstract class CreateOrientedMotionMixin {
    @Shadow public float yaw;
    @Shadow public float pitch;
    @Shadow public abstract float getInitialYaw();
    @Unique private float eventtrail$initial() {
        try { return getInitialYaw(); }
        catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
    }
    @Unique private void eventtrail$observe(float y, float p, float initial) {
        try {
            if ((y != yaw || p != pitch || initial != getInitialYaw()) && !((OrientedContraptionEntity)(Object)this).level().isClientSide())
                ((MovingStorageMotion)this).eventtrail$movingStorageEpoch().changed();
        } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
    }
    /**
     * A failing epoch producer fails closed: invalidate before the failure goes on to the guard, which reports it
     * for CREATE (a missing member switches CREATE off). Never throws.
     */
    @Unique private void eventtrail$failClosed() {
        try { ((MovingStorageMotion)this).eventtrail$movingStorageEpoch().invalidate(); }
        catch (Throwable ignored) { /* the producer's own failure is the one reported; never replace it */ }
    }
    @WrapMethod(method = "startAtYaw", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$start(float value, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateOrientedMotionMixin.start", original, call -> {
            float y, p;
            try { y = yaw; p = pitch; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            float initial = eventtrail$initial();
            try { call.call(); } finally { eventtrail$observe(y, p, initial); }
        }, value);
    }
    @WrapMethod(method = "setInitialOrientation", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$initial(Direction value, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateOrientedMotionMixin.initial", original, call -> {
            float y, p;
            try { y = yaw; p = pitch; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            float initial = eventtrail$initial();
            try { call.call(); } finally { eventtrail$observe(y, p, initial); }
        }, value);
    }
    @WrapMethod(method = "updateOrientation", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$update(boolean locked, boolean stalled, Entity riding, boolean coupled, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.CREATE, "hook:CreateOrientedMotionMixin.update", original, call -> {
            float y, p;
            try { y = yaw; p = pitch; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            float initial = eventtrail$initial();
            try { call.call(); } finally { eventtrail$observe(y, p, initial); }
        }, locked, stalled, riding, coupled);
    }
    @WrapMethod(method = "readAdditional", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$read(CompoundTag data, boolean spawn, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateOrientedMotionMixin.read", original, call -> {
            float y, p;
            try { y = yaw; p = pitch; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            float initial = eventtrail$initial();
            try { call.call(); } finally { eventtrail$observe(y, p, initial); }
        }, data, spawn);
    }
    @WrapMethod(method = "handleStallInformation", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$stall(double x, double y, double z, float angle, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateOrientedMotionMixin.stall", original, call -> {
            float y0, p;
            try { y0 = yaw; p = pitch; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            float initial = eventtrail$initial();
            try { call.call(); } finally { eventtrail$observe(y0, p, initial); }
        }, x, y, z, angle);
    }
}
