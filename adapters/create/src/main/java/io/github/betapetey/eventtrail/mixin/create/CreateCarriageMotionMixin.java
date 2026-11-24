package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOrientedPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * The train carriage's rotation writer (Part 1 SA-create-mounted): {@code alignEntity} writes the carriage entity's
 * yaw, pitch, prevYaw and prevPitch (create-1.21.1-6.0.10) and is its only writer of them. Its position goes through
 * setPos, which the moving-storage motion hook already observes. A changed rotation changes the entity's
 * moving-storage epoch.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity", remap = false)
public abstract class CreateCarriageMotionMixin {
    @WrapMethod(method = "alignEntity", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$align(CarriageContraptionEntity entity, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateCarriageMotionMixin.align", original,
                call -> CreateOrientedPose.around(entity, call::call), entity);
    }
}
