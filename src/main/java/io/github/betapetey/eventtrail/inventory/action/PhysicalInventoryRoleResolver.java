package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves component-exact physical custody transfers to real device slot roles.
 *
 * <p>This resolver is deliberately stricter than aggregate custody correlation.
 * A role is attached only when the per-role device deltas prove the complete
 * transfer. Opposing role deltas, incomplete role views, and ambiguous cursor
 * provenance reject the whole action without returning partial evidence.</p>
 */
public final class PhysicalInventoryRoleResolver {
    private static final Comparator<InventoryItemIdentity> IDENTITY_ORDER = Comparator
            .comparing(InventoryItemIdentity::registryId)
            .thenComparing(InventoryItemIdentity::displayName)
            .thenComparingInt(InventoryItemIdentity::hashCode);

    private PhysicalInventoryRoleResolver() {
    }

    public static Result resolve(
            PhysicalInventoryActionCorrelator.Result correlation,
            RoleSnapshot before,
            RoleSnapshot after,
            Optional<RoleEscrow> priorEscrow) {
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(priorEscrow, "priorEscrow");

        if (!correlation.accepted()) {
            return Result.rejected(FailureReason.CORRELATION_REJECTED);
        }
        if (containsNonCustodyRole(before) || containsNonCustodyRole(after)) {
            return Result.rejected(FailureReason.NON_CUSTODY_ROLE);
        }

        Optional<PhysicalInventoryActionCorrelator.CursorEscrow> nextAggregateEscrow =
                correlation.nextEscrow();
        if (!escrowShapeIsConsistent(priorEscrow, nextAggregateEscrow)) {
            return Result.rejected(FailureReason.ESCROW_MISMATCH);
        }

        Map<InventoryItemIdentity, DirectionTotals> transfersByItem = new HashMap<>();
        try {
            for (PhysicalInventoryActionCorrelator.Transfer transfer : correlation.transfers()) {
                DirectionTotals totals = transfersByItem.computeIfAbsent(
                        transfer.item(), ignored -> new DirectionTotals());
                totals.add(transfer.direction(), transfer.quantity());
            }
        } catch (ArithmeticException overflow) {
            return Result.rejected(FailureReason.COUNT_OVERFLOW);
        }

        Set<InventoryItemIdentity> identities = new HashSet<>();
        identities.addAll(before.identities());
        identities.addAll(after.identities());
        identities.addAll(transfersByItem.keySet());
        priorEscrow.map(RoleEscrow::item).ifPresent(identities::add);
        nextAggregateEscrow.map(PhysicalInventoryActionCorrelator.CursorEscrow::item)
                .ifPresent(identities::add);

        List<InventoryItemIdentity> orderedIdentities = new ArrayList<>(identities);
        orderedIdentities.sort(IDENTITY_ORDER);
        List<RoleTransfer> resolved = new ArrayList<>();
        Optional<RoleEscrow> nextRoleEscrow = Optional.empty();

        try {
            for (InventoryItemIdentity item : orderedIdentities) {
                DirectionTotals transferTotals = transfersByItem.getOrDefault(item, new DirectionTotals());
                long deposits = transferTotals.deposits;
                long withdrawals = transferTotals.withdrawals;

                long priorEscrowQuantity = roleEscrowQuantity(priorEscrow, item);
                long nextEscrowQuantity = aggregateEscrowQuantity(nextAggregateEscrow, item);
                long escrowDelta = Math.subtractExact(nextEscrowQuantity, priorEscrowQuantity);

                RoleDeltas roleDeltas = roleDeltas(before, after, item);
                if (!roleDeltas.gains.isEmpty() && !roleDeltas.losses.isEmpty()) {
                    return Result.rejected(FailureReason.OPPOSING_ROLE_DELTAS);
                }

                long expectedDeviceDelta = Math.subtractExact(
                        Math.subtractExact(deposits, withdrawals),
                        escrowDelta);
                if (roleDeltas.net != expectedDeviceDelta) {
                    return Result.rejected(FailureReason.INCOMPLETE_ROLE_TOTALS);
                }

                Optional<InventorySlotRole> priorRole = priorEscrow
                        .filter(escrow -> escrow.item().equals(item))
                        .map(RoleEscrow::role);
                ItemResolution itemResolution = resolveItem(
                        item,
                        deposits,
                        withdrawals,
                        priorEscrowQuantity,
                        nextEscrowQuantity,
                        priorRole,
                        roleDeltas);
                if (!itemResolution.accepted) {
                    return Result.rejected(itemResolution.failureReason);
                }
                resolved.addAll(itemResolution.transfers);

                if (nextEscrowQuantity > 0L) {
                    if (nextRoleEscrow.isPresent() || itemResolution.nextEscrowRole.isEmpty()) {
                        return Result.rejected(FailureReason.ESCROW_MISMATCH);
                    }
                    nextRoleEscrow = Optional.of(new RoleEscrow(
                            item,
                            nextEscrowQuantity,
                            itemResolution.nextEscrowRole.orElseThrow()));
                }
            }
        } catch (ArithmeticException overflow) {
            return Result.rejected(FailureReason.COUNT_OVERFLOW);
        }

        if (nextAggregateEscrow.isPresent() != nextRoleEscrow.isPresent()) {
            return Result.rejected(FailureReason.ESCROW_MISMATCH);
        }
        return Result.accepted(resolved, nextRoleEscrow);
    }

