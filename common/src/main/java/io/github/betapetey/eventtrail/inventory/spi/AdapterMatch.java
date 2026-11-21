package io.github.betapetey.eventtrail.inventory.spi;

/** Deterministic result of asking an adapter whether it owns a menu. */
public enum AdapterMatch {
    NO_MATCH,
    SUPPORTED,
    UNSUPPORTED_VERSION,
    AMBIGUOUS
}
