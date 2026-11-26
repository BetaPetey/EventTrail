package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.events.platform.FramedSourceCarrier;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Item 2 F4 option A (KL-4): the one "first used under a frame" bit on every damage source object. Set once by
 * FramedSources at a hurt step, never cleared, never saved.
 */
@Mixin(DamageSource.class)
abstract class FramedSourceCarrierMixin implements FramedSourceCarrier {
    @Unique
    private boolean eventtrail$framed;

    @Override
    public boolean eventtrail$framedSource() {
        return eventtrail$framed;
    }

    @Override
    public void eventtrail$markFramedSource() {
        eventtrail$framed = true;
    }
}
