package io.github.betapetey.eventtrail.events.block;

/** Retained-size and work limits shared by committed block-effect evidence. */
public final class BlockAuditLimits {
    public static final int MAX_BLOCK_ENTITY_METADATA_NBT_BYTES = 64 * 1024;
    public static final int MAX_BLOCK_ENTITY_METADATA_CHARS = 64 * 1024;
    public static final int MAX_BLOCK_ENTITY_METADATA_UTF8_BYTES = 64 * 1024;
    public static final int MAX_PLACEMENT_METADATA_BYTES = 256 * 1024;
    public static final int MAX_PLACEMENT_MEMBERS = 512;
    public static final int MAX_EXPLOSION_EVIDENCE_WORK = 25_000;
    public static final int MAX_NBT_DEPTH = 64;
    public static final int MAX_NBT_NODES = 131_072;

    private BlockAuditLimits() {
    }
}
