package io.github.betapetey.eventtrail.inventory.adapters.corpse;

import io.github.betapetey.eventtrail.adapters.AdapterKey;

/**
 * Corpse's grave key-spec: pure constants. It names no class of the mod and holds no hook; the mod's owner keys,
 * contents keys and entity ids join it from the mod's own jar with its grave adapter, and its hooks go in
 * {@code mixin.corpse}.
 */
public final class CorpseGraveSpec {
    /** The grave adapter's key: its fail-safe, its counted reasons, its status line and its parenthesised name. */
    public static final AdapterKey KEY = AdapterKey.CORPSE;
    /** The mod's id: the namespace of its registry ids and of its take rows' provider id. */
    public static final String MOD_ID = "corpse";

    private CorpseGraveSpec() {
    }
}