    /**
     * GF-NESTED: the role path for a nested click the correlator accepted. Every device part of the click is the
     * clicked slot's: the shell when it sits in a device slot, or X's slot when the shell is on the cursor, so
     * {@code clickedRole} is that slot's role. The per-role device totals must prove exactly the flow: the shell's
     * relabel (if any) and X's visible change in that role only, and no change of any other identity in any role.
     * Deposits and immediate withdrawals take that role, a completed escrow withdrawal the escrow's role, and the
     * escrow follows the shell (relabelled to the shell's new identity, same role) when the carried shell was escrow.
     */
    public static Result resolveNested(
            PhysicalInventoryActionCorrelator.NestedResult nested,
            RoleSnapshot before,
            RoleSnapshot after,
            Optional<RoleEscrow> priorEscrow,
            InventorySlotRole clickedRole) {
        Objects.requireNonNull(nested, "nested");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(priorEscrow, "priorEscrow");
        Objects.requireNonNull(clickedRole, "clickedRole");
        if (!nested.accepted() || nested.flow().isEmpty()) {
            return Result.rejected(FailureReason.CORRELATION_REJECTED);
        }
        if (containsNonCustodyRole(before) || containsNonCustodyRole(after)) {
            return Result.rejected(FailureReason.NON_CUSTODY_ROLE);
        }
        PhysicalInventoryActionCorrelator.NestedFlow flow = nested.flow().orElseThrow();
        PhysicalInventoryActionCorrelator.NestedClick click = flow.click();
        boolean shellInDevice = click.shellDomain() == PhysicalInventoryActionCorrelator.CustodyDomain.DEVICE;
        boolean itemInDevice = click.otherDomain() == PhysicalInventoryActionCorrelator.CustodyDomain.DEVICE;
        if ((shellInDevice || itemInDevice) && !clickedRole.isRealDeviceCustody()) {
            return Result.rejected(FailureReason.NON_CUSTODY_ROLE);
        }

        try {
            Set<InventoryItemIdentity> identities = new HashSet<>();
            identities.addAll(before.identities());
            identities.addAll(after.identities());
            identities.add(flow.item());
            identities.add(click.shellBefore());
            identities.add(click.shellAfter());
            boolean relabelled = !click.shellBefore().equals(click.shellAfter());
            for (InventoryItemIdentity identity : identities) {
                long expected = 0L;
                if (shellInDevice && relabelled && identity.equals(click.shellBefore())) {
                    expected = Math.negateExact(click.shellCount());
                } else if (shellInDevice && relabelled && identity.equals(click.shellAfter())) {
                    expected = click.shellCount();
                } else if (itemInDevice && identity.equals(flow.item())) {
                    expected = flow.intoShell() ? Math.negateExact(flow.quantity()) : flow.quantity();
                }
                for (InventorySlotRole role : InventorySlotRole.values()) {
                    long delta = Math.subtractExact(after.count(role, identity), before.count(role, identity));
                    if (delta != (role == clickedRole ? expected : 0L)) {
                        return Result.rejected(FailureReason.INCOMPLETE_ROLE_TOTALS);
                    }
                }
            }
        } catch (ArithmeticException overflow) {
            return Result.rejected(FailureReason.COUNT_OVERFLOW);
        }

        Optional<RoleEscrow> priorOnItem = priorEscrow.filter(escrow -> escrow.item().equals(flow.item()));
        if ((flow.escrowWithdrawn() > 0L || flow.escrowReturned() > 0L)
                && (priorOnItem.isEmpty()
                || priorOnItem.orElseThrow().quantity() < Math.max(flow.escrowWithdrawn(), flow.escrowReturned()))) {
            return Result.rejected(FailureReason.ESCROW_MISMATCH);
        }
        List<RoleTransfer> transfers = new ArrayList<>(2);
        if (flow.deposited() > 0L) {
            transfers.add(new RoleTransfer(TransactionDirection.DEPOSIT, flow.item(), flow.deposited(), clickedRole));
        }
        if (flow.withdrawn() > 0L) {
            transfers.add(new RoleTransfer(TransactionDirection.WITHDRAWAL, flow.item(), flow.withdrawn(), clickedRole));
        }
        if (flow.escrowWithdrawn() > 0L) {
            transfers.add(new RoleTransfer(
                    TransactionDirection.WITHDRAWAL,
                    flow.item(),
                    flow.escrowWithdrawn(),
                    priorOnItem.orElseThrow().role()));
        }

        Optional<PhysicalInventoryActionCorrelator.CursorEscrow> next = nested.result().nextEscrow();
        if (next.isEmpty()) return Result.accepted(transfers, Optional.empty());
        PhysicalInventoryActionCorrelator.CursorEscrow aggregate = next.orElseThrow();
        Optional<InventorySlotRole> role;
        if (flow.escrowOnShell()) {
            role = priorEscrow.filter(escrow -> escrow.item().equals(click.shellBefore())
                    && escrow.quantity() == aggregate.quantity()).map(RoleEscrow::role);
        } else if (flow.escrowGained() > 0L) {
            role = priorEscrow.isEmpty()
                    || (priorOnItem.isPresent() && priorOnItem.orElseThrow().role() == clickedRole)
                    ? Optional.of(clickedRole) : Optional.empty();
            if (role.isEmpty()) return Result.rejected(FailureReason.AMBIGUOUS_ESCROW_ORIGIN);
        } else {
            role = priorEscrow.filter(escrow -> escrow.item().equals(aggregate.item())).map(RoleEscrow::role);
        }
        if (role.isEmpty()) return Result.rejected(FailureReason.ESCROW_MISMATCH);
        return Result.accepted(transfers, Optional.of(new RoleEscrow(aggregate.item(), aggregate.quantity(), role.orElseThrow())));
    }

