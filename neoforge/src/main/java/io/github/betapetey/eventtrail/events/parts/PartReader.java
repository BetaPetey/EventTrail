package io.github.betapetey.eventtrail.events.parts;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one mod's part reader supplies to {@link PartRemovalAuditBridge} (Parts A shared piece, design r2 section 4).
 *
 * <p>Every reader:</p>
 * <ol>
 *   <li>has a public no-argument constructor, lives in this package and is loaded by class name only when its mod
 *       is loaded (the bridge's reader table), at server start;</li>
 *   <li>uses only the mod's public API where it exists and lets failures propagate: no catch-and-deny. A reflective
 *       helper throws {@code MissingAdapterMemberException}, so the fail-safe switches the reader's key OFF until
 *       restart;</li>
 *   <li>reads only the given block entity: no world change, no chunk load, no inventory access;</li>
 *   <li>keeps a slot key stable for the same physical slot before and after an action; an in-place change keeps
 *       the key. A key never moves from one piece to another: it names the physical slot, never an index into a
 *       list that compacts when an earlier piece leaves (the bridge compares keys only, so a moved key gives a
 *       wrong count-1 row for a piece that never left; r3 review m-6 b);</li>
 *   <li>reports at most {@link PartRemovalAuditBridge#MAX_PIECES} pieces with distinct, well-formed slot keys
 *       (otherwise the bridge reports an integrity failure and records nothing for that action);</li>
 *   <li>gives each piece's item at snapshot time, of the same item TYPE as the item the mod itself hands over when
 *       that piece is removed (a coloured cable names the coloured cable item, a facade the facade item), else
 *       {@link ItemStack#EMPTY}; {@code EMPTY} only if that item cannot be named (that piece then gets no piece
 *       row, with one warning). The piece line matches by item type: a piece named with another type gets a
 *       second, wrong "Removed Item" row beside the handed-over one (review-01 M7 a);</li>
 *   <li>states in its own step which routine its mod uses to hand removed items over and where they spawn (the
 *       claimed block's cell, the player's drop point, or elsewhere, which the collector does not count), and
 *       whether the block entity object survives a piece removal: if the mod replaces it (or the block) while a
 *       piece leaves, the bridge records nothing for that action, with one warning (review-01 M7 c, r3 M-2);</li>
 *   <li>treats one piece as one item: the piece line names a removed piece that was not handed over with count 1
 *       (r3 review m-6 e);</li>
 *   <li>states in its own step that its mod completes a removal before the bracketed call returns (the
 *       right-click, or {@code onDestroyedByPlayer}): a removal the mod defers to a later tick is seen as no change
 *       and is not logged (r3 review m-6 c);</li>
 *   <li>states in its own step what its mod's {@code onDestroyedByPlayer} returns for a single-part removal: if it
 *       returns {@code true} while the block stays, the bridge leaves the action to the existing break path, which
 *       then writes nothing (r3 review m-6 d).</li>
 * </ol>
 *
 * <p>Every call runs on the server thread through the adapter fail-safe under the reader's own key.</p>
 */
public interface PartReader {
    /**
     * Cheap and side-effect free: {@code instanceof} checks only. While the reader's mod is loaded this runs for
     * every right-click on a block entity in reach and for every recorded break. A {@code true} answer costs every
     * right-click on that block entity a full block-entity save (the break row's preimage, with its size warning),
     * one {@link #pieces} call and a copy of the player's inventory, and, when the action ends, one more
     * {@code claims} and {@link #pieces} call; so claim only the block entities whose pieces this reader reports
     * (review-01 M7 b).
     */
    boolean claims(BlockEntity blockEntity);

    /**
     * Every removable piece now in the block; never null (a null list is an integrity failure, reported, and the
     * action records nothing: r3 review m-1, m-6 a); an empty list means only the block-gone rule applies.
     */
    List<Piece> pieces(BlockEntity blockEntity);

    /**
     * One removable piece in one slot of a claimed block.
     *
     * @param slot    the physical slot, {@code [a-z0-9_:./-]{1,64}} (for example {@code side:north}, {@code cable})
     * @param pieceId what sits in the slot, at most {@value #MAX_PIECE_ID_CHARS} characters (reader-defined). Only
     *                its length is checked: it is never written to a row and the decision never reads it; it lets a
     *                reader lane's tests and a debugger tell pieces apart (review-01 N10)
     * @param item    the piece's item at snapshot time (copied here), or {@link ItemStack#EMPTY} if unknown
     */
    record Piece(String slot, String pieceId, ItemStack item) {
        public static final int MAX_PIECE_ID_CHARS = 128;
        private static final Pattern SLOT = Pattern.compile("[a-z0-9_:./-]{1,64}");

        public Piece {
            slot = Objects.requireNonNull(slot, "slot");
            pieceId = Objects.requireNonNull(pieceId, "pieceId");
            item = item == null || item.isEmpty() ? ItemStack.EMPTY : item.copy();
        }

        /** The slot key and piece id meet the reader contract (the bridge checks every list it is given). */
        boolean wellFormed() {
            return SLOT.matcher(slot).matches() && pieceId.length() <= MAX_PIECE_ID_CHARS;
        }
    }
}
