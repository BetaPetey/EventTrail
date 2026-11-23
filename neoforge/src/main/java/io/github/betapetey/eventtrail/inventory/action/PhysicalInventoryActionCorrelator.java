package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure custody correlation for one successfully completed physical menu action.
 *
 * <p>The caller owns capture and session lifetime. This model only compares
 * immutable before/after totals and carries at most one component-exact cursor
 * escrow between actions. A rejected action always clears escrow and emits no
 * partial evidence.</p>
 */
public final class PhysicalInventoryActionCorrelator {
    private static final Comparator<InventoryItemIdentity> IDENTITY_ORDER = Comparator
            .comparing(InventoryItemIdentity::registryId)
            .thenComparing(InventoryItemIdentity::displayName)
            .thenComparingInt(InventoryItemIdentity::hashCode);

    private PhysicalInventoryActionCorrelator() {
    }

    /** Correlates with the shipped volatile-component list as the fallback for a non-conserving action. */
    public static Result correlate(Snapshot before, Snapshot after, Optional<CursorEscrow> priorEscrow) {
        return correlate(before, after, priorEscrow, VolatileItemComponents.DEFAULT);
    }

    /** Component-exact only: no volatile-component fallback (callers that compare with native commits). */
    public static Result correlateExact(Snapshot before, Snapshot after, Optional<CursorEscrow> priorEscrow) {
        return correlate(before, after, priorEscrow, VolatileItemComponents.NONE);
    }

    /**
     * GF-IDENTITYNORM: exact conservation first. Only an action rejected as non-conserving is
     * retried as one volatile-component pair; anything else keeps the exact result unchanged.
     */
    public static Result correlate(
            Snapshot before,
            Snapshot after,
            Optional<CursorEscrow> priorEscrow,
            VolatileItemComponents volatileComponents) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(priorEscrow, "priorEscrow");
        Objects.requireNonNull(volatileComponents, "volatileComponents");