    private static ItemResolution resolveItem(
            InventoryItemIdentity item,
            long deposits,
            long withdrawals,
            long priorEscrow,
            long nextEscrow,
            Optional<InventorySlotRole> priorRole,
            RoleDeltas deltas) {
        long escrowIncrease = Math.max(0L, Math.subtractExact(nextEscrow, priorEscrow));
        long escrowDecrease = Math.max(0L, Math.subtractExact(priorEscrow, nextEscrow));
        List<RoleTransfer> resolved = new ArrayList<>();
        Optional<InventorySlotRole> nextRole = Optional.empty();

        if (priorEscrow > 0L && priorRole.isEmpty()) {
            return ItemResolution.rejected(FailureReason.ESCROW_MISMATCH);
        }

        if (!deltas.losses.isEmpty()) {
            if (deposits != 0L) {
                return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
            }
            if (withdrawals < escrowDecrease) {
                return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
            }

            long directWithdrawals = Math.subtractExact(withdrawals, escrowDecrease);
            EnumMap<InventorySlotRole, Long> remainingLosses = new EnumMap<>(deltas.losses);
            if (escrowIncrease > 0L) {
                Optional<InventorySlotRole> origin = findEscrowOrigin(
                        remainingLosses,
                        escrowIncrease,
                        priorRole);
                if (origin.isEmpty()) {
                    return ItemResolution.rejected(FailureReason.AMBIGUOUS_ESCROW_ORIGIN);
                }
                InventorySlotRole originRole = origin.orElseThrow();
                subtractFromRole(remainingLosses, originRole, escrowIncrease);
                nextRole = Optional.of(originRole);
            } else if (nextEscrow > 0L) {
                nextRole = priorRole;
            }

            long roleWithdrawalTotal = sum(remainingLosses);
            if (roleWithdrawalTotal != directWithdrawals) {
                return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
            }
            addRoleTransfers(resolved, TransactionDirection.WITHDRAWAL, item, remainingLosses);
            if (escrowDecrease > 0L) {
                resolved.add(new RoleTransfer(
                        TransactionDirection.WITHDRAWAL,
                        item,
                        escrowDecrease,
                        priorRole.orElseThrow()));
            }
            return ItemResolution.accepted(resolved, nextRole);
        }

        if (!deltas.gains.isEmpty()) {
            if (escrowIncrease != 0L || withdrawals > escrowDecrease) {
                return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
            }

            long escrowReturnedToDevice = Math.subtractExact(escrowDecrease, withdrawals);
            if (escrowReturnedToDevice < 0L
                    || sum(deltas.gains) != Math.addExact(deposits, escrowReturnedToDevice)) {
                return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
            }

            if (withdrawals > 0L) {
                resolved.add(new RoleTransfer(
                        TransactionDirection.WITHDRAWAL,
                        item,
                        withdrawals,
                        priorRole.orElseThrow()));
            }
            if (deposits > 0L) {
                if (escrowReturnedToDevice == 0L) {
                    addRoleTransfers(resolved, TransactionDirection.DEPOSIT, item, deltas.gains);
                } else if (deltas.gains.size() == 1) {
                    InventorySlotRole role = deltas.gains.keySet().iterator().next();
                    resolved.add(new RoleTransfer(TransactionDirection.DEPOSIT, item, deposits, role));
                } else {
                    return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
                }
            }
            if (nextEscrow > 0L) {
                nextRole = priorRole;
            }
            return ItemResolution.accepted(resolved, nextRole);
        }

        if (deposits != 0L || escrowIncrease != 0L || withdrawals != escrowDecrease) {
            return ItemResolution.rejected(FailureReason.AMBIGUOUS_ROLE_FLOW);
        }
        if (withdrawals > 0L) {
            resolved.add(new RoleTransfer(
                    TransactionDirection.WITHDRAWAL,
                    item,
                    withdrawals,
                    priorRole.orElseThrow()));
        }
        if (nextEscrow > 0L) {
            nextRole = priorRole;
        }
        return ItemResolution.accepted(resolved, nextRole);
    }

