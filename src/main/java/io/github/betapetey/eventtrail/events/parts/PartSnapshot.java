package io.github.betapetey.eventtrail.events.parts;

import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.List;
import java.util.Objects;

/**
 * The "before" values of one part-removal action (design r2 1.2 N2), taken when the action opens and dropped when it
 * ends. The block entity is kept only as an identity for the same-object test at finish, never read again from here.
 *
 * @param state       the block state before the action (the break row's before state, from {@code preimage})
 * @param fluidBlock  the block the old state's fluid makes ({@code getFluidState().createLegacyBlock()}), or null
 *                    when the old state holds no fluid: a waterlogged block removed by a mod leaves this block
 * @param blockEntity the claimed block entity, compared by identity at finish
 * @param preimage    the break row's preimage (the break path reuses the existing one)
 * @param pieces      the reader's piece list, checked
 * @param inventory   copies of the player's inventory slots
 * @param usedSlot    the inventory slot of the hand that was used
 * @param frame       the location frame captured for the block
 */
record PartSnapshot(
        BlockState state,
        BlockState fluidBlock,
        Object blockEntity,
        BlockAuditPreimage preimage,
        List<PartReader.Piece> pieces,
        List<ItemStack> inventory,
        int usedSlot,
        AuditBlockLocations.Frame frame) {

    PartSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(blockEntity, "blockEntity");
        Objects.requireNonNull(preimage, "preimage");
        pieces = List.copyOf(pieces);
        inventory = List.copyOf(inventory);
        Objects.requireNonNull(frame, "frame");
    }

    /** The block a removal of {@code state} leaves when the state holds a fluid, else null. */
    static BlockState fluidBlockOf(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.isEmpty() ? null : fluid.createLegacyBlock();
    }

    /**
     * The "after" values the outcome needs.
     *
     * @param state                  the block state at the position after the action
     * @param sameClaimedBlockEntity the position still holds the same block entity object and its reader still claims it
     * @param pieces                 the reader's piece list after the action (empty unless {@code sameClaimedBlockEntity})
     */
    record After(BlockState state, boolean sameClaimedBlockEntity, List<PartReader.Piece> pieces) {
        After {
            Objects.requireNonNull(state, "state");
            pieces = List.copyOf(pieces);
        }
    }
}
