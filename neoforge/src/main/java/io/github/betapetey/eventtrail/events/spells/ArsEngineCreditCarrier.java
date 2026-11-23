package io.github.betapetey.eventtrail.events.spells;

/** Ars-specific runtime exclusion on the exact victim, independent of other mods. */
public interface ArsEngineCreditCarrier {
    boolean eventtrail$arsEngineCreditExcluded();

    void eventtrail$setArsEngineCreditExcluded(boolean excluded);
}