    private static Optional<InventorySlotRole> findEscrowOrigin(
            EnumMap<InventorySlotRole, Long> losses,
            long increase,
            Optional<InventorySlotRole> priorRole) {
        if (priorRole.isPresent()) {
            InventorySlotRole role = priorRole.orElseThrow();
            return losses.getOrDefault(role, 0L) >= increase ? priorRole : Optional.empty();
        }
        InventorySlotRole candidate = null;
        for (Map.Entry<InventorySlotRole, Long> entry : losses.entrySet()) {
            if (entry.getValue() < increase) continue;
            if (candidate != null) return Optional.empty();
            candidate = entry.getKey();
        }
        return Optional.ofNullable(candidate);
    }

    private static void subtractFromRole(
            EnumMap<InventorySlotRole, Long> values,
            InventorySlotRole role,
            long quantity) {
        long remaining = Math.subtractExact(values.getOrDefault(role, 0L), quantity);
        if (remaining == 0L) values.remove(role);
        else values.put(role, remaining);
    }

    private static void addRoleTransfers(
            List<RoleTransfer> target,
            TransactionDirection direction,
            InventoryItemIdentity item,
            EnumMap<InventorySlotRole, Long> quantities) {
        quantities.forEach((role, quantity) -> {
            if (quantity > 0L) target.add(new RoleTransfer(direction, item, quantity, role));
        });
    }

