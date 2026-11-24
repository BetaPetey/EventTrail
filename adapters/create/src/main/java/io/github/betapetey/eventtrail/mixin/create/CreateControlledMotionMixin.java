package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class CreateControlledMotionMixin {
    @Shadow protected float angle;
    @Shadow protected Direction.Axis rotationAxis;
    @Unique private void eventtrail$observe(float previous, Direction.Axis axis) {
        try {
            if ((previous != angle || axis != rotationAxis) && !((ControlledContraptionEntity)(Object)this).level().isClientSide())
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
    @WrapMethod(method = "setAngle", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$angle(float value, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateControlledMotionMixin.angle", original, call -> {
            float before; Direction.Axis axis;
            try { before = angle; axis = rotationAxis; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); } finally { eventtrail$observe(before, axis); }
        }, value);
    }
    @WrapMethod(method = "setRotationAxis", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$axis(Direction.Axis value, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateControlledMotionMixin.axis", original, call -> {
            float before; Direction.Axis axis;
            try { before = angle; axis = rotationAxis; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); } finally { eventtrail$observe(before, axis); }
        }, value);
    }
    @WrapMethod(method = "readAdditional", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$read(CompoundTag data, boolean spawn, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateControlledMotionMixin.read", original, call -> {
            float before; Direction.Axis axis;
            try { before = angle; axis = rotationAxis; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); } finally { eventtrail$observe(before, axis); }
        }, data, spawn);
    }
    @WrapMethod(method = "handleStallInformation", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$stall(double x, double y, double z, float value, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateControlledMotionMixin.stall", original, call -> {
            float before; Direction.Axis axis;
            try { before = angle; axis = rotationAxis; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); } finally { eventtrail$observe(before, axis); }
        }, x, y, z, value);
    }
}