        Result exact = correlateComponentExact(before, after, priorEscrow);
        if (exact.accepted()
                || exact.failureReason() != FailureReason.NON_CONSERVING_ACTION
                || volatileComponents.isEmpty()) {
            return exact;
        }
        return correlateVolatilePair(before, after, priorEscrow, volatileComponents).orElse(exact);
    }

    /**
     * Accepts one pair only: exactly one identity lost count and exactly one gained the same
     * count, the loser gained nowhere, the gainer lost nowhere and did not gain on the cursor,
     * both are the same item, and their components are equal after removing only the listed
     * components. The pair is then correlated exactly as one identity; a deposit carries the
     * identity now in the device, a withdrawal the identity that left the device, and the
     * cursor escrow keeps its exact identity. Any other shape returns empty (the exact result).
     */
    private static Optional<Result> correlateVolatilePair(
            Snapshot before,
            Snapshot after,
            Optional<CursorEscrow> priorEscrow,
            VolatileItemComponents volatileComponents) {
        Set<InventoryItemIdentity> identities = new HashSet<>();
        identities.addAll(before.device().keySet());
        identities.addAll(before.player().keySet());
        identities.addAll(before.cursor().keySet());
        identities.addAll(after.device().keySet());
        identities.addAll(after.player().keySet());
        identities.addAll(after.cursor().keySet());

        InventoryItemIdentity loser = null;
        InventoryItemIdentity gainer = null;
        long lost = 0L;
        long gained = 0L;
        long loserDeviceDelta = 0L;
        long loserPlayerDelta = 0L;
        long gainerDeviceDelta = 0L;
        long gainerPlayerDelta = 0L;
        try {
            for (InventoryItemIdentity item : identities) {
                long deviceDelta = subtract(
                        after.device().getOrDefault(item, 0L),
                        before.device().getOrDefault(item, 0L));
                long playerDelta = subtract(
                        after.player().getOrDefault(item, 0L),
                        before.player().getOrDefault(item, 0L));
                long cursorDelta = subtract(
                        after.cursor().getOrDefault(item, 0L),
                        before.cursor().getOrDefault(item, 0L));
                long net = add(add(deviceDelta, playerDelta), cursorDelta);
                if (net < 0L) {
                    if (loser != null || deviceDelta > 0L || playerDelta > 0L || cursorDelta > 0L) {
                        return Optional.empty();
                    }
                    loser = item;
                    lost = negate(net);
                    loserDeviceDelta = deviceDelta;
                    loserPlayerDelta = playerDelta;
                } else if (net > 0L) {
                    if (gainer != null || deviceDelta < 0L || playerDelta < 0L || cursorDelta != 0L) {
                        return Optional.empty();
                    }
                    gainer = item;
                    gained = net;
                    gainerDeviceDelta = deviceDelta;
                    gainerPlayerDelta = playerDelta;
                }
            }
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }

        if (loser == null || gainer == null || lost != gained
                || !volatileComponents.sameItemIgnoringVolatile(loser, gainer)) {
            return Optional.empty();
        }
        // A copy relabelled inside the device while another leaves it is not one proven flow.
        if (loserDeviceDelta < 0L && gainerDeviceDelta > 0L) {
            return Optional.empty();
        }
        // A gained item already in the device can hide a second move inside a net delta of 0
        // (the wrong stack leaving, or a swap), so it is not one proven flow either. The same holds
        // when the lost item leaves the player but is still in the device after the click (a swap).
        if (gainerPlayerDelta > 0L && (before.device().containsKey(gainer)
                || (after.device().containsKey(loser) && loserPlayerDelta < 0L))) {
            return Optional.empty();
        }
        // A device gain under a different identity could be the return of cursor escrow; the
        // role resolver checks each exact identity and cannot prove that return, so refuse it.
        if (gainerDeviceDelta > 0L && priorEscrow.isPresent()
                && (priorEscrow.orElseThrow().item().equals(loser)
                || priorEscrow.orElseThrow().item().equals(gainer))) {
            return Optional.empty();
        }

        InventoryItemIdentity pairLoser = loser;
        InventoryItemIdentity pairGainer = gainer;
        Optional<CursorEscrow> mergedEscrow = priorEscrow.map(escrow -> escrow.item().equals(pairLoser)
                ? new CursorEscrow(pairGainer, escrow.quantity())
                : escrow);
        Result merged;
        try {
            merged = correlateComponentExact(
                    mergePair(before, pairLoser, pairGainer),
                    mergePair(after, pairLoser, pairGainer),
                    mergedEscrow);
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        if (!merged.accepted()) {
            return Optional.empty();
        }

        List<Transfer> transfers = new ArrayList<>(merged.transfers().size());
        for (Transfer transfer : merged.transfers()) {
            if (transfer.item().equals(pairGainer) && transfer.direction() == TransactionDirection.WITHDRAWAL) {
                // The pair can only lose device custody under the loser's identity.
                transfers.add(new Transfer(TransactionDirection.WITHDRAWAL, pairLoser, transfer.quantity()));
            } else {
                transfers.add(transfer);
            }
        }

        Optional<CursorEscrow> nextEscrow = merged.nextEscrow();
        if (nextEscrow.isPresent() && nextEscrow.orElseThrow().item().equals(pairGainer)) {
            // Escrow on the pair can only be carried over, never gained, in a pair action; it
            // keeps the exact identity that was escrowed (the identity still on the cursor).
            Optional<InventoryItemIdentity> priorPairItem = priorEscrow.map(CursorEscrow::item)
                    .filter(item -> item.equals(pairLoser) || item.equals(pairGainer));
            if (priorPairItem.isEmpty()) {
                return Optional.empty();
            }
            nextEscrow = Optional.of(new CursorEscrow(
                    priorPairItem.orElseThrow(),
                    nextEscrow.orElseThrow().quantity()));
        }
        if (nextEscrow.isPresent()) {
            CursorEscrow escrow = nextEscrow.orElseThrow();
            if (after.cursor().getOrDefault(escrow.item(), 0L) < escrow.quantity()) {
                return Optional.empty();
            }
        }
        return Optional.of(Result.accepted(transfers, nextEscrow));
    }

    private static Snapshot mergePair(Snapshot snapshot, InventoryItemIdentity loser, InventoryItemIdentity gainer) {
        return new Snapshot(
                mergeInto(snapshot.device(), loser, gainer),
                mergeInto(snapshot.player(), loser, gainer),
                mergeInto(snapshot.cursor(), loser, gainer));
    }

    private static Map<InventoryItemIdentity, Long> mergeInto(
            Map<InventoryItemIdentity, Long> totals,
            InventoryItemIdentity loser,
            InventoryItemIdentity gainer) {
        Long loserCount = totals.get(loser);
        if (loserCount == null) {
            return totals;
        }
        Map<InventoryItemIdentity, Long> merged = new HashMap<>(totals);
        merged.remove(loser);
        merged.merge(gainer, loserCount, Math::addExact);
        return merged;
    }

    private static Result correlateComponentExact(
            Snapshot before,
            Snapshot after,
            Optional<CursorEscrow> priorEscrow) {
        Map<InventoryItemIdentity, Long> escrowByItem = new HashMap<>();
        if (priorEscrow.isPresent()) {
            CursorEscrow escrow = priorEscrow.orElseThrow();
            if (before.cursor().getOrDefault(escrow.item(), 0L) < escrow.quantity()) {
                return Result.rejected(FailureReason.STALE_CURSOR_ESCROW);
            }
            escrowByItem.put(escrow.item(), escrow.quantity());
        }

        Set<InventoryItemIdentity> identities = new HashSet<>();
        identities.addAll(before.device().keySet());
        identities.addAll(before.player().keySet());
        identities.addAll(before.cursor().keySet());
        identities.addAll(after.device().keySet());
        identities.addAll(after.player().keySet());
        identities.addAll(after.cursor().keySet());
        identities.addAll(escrowByItem.keySet());

        List<InventoryItemIdentity> orderedIdentities = new ArrayList<>(identities);
        orderedIdentities.sort(IDENTITY_ORDER);
        List<Transfer> transfers = new ArrayList<>();

        try {
            for (InventoryItemIdentity item : orderedIdentities) {
                long deviceDelta = subtract(
                        after.device().getOrDefault(item, 0L),
                        before.device().getOrDefault(item, 0L));
                long playerDelta = subtract(
                        after.player().getOrDefault(item, 0L),
                        before.player().getOrDefault(item, 0L));
                long cursorDelta = subtract(
                        after.cursor().getOrDefault(item, 0L),
                        before.cursor().getOrDefault(item, 0L));

                if (add(add(deviceDelta, playerDelta), cursorDelta) != 0L) {
                    return Result.rejected(FailureReason.NON_CONSERVING_ACTION);
                }

                long escrow = escrowByItem.getOrDefault(item, 0L);
                if (deviceDelta > 0L) {
                    if (cursorDelta > 0L) {
                        return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                    }

                    if (playerDelta <= 0L) {
                        long suppliedByPlayer = negate(playerDelta);
                        long suppliedByCursor = negate(cursorDelta);
                        if (add(suppliedByPlayer, suppliedByCursor) != deviceDelta) {
                            return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                        }

                        long returnedToDevice = Math.min(escrow, suppliedByCursor);
                        escrow -= returnedToDevice;
                        long deposited = deviceDelta - returnedToDevice;
                        if (deposited > 0L) {
                            transfers.add(new Transfer(TransactionDirection.DEPOSIT, item, deposited));
                        }
                    } else {
                        // One cursor distribution may place component-identical items into both
                        // device and player slots. Consume device-origin escrow against the
                        // device return first, then resolve any remainder retained by the player.
                        long suppliedByCursor = negate(cursorDelta);
                        if (add(deviceDelta, playerDelta) != suppliedByCursor) {
                            return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                        }

                        long returnedToDevice = Math.min(escrow, deviceDelta);
                        escrow -= returnedToDevice;
                        long deposited = deviceDelta - returnedToDevice;
                        long completedWithdrawal = Math.min(escrow, playerDelta);
                        escrow -= completedWithdrawal;
                        if (deposited > 0L) {
                            transfers.add(new Transfer(TransactionDirection.DEPOSIT, item, deposited));
                        }
                        if (completedWithdrawal > 0L) {
                            transfers.add(new Transfer(
                                    TransactionDirection.WITHDRAWAL,
                                    item,
                                    completedWithdrawal));
                        }
                    }
                } else if (deviceDelta < 0L) {
                    if (cursorDelta < 0L) {
                        return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                    }

                    long removedFromDevice = negate(deviceDelta);
                    if (playerDelta >= 0L) {
                        if (add(playerDelta, cursorDelta) != removedFromDevice) {
                            return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                        }

                        if (playerDelta > 0L) {
                            transfers.add(new Transfer(
                                    TransactionDirection.WITHDRAWAL,
                                    item,
                                    playerDelta));
                        }
                        if (cursorDelta > 0L) {
                            escrow = add(escrow, cursorDelta);
                        }
                    } else {
                        // PICKUP_ALL can gather the same exact identity from device and player
                        // slots into one cursor stack. Only the device loss gains escrow.
                        long removedFromPlayer = negate(playerDelta);
                        if (add(removedFromDevice, removedFromPlayer) != cursorDelta) {
                            return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                        }
                        escrow = add(escrow, removedFromDevice);
                    }
                } else if (playerDelta > 0L) {
                    if (cursorDelta != negate(playerDelta)) {
                        return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                    }

                    long completedWithdrawal = Math.min(escrow, playerDelta);
                    escrow -= completedWithdrawal;
                    if (completedWithdrawal > 0L) {
                        transfers.add(new Transfer(
                                TransactionDirection.WITHDRAWAL,
                                item,
                                completedWithdrawal));
                    }
                } else if (playerDelta < 0L && cursorDelta != negate(playerDelta)) {
                    return Result.rejected(FailureReason.AMBIGUOUS_CUSTODY_FLOW);
                }

                if (escrow == 0L) {
                    escrowByItem.remove(item);
                } else {
                    escrowByItem.put(item, escrow);
                }
            }
        } catch (ArithmeticException overflow) {
            return Result.rejected(FailureReason.COUNT_OVERFLOW);
        }

        if (escrowByItem.size() > 1) {
            return Result.rejected(FailureReason.MULTIPLE_CURSOR_ESCROWS);
        }

        Optional<CursorEscrow> nextEscrow = escrowByItem.entrySet().stream()
                .findFirst()
                .map(entry -> new CursorEscrow(entry.getKey(), entry.getValue()));
        if (nextEscrow.isPresent()) {
            CursorEscrow escrow = nextEscrow.orElseThrow();
            if (after.cursor().getOrDefault(escrow.item(), 0L) < escrow.quantity()) {
                return Result.rejected(FailureReason.STALE_CURSOR_ESCROW);
            }
        }

        return Result.accepted(transfers, nextEscrow);
    }

    /** A capture or wrapped action failure produces no evidence and clears escrow. */
    public static Result captureFailed() {
        return Result.rejected(FailureReason.CAPTURE_FAILED);
    }

    /**
     * GF-NESTED: the second pass, for one PICKUP click that vanilla's item-click override step handled (the caller
     * holds that proof) and that {@link #correlate} rejected as non-conserving. Exactly one item X may change, only in
     * the click's other stack, and the only other change may be the shell (the clicked slot's stack or the carried
     * stack, same item and count, components changed or not). X went into or came out of the shell:
     * <ul>
     *   <li>shell in a device slot, X on the cursor: into the shell is a deposit (escrowed X returns to the device
     *   first, with no row); out of the shell onto the cursor is pending device custody (escrow), as a pickup is;</li>
     *   <li>shell on the cursor, X in a device slot: into the shell is a withdrawal, written at once; out of it is a
     *   deposit. When the carried shell is itself pending escrow, neither writes anything (the items never left the
     *   device) and the escrow follows the shell to its new identity;</li>
     *   <li>shell and X on the player side: no row, except escrowed X going into the shell, which completes that
     *   escrow's withdrawal.</li>
     * </ul>
     * Where the shell carries BUNDLE_CONTENTS or CONTAINER its contents must change by exactly X; otherwise the amount
     * is the click's (Owner Q17/Q45). Every other shape is refused with its reason; nothing is ever partly accepted.
     */
    public static NestedResult correlateNested(
            Snapshot before,
            Snapshot after,
            Optional<CursorEscrow> priorEscrow,
            NestedClick click) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(priorEscrow, "priorEscrow");
        Objects.requireNonNull(click, "click");
        try {
            return nested(before, after, priorEscrow, click);
        } catch (ArithmeticException overflow) {
            return NestedResult.refused(NestedRefusal.COUNT_OVERFLOW);
        }
    }

    private static NestedResult nested(
            Snapshot before,
            Snapshot after,
            Optional<CursorEscrow> priorEscrow,
            NestedClick click) {
        CustodyDomain shellDomain = click.shellDomain();
        CustodyDomain otherDomain = click.otherDomain();
        if (shellDomain == otherDomain
                || (shellDomain != CustodyDomain.CURSOR && otherDomain != CustodyDomain.CURSOR)
                || click.shellCount() != 1L
                || !SameStackRule.sameItem(click.shellBefore(), click.shellAfter())) {
            return NestedResult.refused(NestedRefusal.NOT_ONE_SHELL);
        }
        if (priorEscrow.isPresent()
                && before.cursor().getOrDefault(priorEscrow.orElseThrow().item(), 0L)
                < priorEscrow.orElseThrow().quantity()) {
            return NestedResult.refused(NestedRefusal.STALE_CURSOR_ESCROW);
        }

        InventoryItemIdentity shellBefore = click.shellBefore();
        InventoryItemIdentity shellAfter = click.shellAfter();
        boolean relabelled = !shellBefore.equals(shellAfter);
        Set<InventoryItemIdentity> identities = new HashSet<>();
        identities.addAll(before.device().keySet());
        identities.addAll(before.player().keySet());
        identities.addAll(before.cursor().keySet());
        identities.addAll(after.device().keySet());
        identities.addAll(after.player().keySet());
        identities.addAll(after.cursor().keySet());

        InventoryItemIdentity item = null;
        long visibleDelta = 0L;
        boolean itemOutsideTheClick = false;
        boolean moreThanOneItem = false;
        for (InventoryItemIdentity identity : identities) {
            long device = subtract(after.device().getOrDefault(identity, 0L),
                    before.device().getOrDefault(identity, 0L));
            long player = subtract(after.player().getOrDefault(identity, 0L),
                    before.player().getOrDefault(identity, 0L));
            long cursor = subtract(after.cursor().getOrDefault(identity, 0L),
                    before.cursor().getOrDefault(identity, 0L));
            if (relabelled && (identity.equals(shellBefore) || identity.equals(shellAfter))) {
                long expected = identity.equals(shellBefore) ? negate(click.shellCount()) : click.shellCount();
                long inShellDomain = inDomain(shellDomain, device, player, cursor);
                if (inShellDomain != expected || add(add(device, player), cursor) != inShellDomain) {
                    return NestedResult.refused(NestedRefusal.SHELL_NOT_IN_PLACE);
                }
                continue;
            }
            if (device == 0L && player == 0L && cursor == 0L) continue;
            if (identity.equals(shellBefore)) {
                // The unchanged shell's own identity moved: that is not one stack changing in place.
                return NestedResult.refused(NestedRefusal.SHELL_NOT_IN_PLACE);
            }
            // The shell checks above, then the count of changed items, then where the one item changed: the reason
            // never depends on the order the identities are visited in.
            if (item != null) {
                moreThanOneItem = true;
                continue;
            }
            long inOther = inDomain(otherDomain, device, player, cursor);
            itemOutsideTheClick = inOther == 0L || add(add(device, player), cursor) != inOther;
            item = identity;
            visibleDelta = inOther;
        }
        if (moreThanOneItem) return NestedResult.refused(NestedRefusal.MORE_THAN_ONE_ITEM);
        if (item == null) return NestedResult.refused(NestedRefusal.NO_ITEM);
        if (itemOutsideTheClick) return NestedResult.refused(NestedRefusal.ITEM_OUTSIDE_THE_CLICK);
        boolean intoShell = visibleDelta < 0L;
        long quantity = intoShell ? negate(visibleDelta) : visibleDelta;

        NestedRefusal contents = checkContents(click, item, intoShell ? quantity : negate(quantity));
        if (contents != NestedRefusal.NONE) return NestedResult.refused(contents);

        long deposited = 0L;
        long withdrawn = 0L;
        long escrowWithdrawn = 0L;
        long escrowReturned = 0L;
        long escrowGained = 0L;
        boolean escrowOnShell = false;
        Optional<CursorEscrow> nextEscrow = priorEscrow;
        boolean escrowOnItem = priorEscrow.isPresent() && priorEscrow.orElseThrow().item().equals(item);
        long escrowQuantity = priorEscrow.map(CursorEscrow::quantity).orElse(0L);

        if (shellDomain == CustodyDomain.CURSOR
                && priorEscrow.map(escrow -> escrow.item().equals(shellBefore)).orElse(false)) {
            // The carried container was taken from the device and has not landed: it is still device custody.
            if (otherDomain != CustodyDomain.DEVICE) {
                return NestedResult.refused(NestedRefusal.ESCROWED_SHELL_WITH_PLAYER_ITEM);
            }
            if (escrowQuantity != click.shellCount()) return NestedResult.refused(NestedRefusal.ESCROW_CONFLICT);
            escrowOnShell = true;
            nextEscrow = Optional.of(new CursorEscrow(shellAfter, escrowQuantity));
        } else if (shellDomain == CustodyDomain.DEVICE) {
            // The other stack is the cursor.
            if (intoShell) {
                escrowReturned = escrowOnItem ? Math.min(escrowQuantity, quantity) : 0L;
                deposited = quantity - escrowReturned;
                nextEscrow = reduced(priorEscrow, escrowReturned);
            } else {
                if (priorEscrow.isPresent() && !escrowOnItem) {
                    return NestedResult.refused(NestedRefusal.ESCROW_CONFLICT);
                }
                escrowGained = quantity;
                nextEscrow = Optional.of(new CursorEscrow(item, add(escrowQuantity, quantity)));
            }
        } else if (otherDomain == CustodyDomain.DEVICE) {
            // The shell is on the cursor and is the player's own; X is in the clicked device slot.
            if (intoShell) {
                withdrawn = quantity;
            } else {
                deposited = quantity;
            }
        } else if (otherDomain == CustodyDomain.CURSOR && escrowOnItem && intoShell) {
            // Escrowed X went into a shell on the player side: that escrow's withdrawal is complete.
            escrowWithdrawn = Math.min(escrowQuantity, quantity);
            nextEscrow = reduced(priorEscrow, escrowWithdrawn);
        }

        if (nextEscrow.isPresent()
                && after.cursor().getOrDefault(nextEscrow.orElseThrow().item(), 0L)
                < nextEscrow.orElseThrow().quantity()) {
            return NestedResult.refused(NestedRefusal.STALE_CURSOR_ESCROW);
        }

        List<Transfer> transfers = new ArrayList<>(2);
        if (deposited > 0L) transfers.add(new Transfer(TransactionDirection.DEPOSIT, item, deposited));
        long withdrawals = add(withdrawn, escrowWithdrawn);
        if (withdrawals > 0L) transfers.add(new Transfer(TransactionDirection.WITHDRAWAL, item, withdrawals));
        NestedFlow flow = new NestedFlow(click, item, quantity, intoShell, deposited, withdrawn, escrowWithdrawn,
                escrowReturned, escrowGained, escrowOnShell);
        return new NestedResult(Result.accepted(transfers, nextEscrow), Optional.of(flow), NestedRefusal.NONE);
    }

    private static NestedRefusal checkContents(NestedClick click, InventoryItemIdentity item, long expectedChange) {
        ItemShellContents.Source source = click.contentsBefore().source();
        if (source != click.contentsAfter().source() || source == ItemShellContents.Source.UNREADABLE) {
            return NestedRefusal.CONTENTS_UNREADABLE;
        }
        if (source == ItemShellContents.Source.NONE) return NestedRefusal.NONE;
        Map<InventoryItemIdentity, Long> beforeContents = click.contentsBefore().totals();
        Map<InventoryItemIdentity, Long> afterContents = click.contentsAfter().totals();
        Set<InventoryItemIdentity> entries = new HashSet<>(beforeContents.keySet());
        entries.addAll(afterContents.keySet());
        entries.add(item);
        for (InventoryItemIdentity entry : entries) {
            long change = subtract(afterContents.getOrDefault(entry, 0L), beforeContents.getOrDefault(entry, 0L));
            if (change != (entry.equals(item) ? expectedChange : 0L)) return NestedRefusal.CONTENTS_MISMATCH;
        }
        return NestedRefusal.NONE;
    }

    private static long inDomain(CustodyDomain domain, long device, long player, long cursor) {
        return switch (domain) {
            case DEVICE -> device;
            case PLAYER -> player;
            case CURSOR -> cursor;
        };
    }

    private static Optional<CursorEscrow> reduced(Optional<CursorEscrow> escrow, long consumed) {
        if (escrow.isEmpty() || consumed == 0L) return escrow;
        long left = subtract(escrow.orElseThrow().quantity(), consumed);
        return left == 0L ? Optional.empty() : Optional.of(new CursorEscrow(escrow.orElseThrow().item(), left));
    }

    private static long subtract(long left, long right) {
        return Math.subtractExact(left, right);
    }

    private static long add(long left, long right) {
        return Math.addExact(left, right);
    }

    private static long negate(long value) {
        return Math.negateExact(value);
    }

    /** Component-exact totals in each custody domain at one instant. */
    public record Snapshot(
            Map<InventoryItemIdentity, Long> device,
            Map<InventoryItemIdentity, Long> player,
            Map<InventoryItemIdentity, Long> cursor) {
        public Snapshot {
            device = copyTotals(device, "device");
            player = copyTotals(player, "player");
            cursor = copyTotals(cursor, "cursor");
            if (cursor.size() > 1) {
                throw new IllegalArgumentException("cursor may contain at most one item identity");
            }
        }

        public static Snapshot withoutCursor(
                Map<InventoryItemIdentity, Long> device,
                Map<InventoryItemIdentity, Long> player) {
            return new Snapshot(device, player, Map.of());
        }

        public static Snapshot withCursor(
                Map<InventoryItemIdentity, Long> device,
                Map<InventoryItemIdentity, Long> player,
                InventoryItemIdentity item,
                long quantity) {
            return new Snapshot(device, player, Map.of(item, quantity));
        }

        private static Map<InventoryItemIdentity, Long> copyTotals(
                Map<InventoryItemIdentity, Long> source,
                String domain) {
            Objects.requireNonNull(source, domain);
            Map<InventoryItemIdentity, Long> copy = new HashMap<>();
            source.forEach((item, count) -> {
                Objects.requireNonNull(item, domain + " item");
                Objects.requireNonNull(count, domain + " count");
                if (count <= 0L) {
                    throw new IllegalArgumentException(domain + " counts must be positive");
                }
                copy.put(item, count);
            });
            return Map.copyOf(copy);
        }
    }

    /** Unresolved device-origin custody currently held on the menu cursor. */
    public record CursorEscrow(InventoryItemIdentity item, long quantity) {
        public CursorEscrow {
            item = Objects.requireNonNull(item, "item");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** One proven player/device custody crossing produced by an accepted action. */
    public record Transfer(TransactionDirection direction, InventoryItemIdentity item, long quantity) {
        public Transfer {
            direction = Objects.requireNonNull(direction, "direction");
            item = Objects.requireNonNull(item, "item");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public enum FailureReason {
        NONE,
        CAPTURE_FAILED,
        NON_CONSERVING_ACTION,
        AMBIGUOUS_CUSTODY_FLOW,
        STALE_CURSOR_ESCROW,
        MULTIPLE_CURSOR_ESCROWS,
        COUNT_OVERFLOW
    }

    /** Atomic correlation result. Rejected results never carry transfers or escrow. */
    public record Result(
            boolean accepted,
            List<Transfer> transfers,
            Optional<CursorEscrow> nextEscrow,
            FailureReason failureReason) {
        public Result {
            transfers = List.copyOf(Objects.requireNonNull(transfers, "transfers"));
            nextEscrow = Objects.requireNonNull(nextEscrow, "nextEscrow");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            if (accepted && failureReason != FailureReason.NONE) {
                throw new IllegalArgumentException("Accepted results cannot have a failure reason");
            }
            if (!accepted && (!transfers.isEmpty() || nextEscrow.isPresent())) {
                throw new IllegalArgumentException("Rejected results cannot carry evidence or escrow");
            }
        }

        private static Result accepted(List<Transfer> transfers, Optional<CursorEscrow> nextEscrow) {
            return new Result(true, transfers, nextEscrow, FailureReason.NONE);
        }

        private static Result rejected(FailureReason reason) {
            return new Result(false, List.of(), Optional.empty(), reason);
        }
    }

    /** GF-NESTED: where a stack of the click sits. */
    public enum CustodyDomain {
        DEVICE,
        PLAYER,
        CURSOR
    }

    /**
     * GF-NESTED: the two stacks of one PICKUP click that an item-click override handled. The shell is the one that
     * kept its item and count (paired by place, item and count, never by components); the other stack is where X's
     * visible change must be. One of the two is always the cursor.
     */
    public record NestedClick(
            CustodyDomain shellDomain,
            CustodyDomain otherDomain,
            InventoryItemIdentity shellBefore,
            InventoryItemIdentity shellAfter,
            long shellCount,
            ItemShellContents.Reading contentsBefore,
            ItemShellContents.Reading contentsAfter) {
        public NestedClick {
            shellDomain = Objects.requireNonNull(shellDomain, "shellDomain");
            otherDomain = Objects.requireNonNull(otherDomain, "otherDomain");
            shellBefore = Objects.requireNonNull(shellBefore, "shellBefore");
            shellAfter = Objects.requireNonNull(shellAfter, "shellAfter");
            contentsBefore = Objects.requireNonNull(contentsBefore, "contentsBefore");
            contentsAfter = Objects.requireNonNull(contentsAfter, "contentsAfter");
        }
    }

    /**
     * GF-NESTED: how an accepted nested click moved X. The quantities say which custody each part took, so the role
     * resolver can give each part its role: {@code deposited} and {@code withdrawn} take the device stack's role,
     * {@code escrowWithdrawn} the escrow's role; {@code escrowReturned} writes nothing; {@code escrowGained} becomes
     * escrow with the device stack's role; {@code escrowOnShell} means the escrow followed the carried shell.
     */
    public record NestedFlow(
            NestedClick click,
            InventoryItemIdentity item,
            long quantity,
            boolean intoShell,
            long deposited,
            long withdrawn,
            long escrowWithdrawn,
            long escrowReturned,
            long escrowGained,
            boolean escrowOnShell) {
        public NestedFlow {
            click = Objects.requireNonNull(click, "click");
            item = Objects.requireNonNull(item, "item");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** GF-NESTED: why a nested click was refused; every refusal is logged by the caller. */
    public enum NestedRefusal {
        NONE,
        NOT_ONE_SHELL,
        SHELL_NOT_IN_PLACE,
        NO_ITEM,
        MORE_THAN_ONE_ITEM,
        ITEM_OUTSIDE_THE_CLICK,
        CONTENTS_UNREADABLE,
        CONTENTS_MISMATCH,
        ESCROWED_SHELL_WITH_PLAYER_ITEM,
        ESCROW_CONFLICT,
        STALE_CURSOR_ESCROW,
        COUNT_OVERFLOW
    }

    /** GF-NESTED: an accepted result always has its flow; a refused one has neither transfers nor escrow. */
    public record NestedResult(Result result, Optional<NestedFlow> flow, NestedRefusal refusal) {
        public NestedResult {
            result = Objects.requireNonNull(result, "result");
            flow = Objects.requireNonNull(flow, "flow");
            refusal = Objects.requireNonNull(refusal, "refusal");
            if (result.accepted() != flow.isPresent() || result.accepted() != (refusal == NestedRefusal.NONE)) {
                throw new IllegalArgumentException("An accepted nested result has a flow and no refusal");
            }
        }

        public boolean accepted() {
            return result.accepted();
        }

        private static NestedResult refused(NestedRefusal refusal) {
            return new NestedResult(
                    Result.rejected(FailureReason.NON_CONSERVING_ACTION), Optional.empty(), refusal);
        }
    }
}
