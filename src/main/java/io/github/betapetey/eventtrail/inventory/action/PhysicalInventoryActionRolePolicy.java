package io.github.betapetey.eventtrail.inventory.action;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.world.inventory.ClickType;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable action-intent constraint applied after custody/role correlation.
 *
 * <p>Before/after totals alone cannot distinguish a player transfer from an
 * equal component-exact processing mutation between machine roles. The server
 * click supplies the missing proof: a direct device click must affect that
 * role, while indirect movement is limited to roles whose real slots accept or
 * release the item. Ambiguous paths fail closed.</p>
 */
public final class PhysicalInventoryActionRolePolicy {
    private PhysicalInventoryActionRolePolicy() {
    }

    public static boolean allows(
            PhysicalInventoryRoleResolver.RoleTransfer transfer,
            InventorySlotRole clickedRole,
            ClickType clickType,
            Optional<PhysicalInventoryRoleResolver.RoleEscrow> priorEscrow,
            Set<InventorySlotRole> acceptingDepositRoles,
            Set<InventorySlotRole> extractableWithdrawalRoles) {
        Objects.requireNonNull(transfer, "transfer");
        Objects.requireNonNull(clickedRole, "clickedRole");
        Objects.requireNonNull(clickType, "clickType");
        Objects.requireNonNull(priorEscrow, "priorEscrow");
        Objects.requireNonNull(acceptingDepositRoles, "acceptingDepositRoles");
        Objects.requireNonNull(extractableWithdrawalRoles, "extractableWithdrawalRoles");

        if (transfer.direction() == TransactionDirection.DEPOSIT
                && (transfer.role() == InventorySlotRole.MACHINE_OUTPUT
                || transfer.role() == InventorySlotRole.MACHINE_BYPRODUCT)) {
            return false;
        }

        if (clickedRole.isRealDeviceCustody() && clickType != ClickType.PICKUP_ALL) {
            return transfer.role() == clickedRole;
        }

        if (transfer.direction() == TransactionDirection.WITHDRAWAL
                && priorEscrow.filter(escrow ->
                        escrow.item().equals(transfer.item())
                                && escrow.role() == transfer.role()
                                && escrow.quantity() >= transfer.quantity()).isPresent()) {
            return true;
        }

        return (transfer.direction() == TransactionDirection.DEPOSIT
                ? acceptingDepositRoles
                : extractableWithdrawalRoles).contains(transfer.role());
    }
}
