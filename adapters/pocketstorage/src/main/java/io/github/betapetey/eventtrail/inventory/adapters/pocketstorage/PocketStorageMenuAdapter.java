package io.github.betapetey.eventtrail.inventory.adapters.pocketstorage;

import com.flanks255.pocketstorage.gui.LockableSlot;
import com.flanks255.pocketstorage.gui.PSUContainer;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import net.minecraft.world.inventory.Slot;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Exact finite Pocket Storage item target; virtual cells never become menu Slots.
 *
 * <p>Built on any installed Pocket Storage build (R1c). Known limitation on a changed build: if its menu no longer
 * passes the binding's shape checks (for example the LockableSlots), no binding is made and Pocket Storage records
 * nothing for the whole run while {@code match} answers SUPPORTED (Advisor M-3). GF-WARNINGS (B48): each such menu is
 * counted, with one warning per site after each server start, and the key stays ON.</p>
 */
public final class PocketStorageMenuAdapter implements MenuTransactionAdapter {
    public static final String ID = "pocketstorage-1.2.5-b3-exact-operation";
    @Override public String adapterId() { return ID; }
    @Override public boolean exact() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.AUTHORITATIVE_OPERATION; }
    @Override public boolean usesCapturedAccessAuthority() { return true; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof PSUContainer)) return AdapterMatch.NO_MATCH;
        return context.menu().getClass() == PSUContainer.class ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }
    static Optional<PocketStorageBinding> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.menu() == null
                || context.menu().getClass() != PSUContainer.class
                || !(context.menu() instanceof PocketStorageMenuAccess access)) return Optional.empty();
        PocketStorageBinding binding = access.eventtrail$pocketBinding();
        return binding != null && binding.player == context.player() && binding.menu == context.menu()
                && binding.current() ? Optional.of(binding) : Optional.empty();
    }
    @Override public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        return current(context).flatMap(PocketStorageBinding::target);
    }
    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(binding -> binding.target()
                .map(target -> new MenuAccessTarget(target, binding.itemId())));
    }
    @Override public Optional<BooleanSupplier> captureAccessGuard(MenuAdapterContext context) {
        return current(context).map(binding -> () -> current(context).orElse(null) == binding);
    }
    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        return context != null && context.player() != null && slot != null && slot.getClass() == LockableSlot.class
                && index >= 0 && index < 36 && slot.index == index && slot.getContainerSlot() == index
                && slot.container == context.player().getInventory() ? InventorySlotRole.PLAYER : InventorySlotRole.UNKNOWN;
    }
}
