package io.github.betapetey.eventtrail.events.platform;

/**
 * Item 2 F4 option A (KL-4): the one bit FramedSourceCarrierMixin adds to every {@code DamageSource}: this source
 * object was used, at some hurt step, under a frame whose rule would withhold a death by it ({@link FramedSources}).
 * Set once and never cleared; nothing else is kept.
 */
public interface FramedSourceCarrier {
    boolean eventtrail$framedSource();

    void eventtrail$markFramedSource();
}
