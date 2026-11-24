package io.github.betapetey.eventtrail.events.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.betapetey.eventtrail.events.evidence.BlockIdentity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.DataMapHooks;

/**
 * GF-SCOPE: what one position's first preimage and its state at scope close mean (plain rules, no world access).
 *
 * <ul>
 *   <li>A Q38 block (either side), the same state, or air to air: nothing.</li>
 *   <li>The same Block (GF-BLOCKIDENTITY "same Block, no row"), with one exception (Owner, R2-1): a right-click
 *       harvest that resets the same Block to a lower {@code age}, or picks its berries ({@code berries} true to
 *       false, lead ruling 2: cave vines), in a real player's use scope, with produce dropped or handed over for that
 *       position during the call, is a break row for the ripe crop and a place row for the reset crop. A crop (an
 *       {@code age} or {@code berries} property) picked or reset that fails a guard writes nothing and is counted;
 *       any other same-Block change is no row. A second exception (p2gf-4 #5, Q12): a fluid source put into or taken
 *       out of a waterloggable block is a place or break row of the fluid; review-01 MINOR-4 extends it to any fluid
 *       the block's fluid state shows put in (empty to a source) or taken out (a source to empty), and counts any
 *       other same-Block fluid change. A third exception (R2-3, lead rulings review 2): a real player's break of a
 *       double slab that leaves one slab of the same Block at that position (KleeSlabs) is a break row of one slab.</li>
 *   <li>A vanilla block family: nothing. The families are the click families (cauldron fills, flower pots, cake and
 *       candle cake, copper weathering and wax; a cake eaten to air in a use scope) and the shape-update conversions
 *       next to a change (review-01 MAJOR-2: a growing plant's head and body, big dripleaf to its stem, bamboo sapling
 *       to bamboo, concrete powder to its concrete). A pumpkin carved with shears is his own click, not a shape
 *       update, so it stays a swap row (brief r2 item 2).</li>
 *   <li>A block or fluid to air, or a block to the very fluid it held (review-02 MAJOR-1: seagrass, a waterlogged
 *       ladder): a break row of the old block. Air to a block or fluid: a place row of the new
 *       block (Q12 fluids are place and break rows).</li>
 *   <li>A head and body of growing plants that are not one proven vanilla pair, or concrete powder whose concrete
 *       cannot be read, to another block: no row, counted (the conversion cannot be told from his change).</li>
 *   <li>One block or fluid to another: one place row of the new block that carries the old one (Q21 swap).</li>
 * </ul>
 */
final class PlayerActionScopeRules {
    /** What one changed position writes. */
    enum Outcome {
        NONE,
        BREAK,
        PLACE,
        /** Q21: one place row of the new block, carrying the old block. */
        SWAP,
        /** R2-1: a break row of the ripe crop and a place row of the reset crop. */
        HARVEST,
        /** Right-click harvest refused: a crop (age or berries) picked or reset that failed a guard; no row, counted. */
        HARVEST_REFUSED,
        /** p2gf-4 #5 (Q12), MINOR-4: a fluid source put into a block (waterlogged or any fluid): a place row of it. */
        FLUID_PLACE,
        /** p2gf-4 #5 (Q12), MINOR-4: a fluid source taken out of a block: a break row of the fluid. */
        FLUID_BREAK,
        /**
         * R2-3 (lead rulings, review 2): a real player's break of a double slab left one slab of the same Block at
         * that position: one break row of the slab removed.
         */
        SLAB_HALF_BREAK,
        /**
         * The change cannot be proven his (an unproven plant or concrete conversion, or a same-Block fluid change
         * that is not a source put in or taken out): no row, counted.
         */
        UNPROVEN
    }

    /**
     * The kinds of scope. USE and USE_ON are the player's use scopes. GRAVE_SNEAK (graves design r6, 4.1; Q185) is a
     * sneak take from a grave: no use scope, so it takes no inventory snapshot and is never a withdrawal candidate.
     */
    enum Kind {
        USE,
        USE_ON,
        PLAYER_ACTION,
        DESTROY,
        INTERACT,
        STEERED,
        GRAVE_SNEAK;

