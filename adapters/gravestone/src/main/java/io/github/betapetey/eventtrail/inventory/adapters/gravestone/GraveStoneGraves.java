package io.github.betapetey.eventtrail.inventory.adapters.gravestone;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.events.platform.GraveSneakFrame;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.graves.GraveAdapterSpec;
import io.github.betapetey.eventtrail.inventory.graves.GraveContents;
import io.github.betapetey.eventtrail.inventory.graves.GraveOwnerReader;
import io.github.betapetey.eventtrail.inventory.graves.GraveOwners;
import io.github.betapetey.eventtrail.inventory.graves.GraveTakes;
import io.github.betapetey.eventtrail.inventory.graves.SpillTags;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.DisplayNameResolver.RenameState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Gravestone Mod's grave adapter (graves design r6, 2.1): its grave spec for the shared base, and the work of its three
 * block hooks. It names no class of the mod: the hooks hand it vanilla types only, and it reads the grave through the
 * block entity's own saved data with the keys of {@link GraveStoneGraveSpec}.
 *
 * <ul>
 *   <li><b>Take</b> (the mod's hand-over of a grave to a player): a {@link GraveTakes} take around the grave block's
 *   whole player-break method, which hands the grave over and then removes the block (checked against the jar); the
 *   sneak's take is the sneak hook's. The mod empties the grave's lists inside the hand-over, and the removed grave
 *   reads as empty, so the take sees what left the grave; the player's own gain caps what he took. Rows are written
 *   only for the player's own act at the grave (the shared base's step 0b) and never inside a command. An empty
 *   grave (a grave block placed by hand) runs only the mod's call.</li>
 *   <li><b>Spill</b> (each stack the mod drops at the grave): the shared exact call-site tag. The mod hands the drop
 *   helper the stack itself, not a copy, so inside a take the taker's own displaced stacks stay his, untagged.</li>
 *   <li><b>Removal</b> (the grave block replaced by another block): the spill origin is the grave as it was just
 *   before, so what the mod drops then is labelled with the grave and its owner. Inside a command nothing is set.</li>
 * </ul>
 *
 * <p>The mod's own command that restores a death copies items without touching the grave or any of these hooks, so it
 * records nothing (a known limit).</p>
 */
public final class GraveStoneGraves implements GraveAdapterSpec<GraveStoneGraves.Grave> {
    /** The spec the hooks and the shared base use. */
    public static final GraveStoneGraves SPEC = new GraveStoneGraves();
    public static final String TAKE_SITE = "hook:GraveStoneBlockMixin.take";
    public static final String SPILL_SITE = "hook:GraveStoneBlockMixin.spill";
    public static final String REMOVAL_SITE = "hook:GraveStoneBlockMixin.removal";
    /** The mod's drop loop hands the drop helper each stack by reference (checked against the jar): not a copy. */
    public static final boolean SPILL_PASSES_COPY = false;

    private GraveStoneGraves() {
    }

    /**
     * One grave as the shared base reads it: its saved data, read again at every call (null once the grave is gone),
     * and the registries its items are parsed with.
     */
    public record Grave(Supplier<CompoundTag> data, HolderLookup.Provider registries) {
        public Grave {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(registries, "registries");
        }

        /** A live grave block entity; a removed one reads as gone. */
        public static Grave of(BlockEntity blockEntity) {
            Level level = Objects.requireNonNull(blockEntity, "blockEntity").getLevel();
            if (level == null) throw new IllegalStateException("The grave has no level");
            return new Grave(() -> blockEntity.isRemoved() ? null : GraveOwnerReader.blockEntityData(blockEntity),
                    level.registryAccess());
        }
    }

    @Override
    public AdapterKey key() {
        return GraveStoneGraveSpec.KEY;
    }

    /** The owner from the death record; a grave that is gone or unreadable has none (counted by the shared base). */
    @Override
    public GraveOwnerFacts owner(Grave grave, String site) {
        return GraveOwners.guarded(key(), site, () -> GraveOwnerReader.read(key(), site, GraveStoneGraveSpec.OWNER,
                Objects.requireNonNull(grave.data().get(), "The grave is gone")));
    }

