package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * GF-BLOCKIDENTITY: the one rule the break, place and explosion paths share for "a real block change", and the
 * Owner's Q38 list of blocks that never get a break, place or explosion row.
 *
 * <p>A real change is a different Block before and after (air, a fluid or another block all reduce to that). The same
 * Block with other properties (a door opened, a crop or crystal one stage lower, an existing altar block rewritten) is
 * not a block placed or removed. The placement path keeps one exception of its own: the clicked snapshot that adds the
 * held block to itself (slab on slab, a second candle).</p>
 *
 * <p>Q38 (Owner, 2026-11-14): "Blocks an effect removes again by itself (temporary bridge blocks, flare light, frosted
 * ice) get no place rows", applied by every scope. The list is keyed by registry id, never by a mod class, and holds
 * only ids proven from the game's own registry or the mod jar's own bytes.</p>
 */
public final class BlockIdentity {
    /**
     * Q38 excluded blocks by registry id: vanilla frosted ice (the game's own registry) and the Relics Phantom Boot
     * bridge block (proven from the Relics jars' own block registration bytes). The Immersive Engineering flare's light
     * block is not listed: in 12.4.2-194 the flare writes vanilla fire and places no light block, so there is nothing
     * to exclude.
     */
    public static final Set<ResourceLocation> EXCLUDED_BLOCK_IDS = Set.of(
            ResourceLocation.withDefaultNamespace("frosted_ice"),
            ResourceLocation.fromNamespaceAndPath("relics", "phantom_block"));

    private BlockIdentity() {
    }

    /** True when the Block itself changed; false for a property-only change or when either state is unknown. */
    public static boolean blockDiffers(BlockState before, BlockState after) {
        return before != null && after != null && before.getBlock() != after.getBlock();
    }

    /** True for a block on the Q38 list: it gets no break, place or explosion row. */
    public static boolean excluded(BlockState state) {
        return state != null && excluded(state.getBlock());
    }

    /** True for a block on the Q38 list: it gets no break, place or explosion row. */
    public static boolean excluded(Block block) {
        return block != null && EXCLUDED_BLOCK_IDS.contains(BuiltInRegistries.BLOCK.getKey(block));
    }
}