        boolean isUse() {
            return this == USE || this == USE_ON;
        }
    }

    private PlayerActionScopeRules() {
    }

    /** A position no real player's destroyBlock of it wrote (see the five-argument form). */
    static Outcome classify(BlockState before, BlockState after, Kind kind, boolean produce) {
        return classify(before, after, kind, produce, false);
    }

    /**
     * @param produce       whether produce was dropped in this position's cell or handed over for this position
     *                      during the call (only asked for a harvest candidate)
     * @param destroyTarget whether the position was written while a real player's destroyBlock of that very position
     *                      ran (R2-3; only asked for a double slab left single)
     */
    static Outcome classify(BlockState before, BlockState after, Kind kind, boolean produce, boolean destroyTarget) {
        if (before == null || after == null || before == after) return Outcome.NONE;
        if (BlockIdentity.excluded(before) || BlockIdentity.excluded(after)) return Outcome.NONE;
        boolean beforeEmpty = before.isAir();
        boolean afterEmpty = after.isAir();
        if (beforeEmpty && afterEmpty) return Outcome.NONE;
        if (!BlockIdentity.blockDiffers(before, after)) {
            // R2-3: KleeSlabs cancels his break of one half of a double slab and sets the single slab of the same
            // Block; vanilla never does this inside destroyBlock, so vanilla breaks are unaffected.
            if (destroyTarget && slabHalfBroken(before, after)) return Outcome.SLAB_HALF_BREAK;
            Boolean filled = waterlogging(before, after);
            if (filled != null) return filled ? Outcome.FLUID_PLACE : Outcome.FLUID_BREAK;
            // Review-02 NIT 2: a WATERLOGGED change with no source on either side (vanilla never makes one; a mod's
            // flowing waterlogging could) is counted, never silent.
            if (waterloggedChanged(before, after)) return Outcome.UNPROVEN;
            // Review-01 MINOR-4: the same rule for any fluid logged into or out of a block by another property than
            // WATERLOGGED (whose path above is unchanged).
            Outcome fluid = fluidChange(before.getFluidState(), after.getFluidState());
            if (fluid == Outcome.FLUID_PLACE && fluidSource(after).isAir()) return Outcome.UNPROVEN;
            if (fluid == Outcome.FLUID_BREAK && fluidSource(before).isAir()) return Outcome.UNPROVEN;
            if (fluid != null) return fluid;
            if (!lowerAge(before, after) && !berriesPicked(before, after)) {
                // Review-01 MINOR-3: only a crop with produce and no growth is a refused right-click harvest; any
                // other same-Block change (a bookshelf, a beehive, a composter) is no row.
                return produce && crop(before) && !grown(before, after) ? Outcome.HARVEST_REFUSED : Outcome.NONE;
            }
            return kind != null && kind.isUse() && produce ? Outcome.HARVEST : Outcome.HARVEST_REFUSED;
        }
        if (sameFamily(before, after, kind)) return Outcome.NONE;
        if (afterEmpty) return Outcome.BREAK;
        if (beforeEmpty) return Outcome.PLACE;
        if (unprovenConversion(before.getBlock(), after.getBlock())) return Outcome.UNPROVEN;
        // Review-02 MAJOR-1: a block replaced by the fluid it held (seagrass or a waterlogged ladder popped off when
        // its support went, the other half of tall seagrass; Level#destroyBlock and #removeBlock set
        // fluidstate.createLegacyBlock()) is a break of that block, never his place of the fluid. Narrow on purpose:
        // a block holding no fluid (a water bucket on short grass) and any other block put there (water to ice)
        // stay a swap.
        if (fluidLeftBehind(before, after)) return Outcome.BREAK;
        return Outcome.SWAP;
    }

    /** Review-02 MAJOR-1: {@code after} is exactly the fluid {@code before} held, as its own block. */
    static boolean fluidLeftBehind(BlockState before, BlockState after) {
        return !before.getFluidState().isEmpty() && after == before.getFluidState().createLegacyBlock();
    }