    /** The death record's lists; a grave that is gone is empty. */
    @Override
    public Map<InventoryItemIdentity, Long> contents(Grave grave) {
        return GraveContents.read(GraveStoneGraveSpec.CONTENTS, grave.data().get(), grave.registries());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The hooks' work.

    /**
     * The take hook's work, around the grave block's method that hands the grave at {@code pos} over to {@code actor}
     * (on a break: the whole player-break method, hand-over and removal; checked against the jar). {@code once} is the
     * mod's call, run exactly once on every path; its result is the mod's own. On the client, for anything but a server
     * player, or when the block entity at {@code pos} is not the mod's grave, only the mod's call runs. Otherwise
     * {@link #takeAt} decides.
     */
    public static <R> void take(Level level, BlockPos pos, Entity actor, HookGuard.Original<R> once) {
        if (level == null || level.isClientSide() || pos == null || !(actor instanceof ServerPlayer taker)) {
            once.call();
            return;
        }
        BlockEntity grave = graveAt(level, pos);
        if (grave == null) {
            once.call();
            return;
        }
        Grave handle = Grave.of(grave);
        takeAt(handle, taker, once::call, modCall -> GraveTakes.take(SPEC, TAKE_SITE, handle,
                identity(level, pos, grave), level, taker, arguments -> {
                    modCall.run();
                    return null;
                }));
    }

    /**
     * The take's work once the mod's grave is found. An empty grave (a grave block placed by hand: nothing can be taken
     * and its removal drops nothing) only runs the mod's call, as {@link #origin(GraveIdentity, Grave)} does, so no owner
     * is read and nothing is counted. Otherwise a sneak frame armed for this player opens his sneak scope at the grave
     * first, then {@code take} runs the shared take around {@code modCall}.
     */
    public static void takeAt(Grave grave, ServerPlayer taker, Runnable modCall, Consumer<Runnable> take) {
        if (SPEC.contents(grave).isEmpty()) {
            modCall.run();
            return;
        }
        GraveSneakFrame.takeEntered(taker);
        take.accept(modCall);
    }

    /** The mod's grave block entity at {@code pos}, by its block-entity type (no mod class is named), or null. */
    static BlockEntity graveAt(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null
                || !GraveStoneGraveSpec.BLOCK_ID.equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()))) {
            return null;
        }
        return blockEntity;
    }

    /** The spill hook's work, around one call of the drop helper for {@code stack}. */
    public static void spill(ItemStack stack, Runnable dropCall) {
        SpillTags.atSpill(stack, SPILL_PASSES_COPY, dropCall);
    }

    /**
     * The removal hook's work, around the mod's removal handler: when the grave block is replaced by another block
     * (the only case in which the mod drops anything), the grave as it is now is the origin of what it drops.
     */
    public static void removal(BlockState state, Level level, BlockPos pos, BlockState newState, Runnable removal) {
        if (level == null || level.isClientSide() || pos == null || state == null || newState == null
                || state.getBlock() == newState.getBlock()) {
            removal.run();
            return;
        }
        removal(() -> origin(level, pos), removal);
    }

    /**
     * The removal's origin frame around {@code removal}; inside a command none is opened (shared base). Inside an open
     * take (a break: the take wraps the whole break, so the removal runs inside it) none is opened either: the take's
     * own context is the grave and its owner, and what the removal drops is counted by the take as spilled grave units.
     */
    public static void removal(Supplier<SpillTags.SpillOrigin> origin, Runnable removal) {
        if (GraveTakes.takeOpen()) {
            removal.run();
            return;
        }
        SpillTags.outsideTake(origin, removal);
    }

    /** The origin of a removal: the grave's contents and owner, or none for an empty grave. */
    public static SpillTags.SpillOrigin origin(GraveIdentity identity, Grave grave) {
        Map<InventoryItemIdentity, Long> contents = SPEC.contents(grave);
        if (contents.isEmpty()) return null;
        GraveOwnerFacts owner = SPEC.owner(grave, REMOVAL_SITE);
        return SpillTags.SpillOrigin.of(identity, contents, SPEC.portions(grave, contents, owner));
    }

    private static SpillTags.SpillOrigin origin(Level level, BlockPos pos) {
        BlockEntity blockEntity = graveAt(level, pos);
        if (blockEntity == null) return null;
        return origin(identity(level, pos, blockEntity), Grave.of(blockEntity));
    }

    /** A grave block's identity: its dimension, position, block id and display. */
    static GraveIdentity identity(Level level, BlockPos pos, BlockEntity grave) {
        Block block = grave.getBlockState().getBlock();
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        Supplier<Component> customName = grave instanceof Nameable nameable ? nameable::getCustomName : null;
        return GraveIdentity.block(level.dimension().location().toString(), pos, blockId, display(block, customName));
    }

    /** The grave's display: the game's own block name, with a custom name alongside, never instead. */
    public static String display(Block block, Supplier<Component> customName) {
        return DisplayNameResolver.formatName(DisplayNameResolver.blockDisplayName(block), null,
                RenameState.ofComponent(customName));
    }
}
