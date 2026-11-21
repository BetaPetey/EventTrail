package io.github.betapetey.eventtrail.inventory.view;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.spi.MenuSlotAccess;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, single-target view of proven real device slots exposed by a menu.
 *
 * <p>Adapters must pass only device slots they have classified. Player, ghost,
 * configuration, display, and unknown slots fail resolution rather than being
 * silently treated as storage. Backing slots are deduplicated by the exact
 * backing object identity and backing slot index, which handles menus that
 * expose the same storage cell more than once without relying on backing
 * implementations' {@code equals} methods.</p>
 *
 * <p>Resolution recognizes ordinary {@link Slot} instances backed by a real
 * {@link Container} and NeoForge {@link SlotItemHandler} instances backed by
 * their {@link IItemHandler}. The handler path is intentionally checked first:
 * {@code SlotItemHandler.container} is only NeoForge's shared empty placeholder.
 * Captures are complete-or-invalid; a failed read or item-identity conversion
 * never returns a partial snapshot.</p>
 */
public final class BoundedMenuInventoryView {
    private final String targetIdentity;
    private final List<ResolvedSlot> slots;

    private BoundedMenuInventoryView(String targetIdentity, List<ResolvedSlot> slots) {
        this.targetIdentity = targetIdentity;
        this.slots = List.copyOf(slots);
    }

    /**
     * Resolves adapter-declared menu slots into one bounded observation view.
     * Invalid input returns no usable view.
     */
    public static Resolution resolve(Collection<SlotBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return Resolution.invalid(FailureReason.NO_DEVICE_SLOTS);
        }
        if (bindings.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) {
            return Resolution.invalid(FailureReason.SLOT_LIMIT_EXCEEDED);
        }

        Map<BackingSlotKey, ResolvedSlot> uniqueSlots = new LinkedHashMap<>();
        String targetIdentity = null;