    private static RoleDeltas roleDeltas(
            RoleSnapshot before,
            RoleSnapshot after,
            InventoryItemIdentity item) {
        EnumMap<InventorySlotRole, Long> gains = new EnumMap<>(InventorySlotRole.class);
        EnumMap<InventorySlotRole, Long> losses = new EnumMap<>(InventorySlotRole.class);
        long net = 0L;
        for (InventorySlotRole role : InventorySlotRole.values()) {
            long delta = Math.subtractExact(after.count(role, item), before.count(role, item));
            net = Math.addExact(net, delta);
            if (delta > 0L) gains.put(role, delta);
            else if (delta < 0L) losses.put(role, Math.negateExact(delta));
        }
        return new RoleDeltas(gains, losses, net);
    }

    private static long roleEscrowQuantity(Optional<RoleEscrow> escrow, InventoryItemIdentity item) {
        return escrow.filter(value -> value.item().equals(item))
                .map(RoleEscrow::quantity)
                .orElse(0L);
    }

    private static long aggregateEscrowQuantity(
            Optional<PhysicalInventoryActionCorrelator.CursorEscrow> escrow,
            InventoryItemIdentity item) {
        return escrow.filter(value -> value.item().equals(item))
                .map(PhysicalInventoryActionCorrelator.CursorEscrow::quantity)
                .orElse(0L);
    }

    private static boolean escrowShapeIsConsistent(
            Optional<RoleEscrow> prior,
            Optional<PhysicalInventoryActionCorrelator.CursorEscrow> next) {
        return prior.map(RoleEscrow::role).map(InventorySlotRole::isRealDeviceCustody).orElse(true)
                && next.map(value -> value.quantity() > 0L).orElse(true);
    }

    private static boolean containsNonCustodyRole(RoleSnapshot snapshot) {
        return snapshot.totals().keySet().stream().anyMatch(role -> !role.isRealDeviceCustody());
    }

    private static long sum(Map<InventorySlotRole, Long> values) {
        long sum = 0L;
        for (long value : values.values()) sum = Math.addExact(sum, value);
        return sum;
    }

    /** Component-exact device totals, separated by semantic real-slot role. */
    public record RoleSnapshot(Map<InventorySlotRole, Map<InventoryItemIdentity, Long>> totals) {
        public RoleSnapshot {
            Objects.requireNonNull(totals, "totals");
            EnumMap<InventorySlotRole, Map<InventoryItemIdentity, Long>> copy =
                    new EnumMap<>(InventorySlotRole.class);
            totals.forEach((role, itemTotals) -> {
                Objects.requireNonNull(role, "role");
                Objects.requireNonNull(itemTotals, "itemTotals");
                Map<InventoryItemIdentity, Long> copiedItems = new HashMap<>();
                itemTotals.forEach((item, quantity) -> {
                    Objects.requireNonNull(item, "item");
                    Objects.requireNonNull(quantity, "quantity");
                    if (quantity <= 0L) {
                        throw new IllegalArgumentException("Role totals must be positive");
                    }
                    copiedItems.put(item, quantity);
                });
                if (!copiedItems.isEmpty()) copy.put(role, Map.copyOf(copiedItems));
            });
            totals = Map.copyOf(copy);
        }

