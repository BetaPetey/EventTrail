package io.github.betapetey.eventtrail.events.parts;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The slot keys and piece ids of {@link EnderIoPartReader} (parts readers design-03 3.3), on plain values only, so the
 * unit tests need no Ender IO and no game.
 *
 * <p>A key names the physical slot of an Ender IO conduit bundle and nothing else: {@code conduit:<conduit type id>}
 * for the conduit of one type (a bundle holds at most one conduit per {@code ConduitType}, so the type is the slot) and
 * {@code facade} for the facade. It never depends on list order or on which conduit of that type sits in the slot, so
 * the upgrade swap (a better conduit of the same type) keeps it. A type key that would pass
 * {@link #MAX_SLOT_CHARS} characters is hashed, never cut, so it stays stable and one per type.</p>
 */
final class EnderIoPartSlots {
    /** Most conduits one bundle holds (8.2.12-beta {@code ConduitBundleBlockEntity.isFull}: size 9). */
    static final int MAX_CONDUITS = 9;
    /** Most pieces one bundle can hold: nine conduits and the facade. */
    static final int MAX_PIECES = MAX_CONDUITS + 1;
    /** The longest slot key the reader contract allows ({@code [a-z0-9_:./-]{1,64}}). */
    static final int MAX_SLOT_CHARS = 64;

    /** The facade's slot key. */
    static final String FACADE = "facade";
    /** The prefix of a conduit type's slot key; the type's registry id follows. */
    static final String CONDUIT = "conduit:";
    /**
     * The prefix of a conduit type's slot key when {@code conduit:<id>} would pass {@link #MAX_SLOT_CHARS}: the first
     * 16 hex digits of the id's name-based UUID follow.
     */
    static final String HASHED_CONDUIT = "conduit-h:";
    /** The prefix of a piece id too long to keep: the id's name-based UUID follows (the ID and AE2 readers' fallback). */
    static final String HASHED_PREFIX = "hashed:";

    private EnderIoPartSlots() {
    }

    /**
     * The key of the slot of the conduit whose type has this registry id. A null id (a conduit type that is not
     * registered) throws: the action is skipped with a warning, never given a guessed key.
     */
    static String conduitSlot(String conduitTypeId) {
        Objects.requireNonNull(conduitTypeId, "an unregistered Ender IO conduit type has no slot key");
        String key = CONDUIT + conduitTypeId;
        if (key.length() <= MAX_SLOT_CHARS) return key;
        long hash = UUID.nameUUIDFromBytes(conduitTypeId.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        return HASHED_CONDUIT + HexFormat.of().toHexDigits(hash);
    }

    /**
     * The piece id: the given id (a conduit's registered name or a facade item's registry id) as given up to
     * {@link PartReader.Piece#MAX_PIECE_ID_CHARS} characters, else hashed (never cut, so two long ids stay distinct and
     * the same id always gives the same piece id), as the ID and AE2 readers do.
     */
    static String pieceId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.length() <= PartReader.Piece.MAX_PIECE_ID_CHARS) return id;
        return HASHED_PREFIX + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
    }
}
