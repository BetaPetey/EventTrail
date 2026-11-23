package io.github.betapetey.eventtrail.events.parts;

import net.minecraft.core.Direction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The slot keys and piece ids of {@link Ae2PartReader} (parts readers design-03 1.3), on plain values only, so the
 * unit tests need no AE2 and no game.
 *
 * <p>A key names the physical slot of an AE2 cable bus and nothing else: {@code cable} for the centre
 * ({@code IPartHost.getPart(null)}), {@code side:<direction>} for the part on one side and {@code facade:<direction>}
 * for the facade on one side, with the direction's own serialized name. It never depends on list order or on what
 * sits in the slot, so AE2's in-place changes (P2P attunement, memory-card paste, {@code replacePart}) keep it.</p>
 */
final class Ae2PartSlots {
    /** Most pieces one AE2 cable bus can hold: the cable, six side parts and six facades. */
    static final int MAX_PIECES = 13;
    /** The six sides in a fixed order (the order is not part of any key). */
    static final List<Direction> SIDES = List.of(Direction.values());

    /** The prefix of a piece id too long to keep: the id's name-based UUID follows (the ID reader's fallback). */
    static final String HASHED_PREFIX = "hashed:";

    private static final String CABLE = "cable";
    private static final String SIDE = "side:";
    private static final String FACADE = "facade:";

    private Ae2PartSlots() {
    }

    /** The key of the part slot that {@code IPartHost.getPart(side)} reads: null is the cable centre. */
    static String partSlot(Direction side) {
        return side == null ? CABLE : SIDE + side.getSerializedName();
    }

    /** The key of the facade slot on {@code side}. */
    static String facadeSlot(Direction side) {
        return FACADE + Objects.requireNonNull(side, "side").getSerializedName();
    }

    /**
     * The piece id: the item's registry id as given up to {@link PartReader.Piece#MAX_PIECE_ID_CHARS} characters, else
     * hashed (never cut, so two long ids stay distinct and the same id always gives the same piece id), as the ID
     * reader does ({@code IntegratedDynamicsPartSlots.pieceId}).
     */
    static String pieceId(String registryId) {
        Objects.requireNonNull(registryId, "registryId");
        if (registryId.length() <= PartReader.Piece.MAX_PIECE_ID_CHARS) return registryId;
        return HASHED_PREFIX + UUID.nameUUIDFromBytes(registryId.getBytes(StandardCharsets.UTF_8));
    }

    /** Every key the reader can report for one block, in the order the reader reads the slots. */
    static List<String> allSlots() {
        List<String> slots = new ArrayList<>(MAX_PIECES);
        slots.add(partSlot(null));
        for (Direction side : SIDES) slots.add(partSlot(side));
        for (Direction side : SIDES) slots.add(facadeSlot(side));
        return List.copyOf(slots);
    }
}
