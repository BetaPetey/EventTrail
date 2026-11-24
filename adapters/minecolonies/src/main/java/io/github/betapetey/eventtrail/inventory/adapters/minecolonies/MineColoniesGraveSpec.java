package io.github.betapetey.eventtrail.inventory.adapters.minecolonies;

import io.github.betapetey.eventtrail.adapters.AdapterKey;

/**
 * MineColonies' colony grave key-spec: pure constants. It names no class of the mod and holds no hook; the mod's
 * owner keys, contents keys and block ids join it from the mod's own jar with its grave adapter, and its hooks go in
 * {@code mixin.minecolonies}. Colony grave rows name the colony's owner and show no parenthesised mod name.
 */
public final class MineColoniesGraveSpec {
    /** The grave adapter's key: its fail-safe, its counted reasons and its status line. */
    public static final AdapterKey KEY = AdapterKey.MINECOLONIES;
    /** The mod's id: the namespace of its registry ids and of its take rows' provider id. */
    public static final String MOD_ID = "minecolonies";

    private MineColoniesGraveSpec() {
    }
}
