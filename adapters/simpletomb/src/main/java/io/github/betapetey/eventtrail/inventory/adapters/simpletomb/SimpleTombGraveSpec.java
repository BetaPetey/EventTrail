package io.github.betapetey.eventtrail.inventory.adapters.simpletomb;

import io.github.betapetey.eventtrail.adapters.AdapterKey;

/**
 * Simple Tombstone's grave key-spec: pure constants. It names no class of the mod and holds no hook; the mod's owner
 * keys, contents keys and block ids join it from the mod's own jar with its grave adapter, and its hooks go in
 * {@code mixin.simpletomb}.
 */
public final class SimpleTombGraveSpec {
    /** The grave adapter's key: its fail-safe, its counted reasons, its status line and its parenthesised name. */
    public static final AdapterKey KEY = AdapterKey.SIMPLE_TOMB;
    /** The mod's id: the namespace of its registry ids and of its take rows' provider id. */
    public static final String MOD_ID = "simpletomb";

    private SimpleTombGraveSpec() {
    }
}