    /**
     * Review-02 NIT 4: a liquid block's own level change (the same LiquidBlock, for example a water bucket emptied
     * into flowing water). It stays UNPROVEN and counted; only its log line is at debug level.
     */
    static boolean liquidLevelChange(BlockState before, BlockState after) {
        return before != null && after != null && before.getBlock() == after.getBlock()
                && before.getBlock() instanceof LiquidBlock && before != after;
    }

    /** Lead ruling 2: the same Block with {@code berries} true before and false after (never false to true). */
    static boolean berriesPicked(BlockState before, BlockState after) {
        if (before == null || after == null || before.getBlock() != after.getBlock()
                || !before.hasProperty(BlockStateProperties.BERRIES)
                || !after.hasProperty(BlockStateProperties.BERRIES)) return false;
        return before.getValue(BlockStateProperties.BERRIES) && !after.getValue(BlockStateProperties.BERRIES);
    }

    /** A crop for the right-click harvest rule: an {@code age} or a {@code berries} property. */
    static boolean crop(BlockState state) {
        return state != null && (ageProperty(state) != null || state.hasProperty(BlockStateProperties.BERRIES));
    }

    /** Growth: a higher age, or berries false to true (bone meal or a tick); never a harvest. */
    static boolean grown(BlockState before, BlockState after) {
        return higherAge(before, after) || berriesPicked(after, before);
    }

    /**
     * The second same-Block exception (p2gf-4 #5, Q12): the same Block whose {@code waterlogged} value changed and
     * whose fluid became or stopped being a source. True: a source was put in; false: a source was taken out; null:
     * not a waterlogging change.
     */
    static Boolean waterlogging(BlockState before, BlockState after) {
        if (before == null || after == null || before.getBlock() != after.getBlock()
                || !before.hasProperty(BlockStateProperties.WATERLOGGED)
                || !after.hasProperty(BlockStateProperties.WATERLOGGED)) return null;
        Outcome outcome = waterloggedChange(before.getValue(BlockStateProperties.WATERLOGGED),
                after.getValue(BlockStateProperties.WATERLOGGED), before.getFluidState(), after.getFluidState());
        if (outcome == Outcome.FLUID_PLACE) return Boolean.TRUE;
        if (outcome == Outcome.FLUID_BREAK) return Boolean.FALSE;
        return null;
    }

    /**
     * The waterlogging rule on its plain values: null when {@code waterlogged} did not change; FLUID_PLACE when it
     * became true with a source after; FLUID_BREAK when it was true with a source before; else UNPROVEN (review-02
     * NIT 2: no source on either side, counted).
     */
    static Outcome waterloggedChange(boolean was, boolean is, FluidState before, FluidState after) {
        if (was == is) return null;
        if (is && after.isSource()) return Outcome.FLUID_PLACE;
        if (was && before.isSource()) return Outcome.FLUID_BREAK;
        return Outcome.UNPROVEN;
    }

