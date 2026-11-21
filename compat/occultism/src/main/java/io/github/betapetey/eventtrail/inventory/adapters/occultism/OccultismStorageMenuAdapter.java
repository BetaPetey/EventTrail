package io.github.betapetey.eventtrail.inventory.adapters.occultism;

import com.klikli_dev.occultism.common.container.storage.*;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.inventory.Slot;

/**
 * Built on any installed Occultism build (R1c). Known limitation on a changed build: if its storage menu no longer passes
 * the binding's shape checks (for example the exact controller and handler classes), no binding is made and Occultism records nothing
 * for the whole run while {@code match} answers SUPPORTED (Advisor M-3). GF-WARNINGS (B48): each such menu is counted,
 * with one warning per site after each server start, and the key stays ON.
 */
public final class OccultismStorageMenuAdapter implements MenuTransactionAdapter {
    /**
     * Provider id written into rows. Row data, kept byte for byte (R1c, Q3): Occultism has one path, so one label,
     * the one the newest tested build used.
     */
    public static final String ID = "occultism-1.224.4-dimensional-item-operation";
    /** Prefix of a controller target's backing key. Row data, kept byte for byte (R1c, Q3). */
    static final String BACKING_NAMESPACE = "occultism:1.224.4:controller:";
    @Override public String adapterId() { return ID; }
    @Override public boolean exact() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.AUTHORITATIVE_OPERATION; }
    @Override public boolean usesCapturedAccessAuthority() { return true; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof StorageControllerContainerBase)) return AdapterMatch.NO_MATCH;
        Class<?> type = context.menu().getClass();
        return type == StorageControllerContainer.class || type == StableWormholeContainer.class || type == StorageRemoteContainer.class
                ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }
    static Optional<OccultismStorageBinding> current(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof OccultismStorageMenuAccess access)) return Optional.empty();
        var binding = access.eventtrail$occultismBinding();
        return binding != null && binding.player == context.player() && binding.menu == context.menu() && binding.current()
                ? Optional.of(binding) : Optional.empty();
    }
    @Override public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        return current(context).flatMap(OccultismStorageBinding::target);
    }
    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(binding -> binding.target().map(target -> new MenuAccessTarget(target, binding.targetId())));
    }
    @Override public Optional<BooleanSupplier> captureAccessGuard(MenuAdapterContext context) {
        return current(context).map(binding -> () -> current(context).orElse(null) == binding);
    }
    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        if (context == null || context.player() == null || slot == null || slot.index != index) return InventorySlotRole.UNKNOWN;
        if (index >= 11 && index < 47 && slot.container == context.player().getInventory()
                && slot.getContainerSlot() == OccultismStorageBinding.playerIndex(index)) return InventorySlotRole.PLAYER;
        if (index == 0) return InventorySlotRole.CRAFTING_OUTPUT;
        if (index >= 1 && index <= 9) return InventorySlotRole.CRAFTING_INPUT;
        return index == 10 ? InventorySlotRole.CONFIGURATION : InventorySlotRole.UNKNOWN;
    }
}
