package io.github.betapetey.eventtrail;

/**
 * EventTrail's fixed identifiers. This class holds the mod id and has no Minecraft or NeoForge imports, so code can read
 * it without a compile-time dependency on the mod entry point. {@code EventTrailMod.MOD_ID} is defined from this
 * constant.
 */
public final class EventTrailIds {
    public static final String MOD_ID = "eventtrail";

    private EventTrailIds() {
    }
}