    /** Whether both states have {@code waterlogged} and its value differs (the waterlogging path's own case). */
    static boolean waterloggedChanged(BlockState before, BlockState after) {
        return before.hasProperty(BlockStateProperties.WATERLOGGED) && after.hasProperty(BlockStateProperties.WATERLOGGED)
                && before.getValue(BlockStateProperties.WATERLOGGED) != after.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Review-01 MINOR-4: the fluid a same-Block change put in or took out, read from the two fluid states alone (no
     * mod jar is needed). Null: the fluid did not change. FLUID_PLACE: empty to a source. FLUID_BREAK: a source to
     * empty. UNPROVEN: any other fluid change (a flowing fluid either side, one source for another), which cannot be
     * told from his own act: no row, counted.
     */
    static Outcome fluidChange(FluidState before, FluidState after) {
        if (before == null || after == null || before == after) return null;
        if (before.isEmpty() && after.isEmpty()) return null;
        if (before.isEmpty() && after.isSource()) return Outcome.FLUID_PLACE;
        if (before.isSource() && after.isEmpty()) return Outcome.FLUID_BREAK;
        return Outcome.UNPROVEN;
    }

    /**
     * The fluid source a state holds, as its own block (water for a waterlogged block; a mod fluid's own block, or
     * air when the fluid has none, which the rules count instead of writing).
     */
    static BlockState fluidSource(BlockState holder) {
        return holder.getFluidState().createLegacyBlock();
    }

    /** The row detail's word for the block a fluid is in: "waterlogged" on the waterlogging path, else "fluidlogged". */
    static String fluidHolder(BlockState before, BlockState after) {
        return waterlogging(before, after) != null ? "waterlogged" : "fluidlogged";
    }

    /**
     * R2-3: the same {@link SlabBlock} with its slab type going from double to bottom or top (one half gone).
     */
    static boolean slabHalfBroken(BlockState before, BlockState after) {
        if (before == null || after == null || before.getBlock() != after.getBlock()
                || !(before.getBlock() instanceof SlabBlock)
                || !before.hasProperty(SlabBlock.TYPE) || !after.hasProperty(SlabBlock.TYPE)) return false;
        SlabType left = after.getValue(SlabBlock.TYPE);
        return before.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && (left == SlabType.BOTTOM || left == SlabType.TOP);
    }

    /** R2-3: the one slab removed from a double slab that left {@code after}: the half that is not there now. */
    static BlockState brokenSlab(BlockState before, BlockState after) {
        SlabType removed = after.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? SlabType.TOP : SlabType.BOTTOM;
        return before.setValue(SlabBlock.TYPE, removed);
    }

    /** The same Block with an {@code age} property that is lower after than before. */
    static boolean lowerAge(BlockState before, BlockState after) {
        if (before == null || after == null || before.getBlock() != after.getBlock()) return false;
        IntegerProperty age = ageProperty(before);
        if (age == null || !after.hasProperty(age)) return false;
        return after.getValue(age) < before.getValue(age);
    }

    /** Growth: the same Block with a higher {@code age} (never a harvest; bone meal growth writes no row). */
    static boolean higherAge(BlockState before, BlockState after) {
        if (before == null || after == null || before.getBlock() != after.getBlock()) return false;
        IntegerProperty age = ageProperty(before);
        if (age == null || !after.hasProperty(age)) return false;
        return after.getValue(age) > before.getValue(age);
    }

    /** The block's integer {@code age} property, or null. */
    static IntegerProperty ageProperty(BlockState state) {
        if (state == null) return null;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && "age".equals(property.getName())) return integer;
        }
        return null;
    }

    /**
     * The vanilla families an ordinary click changes without a different block being put down (plan review), and the
     * vanilla shape-update conversions a change next to them causes (review-01 MAJOR-2). Each conversion is bound
     * only to the block the vanilla code converts to.
     */
    static boolean sameFamily(BlockState before, BlockState after, Kind kind) {
        Block a = before.getBlock();
        Block b = after.getBlock();
        if (a instanceof AbstractCauldronBlock && b instanceof AbstractCauldronBlock) return true;
        if (a instanceof FlowerPotBlock && b instanceof FlowerPotBlock) return true;
        if (cake(a) && cake(b)) return true;
        if (cake(a) && after.isAir() && kind != null && kind.isUse()) return true;
        // Pumpkin to carved pumpkin is not here: PumpkinBlock#useItemOn sets it on his own shears click (no shape
        // update), so it stays his swap row (brief r2 item 2; NOTES).
        // GrowingPlantHeadBlock/GrowingPlantBodyBlock#updateShape: a head becomes its body and a body its head.
        if (growingPlantPair(a, b) || growingPlantPair(b, a)) return true;
        // BigDripleafBlock#updateShape: a leaf with a leaf put on top becomes Blocks.BIG_DRIPLEAF_STEM.
        if (a instanceof BigDripleafBlock && b == Blocks.BIG_DRIPLEAF_STEM) return true;
        // BambooSaplingBlock#updateShape: a sapling with bamboo on top becomes Blocks.BAMBOO.
        if (a instanceof BambooSaplingBlock && b == Blocks.BAMBOO) return true;
        // ConcretePowderBlock#updateShape: powder touching water becomes its own concrete.
        if (a instanceof ConcretePowderBlock powder && b != null && b == concreteOf(powder)) return true;
        return copperBase(a) != null && copperBase(a) == copperBase(b);
    }

    /** The four vanilla growing plants, head then body (GrowingPlantHeadBlock#getBodyBlock is protected). */
    private static boolean growingPlantPair(Block head, Block body) {
        return (head == Blocks.KELP && body == Blocks.KELP_PLANT)
                || (head == Blocks.TWISTING_VINES && body == Blocks.TWISTING_VINES_PLANT)
                || (head == Blocks.WEEPING_VINES && body == Blocks.WEEPING_VINES_PLANT)
                || (head == Blocks.CAVE_VINES && body == Blocks.CAVE_VINES_PLANT);
    }

    /**
     * A conversion that may be a shape update but cannot be proven one: a head and a body of growing plants that are
     * not a vanilla pair (a mod plant's pair is protected), or concrete powder whose concrete cannot be read. Such a
     * change is neither written as his place nor dropped silently: no row, counted.
     */
    static boolean unprovenConversion(Block a, Block b) {
        if (a instanceof GrowingPlantHeadBlock && b instanceof GrowingPlantBodyBlock) return true;
        if (a instanceof GrowingPlantBodyBlock && b instanceof GrowingPlantHeadBlock) return true;
        return a instanceof ConcretePowderBlock powder && concreteOf(powder) == null;
    }

    /** The concrete a powder turns into, read through the block's own codec field "concrete"; null if unreadable. */
    static Block concreteOf(ConcretePowderBlock powder) {
        try {
            JsonElement encoded = ConcretePowderBlock.CODEC.codec().encodeStart(JsonOps.INSTANCE, powder)
                    .result().orElse(null);
            if (!(encoded instanceof JsonObject object) || !(object.get("concrete") instanceof JsonElement id)
                    || !id.isJsonPrimitive()) return null;
            ResourceLocation key = ResourceLocation.tryParse(id.getAsString());
            if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return null;
            Block concrete = BuiltInRegistries.BLOCK.get(key);
            return concrete == Blocks.AIR ? null : concrete;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static boolean cake(Block block) {
        return block instanceof CakeBlock || block instanceof CandleCakeBlock;
    }

    /**
     * The unwaxed, unweathered base of a copper-family block, or null when the block is in no wax or weathering
     * table. NeoForge's data maps answer first (mod copper too); the vanilla tables answer when the data maps are
     * not loaded.
     */
    @SuppressWarnings("deprecation")
    static Block copperBase(Block block) {
        if (block == null) return null;
        Block unwaxed = orElse(() -> DataMapHooks.getBlockUnwaxed(block),
                () -> HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block));
        if (unwaxed == null) unwaxed = block;
        Block base = unwaxed;
        for (int step = 0; step < 16; step++) {
            Block current = base;
            Block previous = orElse(() -> DataMapHooks.getPreviousOxidizedStage(current),
                    () -> WeatheringCopper.PREVIOUS_BY_BLOCK.get().get(current));
            if (previous == null || previous == base) break;
            base = previous;
        }
        Block plain = unwaxed;
        boolean copper = unwaxed != block || base != unwaxed
                || orElse(() -> DataMapHooks.getBlockWaxed(plain), () -> HoneycombItem.WAXABLES.get().get(plain)) != null
                || orElse(() -> DataMapHooks.getNextOxidizedStage(plain),
                        () -> WeatheringCopper.NEXT_BY_BLOCK.get().get(plain)) != null;
        return copper ? base : null;
    }

    /** NeoForge's data-map answer, or the vanilla table's when the data maps give none or cannot be read. */
    private static Block orElse(java.util.function.Supplier<Block> dataMap, java.util.function.Supplier<Block> vanilla) {
        Block found;
        try {
            found = dataMap.get();
        } catch (RuntimeException | LinkageError unreadable) {
            found = null;
        }
        return found != null ? found : vanilla.get();
    }
}
