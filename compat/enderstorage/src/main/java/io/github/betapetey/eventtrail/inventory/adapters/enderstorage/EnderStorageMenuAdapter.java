package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.storage.EnderItemStorage;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import net.minecraft.world.inventory.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Sole exact shared-channel producer. Generic physical backing remains unable to admit a channel.
 *
 * <p>Built on any installed Ender Storage build (R1c). Known limitations on a changed build: if its menu layout no
 * longer passes the binding's shape checks, no binding is made and Ender Storage records nothing for the whole run
 * while {@code match} answers SUPPORTED (Advisor M-3). GF-WARNINGS (B48): each such menu is counted, with one warning
 * per site after each server start, and the key stays ON. The
 * nested-automation witness ({@code EnderItemCapabilityMixin}) hooks NeoForge's {@code InvWrapper}, not an Ender
 * class, so the hook check passes it on every Ender build and proves nothing about a changed one; the custody check
 * still applies (Advisor M-5).</p>
 */
public final class EnderStorageMenuAdapter implements MenuTransactionAdapter {
    @Override public String adapterId() { return "enderstorage-2.13.0.191"; }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public boolean usesSharedChannelBacking() { return true; }
    @Override public boolean usesCapturedAccessAuthority() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof ContainerEnderItemStorage menu)) return AdapterMatch.NO_MATCH;
        return menu.getClass() == ContainerEnderItemStorage.class && menu instanceof EnderMenuCarrier
                ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }
    private Optional<EnderStorageBinding> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        var binding = ((EnderMenuCarrier) context.menu()).eventtrail$enderBinding();
        return binding != null && binding.player == context.player() && binding.menu == context.menu() && binding.current()
                ? Optional.of(binding) : Optional.empty();
    }
    @Override public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) { return current(context).flatMap(EnderStorageBinding::target); }
    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(binding -> binding.target().map(target -> new MenuAccessTarget(target, binding.targetId())));
    }
    @Override public Optional<BooleanSupplier> captureAccessGuard(MenuAdapterContext context) { return current(context).map(EnderStorageBinding::accessGuard); }
    @Override public Optional<BooleanSupplier> captureActionGuard(MenuAdapterContext context) { return current(context).map(EnderStorageBinding::actionGuard); }
    @Override public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        Optional<EnderStorageBinding> binding = current(context);
        if (binding.isEmpty()) return Optional.empty();
        Optional<MenuPhysicalBacking.Description> description = describe(context, binding.get().storage, binding.get());
        // GF-WARNINGS (B48): a bound menu (match answered SUPPORTED) whose slot layout fails the shape check is warned.
        if (description.isEmpty()) RecordingGaps.bindingShapeFailed(AdapterKey.ENDER_STORAGE,
                "adapter:EnderStorageMenuAdapter.describePhysicalBacking");
        return description;
    }
    static Optional<MenuPhysicalBacking.Description> describe(MenuAdapterContext context, EnderItemStorage storage, Object owner) {
        if (context == null || context.player() == null || !(context.menu() instanceof ContainerEnderItemStorage menu)
                || menu.getClass() != ContainerEnderItemStorage.class || menu.chestInv != storage || storage == null
                || storage.getClass() != EnderItemStorage.class || storage.getSize() < 0 || storage.getSize() > 2) return Optional.empty();
        int size = switch (storage.getSize()) { case 0 -> 9; case 1 -> 27; case 2 -> 54; default -> 0; };
        if (size != storage.getContainerSize() || menu.slots.size() != size + 36) return Optional.empty();
        List<MenuPhysicalBacking.DeclaredSlot> slots = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i); boolean device = i < size;
            int cell = device ? i : i < size + 27 ? i - size + 9 : i - size - 27;
            if (slot == null || slot.getClass() != Slot.class || slot.index != i
                    || slot.container != (device ? storage : context.player().getInventory()) || slot.getContainerSlot() != cell) return Optional.empty();
            slots.add(new MenuPhysicalBacking.DeclaredSlot(i, slot, device ? InventorySlotRole.STORAGE : InventorySlotRole.PLAYER,
                    device ? Optional.of(new MenuSlotAccess(storage, cell, () -> storage.getItem(cell))) : Optional.empty()));
        }
        return Optional.of(new MenuPhysicalBacking.Description(owner,
                List.of(new MenuPhysicalBacking.Backing(storage, size, storage::getItem)), slots, true,
                List.of(owner, storage.manager, storage)));
    }
    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        if (context == null || context.player() == null || slot == null || slot.getClass() != Slot.class
                || !(context.menu() instanceof ContainerEnderItemStorage menu)) return InventorySlotRole.UNKNOWN;
        if (slot.container == context.player().getInventory()) return InventorySlotRole.PLAYER;
        return slot.container == menu.chestInv ? InventorySlotRole.STORAGE : InventorySlotRole.UNKNOWN;
    }
}
