package io.github.betapetey.eventtrail.events;

import io.github.betapetey.eventtrail.inventory.action.ItemShellContents;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionCorrelator;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionCorrelator.CustodyDomain;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryRoleResolver;
import io.github.betapetey.eventtrail.inventory.action.SameStackRule;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * GF-NESTED: the tracker's two rules for a click the component-exact correlation rejected.
 *
 * <ul>
 *   <li>{@link #resolve}: the second pass. Only for a PICKUP click with an item-click override proof for the clicked
 *   slot; it builds the click's shell and other stack from the proof (paired by place, item and count, never by
 *   components) and runs the correlator's nested pass and then the role resolver's nested path. Every refusal has a
 *   reason, which the tracker logs.</li>
 *   <li>{@link #escrowLeftTheCursor}: the rule apotheosis#8 asks for. When a PICKUP click on a slot of the player's
 *   inventory is rejected, the device totals did not change in it, and part of the pending cursor escrow (intact
 *   when the click began) is no longer on the cursor, that part left the device in an earlier accepted click and did
 *   not return: one withdrawal for it, with the escrow's role. Not when the same item with other components is on
 *   the cursor after the click (the carried container changed in place and is still carried).</li>
 * </ul>
 */
final class NestedClickCustody {
    private NestedClickCustody() {
    }

    /** The second pass's answer: accepted with both results, or refused with a reason. */
    record Outcome(
            Optional<PhysicalInventoryActionCorrelator.Result> custody,
            Optional<PhysicalInventoryRoleResolver.Result> roles,
            String reason) {
        Outcome {
            custody = Objects.requireNonNull(custody, "custody");
            roles = Objects.requireNonNull(roles, "roles");
            reason = Objects.requireNonNull(reason, "reason");
            if (custody.isPresent() != roles.isPresent()) {
                throw new IllegalArgumentException("An accepted outcome has both results");
            }
        }

        boolean accepted() {
            return custody.isPresent();
        }

        private static Outcome refused(String reason) {
            return new Outcome(Optional.empty(), Optional.empty(), reason);
        }
    }

    static Outcome resolve(
            ItemClickOverrideProof.Proof proof,
            InventorySlotRole proofSlotRole,
            ClickType clickType,
            InventorySlotRole clickedRole,
            int clickedSlotIndex,
            PhysicalInventoryActionCorrelator.Snapshot before,
            PhysicalInventoryActionCorrelator.Snapshot after,
            Optional<PhysicalInventoryActionCorrelator.CursorEscrow> priorEscrow,
            PhysicalInventoryRoleResolver.RoleSnapshot rolesBefore,
            PhysicalInventoryRoleResolver.RoleSnapshot rolesAfter,
            Optional<PhysicalInventoryRoleResolver.RoleEscrow> priorRoleEscrow) {
        if (clickType != ClickType.PICKUP) return Outcome.refused("NOT_A_PICKUP");
        // The proof's slot must be the very menu slot the click names, not only a slot with the same role.
        if (proof.menuSlotIndex() != clickedSlotIndex || proofSlotRole != clickedRole) {
            return Outcome.refused("PROOF_SLOT_NOT_THE_CLICKED_SLOT");
        }

        CustodyDomain slotDomain;
        if (proof.playerInventorySlot()) {
            if (clickedRole != InventorySlotRole.PLAYER) return Outcome.refused("CLICKED_SLOT_ROLE_UNPROVEN");
            slotDomain = CustodyDomain.PLAYER;
        } else if (clickedRole.isRealDeviceCustody()) {
            slotDomain = CustodyDomain.DEVICE;
        } else {
            return Outcome.refused("CLICKED_SLOT_NOT_CUSTODY");
        }

        boolean slotKept = SameStackRule.keptItemAndCount(proof.clickedBefore(), proof.clickedAfter());
        boolean carriedKept = SameStackRule.keptItemAndCount(proof.carriedBefore(), proof.carriedAfter());
        if (slotKept == carriedKept) return Outcome.refused("NOT_ONE_SHELL");
        ItemStack shellBefore = slotKept ? proof.clickedBefore() : proof.carriedBefore();
        ItemStack shellAfter = slotKept ? proof.clickedAfter() : proof.carriedAfter();
        CustodyDomain shellDomain = slotKept ? slotDomain : CustodyDomain.CURSOR;
        CustodyDomain otherDomain = slotKept ? CustodyDomain.CURSOR : slotDomain;

        InventoryItemIdentity shellBeforeIdentity;
        InventoryItemIdentity shellAfterIdentity;
        try {
            shellBeforeIdentity = InventoryItemIdentity.of(shellBefore);
            shellAfterIdentity = InventoryItemIdentity.of(shellAfter);
        } catch (IllegalArgumentException unidentified) {
            return Outcome.refused("SHELL_UNIDENTIFIED");
        }
        PhysicalInventoryActionCorrelator.NestedClick click = new PhysicalInventoryActionCorrelator.NestedClick(
                shellDomain,
                otherDomain,
                shellBeforeIdentity,
                shellAfterIdentity,
                shellBefore.getCount(),
                ItemShellContents.read(shellBefore),
                ItemShellContents.read(shellAfter));

        PhysicalInventoryActionCorrelator.NestedResult nested =
                PhysicalInventoryActionCorrelator.correlateNested(before, after, priorEscrow, click);
        if (!nested.accepted()) return Outcome.refused(nested.refusal().name());
        PhysicalInventoryRoleResolver.Result roles = PhysicalInventoryRoleResolver.resolveNested(
                nested, rolesBefore, rolesAfter, priorRoleEscrow, clickedRole);
        if (!roles.accepted()) return Outcome.refused("ROLES_" + roles.failureReason().name());
        return new Outcome(Optional.of(nested.result()), Optional.of(roles), "NONE");
    }

    static Optional<PhysicalInventoryRoleResolver.RoleTransfer> escrowLeftTheCursor(
            ClickType clickType,
            InventorySlotRole clickedRole,
            PhysicalInventoryActionCorrelator.Snapshot before,
            PhysicalInventoryActionCorrelator.Snapshot after,
            Optional<PhysicalInventoryActionCorrelator.CursorEscrow> escrow,
            Optional<PhysicalInventoryRoleResolver.RoleEscrow> roleEscrow) {
        if (clickType != ClickType.PICKUP || clickedRole != InventorySlotRole.PLAYER) return Optional.empty();
        if (escrow.isEmpty() || roleEscrow.isEmpty()) return Optional.empty();
        PhysicalInventoryActionCorrelator.CursorEscrow aggregate = escrow.orElseThrow();
        PhysicalInventoryRoleResolver.RoleEscrow role = roleEscrow.orElseThrow();
        if (!role.item().equals(aggregate.item()) || role.quantity() != aggregate.quantity()) return Optional.empty();
        if (!before.device().equals(after.device())) return Optional.empty();
        if (before.cursor().getOrDefault(aggregate.item(), 0L) < aggregate.quantity()) return Optional.empty();
        for (InventoryItemIdentity carried : after.cursor().keySet()) {
            // The same item with other components is still on the cursor (a carried container relabelled in place):
            // it has not left, so no withdrawal is proven.
            if (!carried.equals(aggregate.item()) && SameStackRule.sameItem(carried, aggregate.item())) {
                return Optional.empty();
            }
        }
        long stillCarried = Math.min(aggregate.quantity(), after.cursor().getOrDefault(aggregate.item(), 0L));
        long left = aggregate.quantity() - stillCarried;
        if (left <= 0L) return Optional.empty();
        return Optional.of(new PhysicalInventoryRoleResolver.RoleTransfer(
                TransactionDirection.WITHDRAWAL, aggregate.item(), left, role.role()));
    }
}