        public static RoleSnapshot empty() {
            return new RoleSnapshot(Map.of());
        }

        public long count(InventorySlotRole role, InventoryItemIdentity item) {
            return totals.getOrDefault(role, Map.of()).getOrDefault(item, 0L);
        }

        private Set<InventoryItemIdentity> identities() {
            Set<InventoryItemIdentity> identities = new HashSet<>();
            totals.values().forEach(values -> identities.addAll(values.keySet()));
            return identities;
        }
    }

    /** Role provenance carried with aggregate cursor escrow between actions. */
    public record RoleEscrow(
            InventoryItemIdentity item,
            long quantity,
            InventorySlotRole role) {
        public RoleEscrow {
            item = Objects.requireNonNull(item, "item");
            role = Objects.requireNonNull(role, "role");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
            if (!role.isRealDeviceCustody()) {
                throw new IllegalArgumentException("Escrow requires a real device-custody role");
            }
        }
    }

    /** One proven transfer resolved to a real device-custody role. */
    public record RoleTransfer(
            TransactionDirection direction,
            InventoryItemIdentity item,
            long quantity,
            InventorySlotRole role) {
        public RoleTransfer {
            direction = Objects.requireNonNull(direction, "direction");
            item = Objects.requireNonNull(item, "item");
            role = Objects.requireNonNull(role, "role");
            if (quantity <= 0L) throw new IllegalArgumentException("quantity must be positive");
            if (!role.isRealDeviceCustody()) {
                throw new IllegalArgumentException("Transfer requires a real device-custody role");
            }
        }
    }

    public enum FailureReason {
        NONE,
        CORRELATION_REJECTED,
        NON_CUSTODY_ROLE,
        OPPOSING_ROLE_DELTAS,
        INCOMPLETE_ROLE_TOTALS,
        AMBIGUOUS_ROLE_FLOW,
        AMBIGUOUS_ESCROW_ORIGIN,
        ESCROW_MISMATCH,
        COUNT_OVERFLOW
    }

    /** Atomic result: rejection never exposes partial role evidence or escrow. */
    public record Result(
            boolean accepted,
            List<RoleTransfer> transfers,
            Optional<RoleEscrow> nextEscrow,
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

        private static Result accepted(List<RoleTransfer> transfers, Optional<RoleEscrow> nextEscrow) {
            return new Result(true, transfers, nextEscrow, FailureReason.NONE);
        }

        private static Result rejected(FailureReason reason) {
            return new Result(false, List.of(), Optional.empty(), reason);
        }
    }

    private static final class DirectionTotals {
        private long deposits;
        private long withdrawals;

        private void add(TransactionDirection direction, long quantity) {
            if (direction == TransactionDirection.DEPOSIT) {
                deposits = Math.addExact(deposits, quantity);
            } else {
                withdrawals = Math.addExact(withdrawals, quantity);
            }
        }
    }

    private record RoleDeltas(
            EnumMap<InventorySlotRole, Long> gains,
            EnumMap<InventorySlotRole, Long> losses,
            long net) {
    }

    private record ItemResolution(
            boolean accepted,
            List<RoleTransfer> transfers,
            Optional<InventorySlotRole> nextEscrowRole,
            FailureReason failureReason) {
        private static ItemResolution accepted(
                List<RoleTransfer> transfers,
                Optional<InventorySlotRole> nextEscrowRole) {
            return new ItemResolution(true, List.copyOf(transfers), nextEscrowRole, FailureReason.NONE);
        }

        private static ItemResolution rejected(FailureReason failureReason) {
            return new ItemResolution(false, List.of(), Optional.empty(), failureReason);
        }
    }
}
