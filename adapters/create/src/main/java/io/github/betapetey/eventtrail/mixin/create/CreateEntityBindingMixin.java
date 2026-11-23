package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateEntityCarrier;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOrientedPose;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.AbstractContraptionEntity", remap = false)
public abstract class CreateEntityBindingMixin implements CreateEntityCarrier {
    @Shadow protected Contraption contraption;
    @Unique private MovingStorageEpoch eventtrail$epoch;
    @Unique private long eventtrail$context;
    public MovingStorageEpoch eventtrail$movingStorageEpoch() {
        if (eventtrail$epoch == null) eventtrail$epoch = new MovingStorageEpoch();
        return eventtrail$epoch;
    }
    public long eventtrail$createContextEpoch() { return eventtrail$context; }
    public void eventtrail$createContextChanged() {
        if (eventtrail$context == Long.MAX_VALUE) eventtrail$movingStorageEpoch().invalidate();
        else eventtrail$context++;
        eventtrail$movingStorageEpoch().changed();
    }
    @WrapMethod(method = "setContraption", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$replace(Contraption next, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateEntityBindingMixin.replace", original, call -> {
            Contraption before;
            try { before = contraption; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); }
            finally { eventtrail$contextAfter(before); }
        }, next);
    }
    @WrapMethod(method = "readAdditional", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$read(CompoundTag data, boolean spawn, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateEntityBindingMixin.read", original, call -> {
            Contraption before;
            try { before = contraption; } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
            try { call.call(); }
            finally { eventtrail$contextAfter(before); }
        }, data, spawn);
    }
    /**
     * Rotation written inside the tick by a subclass's own tickContraption (the Bits 'n' Bobs chain carriage's
     * updateYawFromChain, Part 1 SA-create-mounted) changes the moving-storage epoch; non-oriented entities just tick.
     */
    @WrapMethod(method = "tick", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$tick(Operation<Void> original) {
        HookGuard.wrap(AdapterKey.CREATE, "hook:CreateEntityBindingMixin.tick", original,
                call -> CreateOrientedPose.around(this, call::call));
    }
    @Inject(method = "disassemble", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$disassemble(CallbackInfo ci) {
        HookGuard.observe(AdapterKey.CREATE, "hook:CreateEntityBindingMixin.disassemble", () -> eventtrail$retire());
    }
    @Inject(method = "remove", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$remove(Entity.RemovalReason reason, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.CREATE, "hook:CreateEntityBindingMixin.remove", () -> eventtrail$retire());
    }
    @Unique private void eventtrail$retire() {
        try {
            if (!((AbstractContraptionEntity)(Object)this).level().isClientSide()) {
                eventtrail$createContextChanged(); eventtrail$movingStorageEpoch().invalidate();
            }
        } catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
    }
    /** After the mod's call: a replaced contraption is a context change; a failure here fails closed. */
    @Unique private void eventtrail$contextAfter(Contraption before) {
        try { if (before != contraption) eventtrail$createContextChanged(); }
        catch (Throwable failure) { eventtrail$failClosed(); throw failure; }
    }
    /**
     * A failing epoch producer fails closed: invalidate before the failure goes on to the guard, which reports it
     * for CREATE (a missing member switches CREATE off). Never throws.
     */
    @Unique private void eventtrail$failClosed() {
        try { eventtrail$movingStorageEpoch().invalidate(); }
        catch (Throwable ignored) { /* the producer's own failure is the one reported; never replace it */ }
    }
}
