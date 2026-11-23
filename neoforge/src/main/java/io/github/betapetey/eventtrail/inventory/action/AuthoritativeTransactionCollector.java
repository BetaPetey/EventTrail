package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, action-local buffer for exact transfers reported by an optional mod.
 *
 * <p>The menu action owns one collector on the server thread. Evidence is not
 * exposed until the outer action completes normally, so a thrown or over-sized
 * operation cannot leave a partially attributed transaction behind.</p>
 */
public final class AuthoritativeTransactionCollector {
    public static final int MAX_TRANSFERS_PER_ACTION = 512;

    private final List<Transfer> transfers = new ArrayList<>();
    private State state = State.OPEN;

    /** Adds one positive, already-committed transfer in invocation order. */
    public boolean record(
            TransactionDirection direction,
            InventoryItemIdentity item,
            long quantity) {
        if (state != State.OPEN || direction == null || item == null || quantity <= 0L) {
            return false;
        }
        if (transfers.size() >= MAX_TRANSFERS_PER_ACTION) {
            transfers.clear();
            state = State.INVALID;
            return false;
        }
        transfers.add(new Transfer(direction, item, quantity));
        return true;
    }

    /** Completes the action exactly once; invalid/aborted actions expose no evidence. */
    public List<Transfer> commit() {
        if (state != State.OPEN) return List.of();
        state = State.COMMITTED;
        return List.copyOf(transfers);
    }

    /** Discards the complete pending action. Safe to call repeatedly. */
    public void abort() {
        transfers.clear();
        if (state != State.COMMITTED) state = State.ABORTED;
    }

    public boolean invalid() {
        return state == State.INVALID;
    }

    public int pendingCount() {
        return state == State.OPEN ? transfers.size() : 0;
    }

    private enum State {
        OPEN,
        INVALID,
        COMMITTED,
        ABORTED
    }

    /** One exact committed call. Identical rapid calls deliberately remain distinct. */
    public record Transfer(
            TransactionDirection direction,
            InventoryItemIdentity item,
            long quantity) {
        public Transfer {
            direction = Objects.requireNonNull(direction, "direction");
            item = Objects.requireNonNull(item, "item");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
