package io.github.betapetey.eventtrail.events.parts;

import net.minecraft.core.Direction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Slot keys and piece ids of {@link IntegratedDynamicsPartReader} (parts design-03 2.3), on plain values only so the
 * unit tests need no mod. A key names the physical slot only: one per side for a part, one for the cable, one for
 * the facade; never a list index and never what sits in the slot.
 */
final class IntegratedDynamicsPartSlots {
    /** The cable slot (filled while the cable is real). */
    static final String CABLE = "cable";
    /** The facade slot. */
    static final String FACADE = "facade";
    /** The cable piece's id. */
    static final String CABLE_ID = "integrateddynamics:cable";
    /** The facade piece's id. */
    static final String FACADE_ID = "integrateddynamics:facade";
    /** Six sides, the cable and the facade. */
    static final int MAX_PIECES = 8;
    /** The prefix of a piece id too long to keep: the id's name-based UUID follows. */
    static final String HASHED_PREFIX = "hashed:";

    private static final String SIDE_PREFIX = "side:";

    private IntegratedDynamicsPartSlots() {
    }

    /** The slot key of the part on {@code side}, for example {@code side:north}. */
    static String side(Direction side) {
        return SIDE_PREFIX + side.getName();
    }

    /**
     * The piece id for a part type's unique name: kept as is up to {@link PartReader.Piece#MAX_PIECE_ID_CHARS}
     * characters, else hashed (never cut, so two long names stay distinct).
     */
    static String pieceId(String uniqueName) {
        if (uniqueName.length() <= PartReader.Piece.MAX_PIECE_ID_CHARS) return uniqueName;
        return HASHED_PREFIX + UUID.nameUUIDFromBytes(uniqueName.getBytes(StandardCharsets.UTF_8));
    }

    /** Every slot key one block can have, in the order the reader reports them. */
    static List<String> slotKeys() {
        List<String> keys = new ArrayList<>(MAX_PIECES);
        for (Direction side : Direction.values()) {
            keys.add(side(side));
        }
        keys.add(CABLE);
        keys.add(FACADE);
        return List.copyOf(keys);
    }
}