        try {
            for (SlotBinding binding : bindings) {
                if (binding == null || binding.slot() == null || binding.role() == null) {
                    return Resolution.invalid(FailureReason.INVALID_BINDING);
                }
                if (!binding.role().isRealDeviceCustody()) {
                    return Resolution.invalid(FailureReason.NON_CUSTODY_ROLE);
                }
                if (binding.targetIdentity() == null || binding.targetIdentity().isBlank()) {
                    return Resolution.invalid(FailureReason.INVALID_TARGET_IDENTITY);
                }
                if (targetIdentity == null) {
                    targetIdentity = binding.targetIdentity();
                } else if (!targetIdentity.equals(binding.targetIdentity())) {
                    return Resolution.invalid(FailureReason.MIXED_TARGET_IDENTITIES);
                }

                ResolvedSlot resolved = resolveSlot(binding);
                if (resolved == null) {
                    return Resolution.invalid(FailureReason.INVALID_BACKING_SLOT);
                }

                ResolvedSlot existing = uniqueSlots.putIfAbsent(resolved.key(), resolved);
                if (existing != null && existing.role() != resolved.role()) {
                    return Resolution.invalid(FailureReason.CONFLICTING_DUPLICATE_SLOT);
                }
                if (uniqueSlots.size() > MenuInventoryLimits.MAX_DEVICE_CUSTODY_CELLS) {
                    return Resolution.invalid(FailureReason.SLOT_LIMIT_EXCEEDED);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            return Resolution.invalid(FailureReason.BACKING_DISCOVERY_FAILED);
        }

        if (uniqueSlots.isEmpty()) {
            return Resolution.invalid(FailureReason.NO_DEVICE_SLOTS);
        }
        return Resolution.valid(new BoundedMenuInventoryView(
                Objects.requireNonNull(targetIdentity, "targetIdentity"),
                new ArrayList<>(uniqueSlots.values())));
    }

    /** Captures every resolved backing slot atomically from EventTrail's perspective. */
    public Capture capture() {
        List<CapturedSlot> capturedSlots = new ArrayList<>(slots.size());
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();

        try {
            for (ResolvedSlot slot : slots) {
                ItemStack stack = slot.reader().read();
                if (stack == null) {
                    return Capture.invalid(FailureReason.SLOT_READ_FAILED);
                }

                if (stack.isEmpty()) {
                    capturedSlots.add(new CapturedSlot(
                            slot.backingSlotIndex(),
                            slot.role(),
                            Optional.empty(),
                            0));
                    continue;
                }

                int count = stack.getCount();
                if (count <= 0) {
                    return Capture.invalid(FailureReason.INVALID_ITEM_COUNT);
                }
                InventoryItemIdentity identity = InventoryItemIdentity.of(stack);
                totals.merge(identity, (long) count, Math::addExact);
                capturedSlots.add(new CapturedSlot(
                        slot.backingSlotIndex(),
                        slot.role(),
                        Optional.of(identity),
                        count));
            }
        } catch (RuntimeException | LinkageError failure) {
            return Capture.invalid(FailureReason.SLOT_READ_FAILED);
        }

        return Capture.valid(targetIdentity, capturedSlots, totals);
    }

    public String targetIdentity() {
        return targetIdentity;
    }

    public int slotCount() {
        return slots.size();
    }

    public List<SlotDescriptor> slots() {
        return slots.stream()
                .map(slot -> new SlotDescriptor(slot.backingSlotIndex(), slot.role()))
                .toList();
    }

    private static ResolvedSlot resolveSlot(SlotBinding binding) {
        Slot slot = binding.slot();
        if (binding.adapterAccess().isPresent()) {
            MenuSlotAccess access = binding.adapterAccess().orElseThrow();
            BackingSlotKey key = new BackingSlotKey(
                    access.backingIdentity(), access.backingSlotIndex());
            return new ResolvedSlot(
                    key,
                    access.backingSlotIndex(),
                    binding.role(),
                    access.stackReader()::get);
        }

        int backingSlotIndex = slot.getSlotIndex();
        if (backingSlotIndex < 0) {
            return null;
        }

        if (slot instanceof SlotItemHandler handlerSlot) {
            IItemHandler handler = Objects.requireNonNull(handlerSlot.getItemHandler(), "itemHandler");
            if (backingSlotIndex >= handler.getSlots()) {
                return null;
            }
            BackingSlotKey key = new BackingSlotKey(handler, backingSlotIndex);
            return new ResolvedSlot(
                    key,
                    backingSlotIndex,
                    binding.role(),
                    () -> handler.getStackInSlot(backingSlotIndex));
        }

        Container container = Objects.requireNonNull(slot.container, "container");
        if (backingSlotIndex >= container.getContainerSize()) {
            return null;
        }
        BackingSlotKey key = new BackingSlotKey(container, backingSlotIndex);
        return new ResolvedSlot(
                key,
                backingSlotIndex,
                binding.role(),
                () -> container.getItem(backingSlotIndex));
    }

    /** One adapter-classified menu slot belonging to a single logical target. */
    public record SlotBinding(
            Slot slot,
            InventorySlotRole role,
            String targetIdentity,
            Optional<MenuSlotAccess> adapterAccess) {
        public SlotBinding(Slot slot, InventorySlotRole role, String targetIdentity) {
            this(slot, role, targetIdentity, Optional.empty());
        }

        public SlotBinding {
            adapterAccess = Objects.requireNonNull(adapterAccess, "adapterAccess");
        }
    }

    /** Public immutable slot metadata; backing implementation objects remain private. */
    public record SlotDescriptor(int backingSlotIndex, InventorySlotRole role) {
    }

    /** One captured backing slot, including its role and exact component identity. */
    public record CapturedSlot(
            int backingSlotIndex,
            InventorySlotRole role,
            Optional<InventoryItemIdentity> item,
            int count) {
        public CapturedSlot {
            role = Objects.requireNonNull(role, "role");
            item = Objects.requireNonNull(item, "item");
            if (count < 0) throw new IllegalArgumentException("count must not be negative");
            if (item.isEmpty() && count != 0) {
                throw new IllegalArgumentException("An empty slot must have zero count");
            }
            if (item.isPresent() && count == 0) {
                throw new IllegalArgumentException("An occupied slot must have positive count");
            }
        }
    }

    /** Resolution never exposes a partially resolved view. */
    public record Resolution(
            boolean valid,
            Optional<BoundedMenuInventoryView> view,
            FailureReason failureReason) {
        public Resolution {
            view = Objects.requireNonNull(view, "view");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            if (valid != view.isPresent()) {
                throw new IllegalArgumentException("Only valid resolutions may contain a view");
            }
            if (valid != (failureReason == FailureReason.NONE)) {
                throw new IllegalArgumentException("Resolution validity and failure reason disagree");
            }
        }

        private static Resolution valid(BoundedMenuInventoryView view) {
            return new Resolution(true, Optional.of(view), FailureReason.NONE);
        }

        private static Resolution invalid(FailureReason reason) {
            return new Resolution(false, Optional.empty(), reason);
        }
    }

    /** Capture never exposes slots or totals after any capture failure. */
    public record Capture(
            boolean valid,
            String targetIdentity,
            List<CapturedSlot> slots,
            Map<InventoryItemIdentity, Long> totals,
            FailureReason failureReason) {
        public Capture {
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
            totals = Map.copyOf(Objects.requireNonNull(totals, "totals"));
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            if (valid != (failureReason == FailureReason.NONE)) {
                throw new IllegalArgumentException("Capture validity and failure reason disagree");
            }
            if (!valid && (targetIdentity != null || !slots.isEmpty() || !totals.isEmpty())) {
                throw new IllegalArgumentException("Invalid captures cannot contain partial state");
            }
            if (valid && (targetIdentity == null || targetIdentity.isBlank())) {
                throw new IllegalArgumentException("Valid captures require a target identity");
            }
        }

        private static Capture valid(
                String targetIdentity,
                List<CapturedSlot> slots,
                Map<InventoryItemIdentity, Long> totals) {
            return new Capture(true, targetIdentity, slots, totals, FailureReason.NONE);
        }

        private static Capture invalid(FailureReason reason) {
            return new Capture(false, null, List.of(), Map.of(), reason);
        }
    }

    public enum FailureReason {
        NONE,
        NO_DEVICE_SLOTS,
        INVALID_BINDING,
        NON_CUSTODY_ROLE,
        INVALID_TARGET_IDENTITY,
        MIXED_TARGET_IDENTITIES,
        INVALID_BACKING_SLOT,
        CONFLICTING_DUPLICATE_SLOT,
        SLOT_LIMIT_EXCEEDED,
        BACKING_DISCOVERY_FAILED,
        SLOT_READ_FAILED,
        INVALID_ITEM_COUNT
    }

    private record ResolvedSlot(
            BackingSlotKey key,
            int backingSlotIndex,
            InventorySlotRole role,
            SlotReader reader) {
    }

    @FunctionalInterface
    private interface SlotReader {
        ItemStack read();
    }

    /** Identity-based key; backing implementations' value equality is irrelevant. */
    private static final class BackingSlotKey {
        private final Object backing;
        private final int slot;

        private BackingSlotKey(Object backing, int slot) {
            this.backing = backing;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof BackingSlotKey other
                    && backing == other.backing
                    && slot == other.slot;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(backing) + slot;
        }
    }
}
