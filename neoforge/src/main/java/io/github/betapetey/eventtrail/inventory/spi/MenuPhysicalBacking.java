package io.github.betapetey.eventtrail.inventory.spi;

import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.view.BoundedMenuInventoryView;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Complete, action-revalidated physical backing supplied by a trusted menu
 * producer. This describes ownership and reads; it never infers a transaction.
 *
 * <p>The producer must derive its backings from the active menu's actual owner,
 * not nearby capabilities. It must return no description if that owner, its
 * complete physical/compound membership, portable stack/provider/parent anchors,
 * or its live-world binding is stale. Item identities come from the producer's
 * authoritative contents identity, never invented world coordinates.
 * Every declared backing cell must be represented, including non-custodial
 * cells. Player slots belong only to the exact invoking inventory.</p>
 *
 * <p>No optional-mod or loader API appears here. Minecraft types are intentional
 * shared types for this generation, not an assertion of Java-only portability.</p>
 */
public final class MenuPhysicalBacking {
    private final AbstractContainerMenu menu;
    private final Container playerInventory;
    private final Supplier<Optional<Description>> producer;
    private final Description initial;
    private final BoundedMenuInventoryView view;
    private final String baseTargetIdentity;
    private final BackingContextKind declaredKind;
    private boolean readFailureObserved;

    private MenuPhysicalBacking(AbstractContainerMenu menu, Container playerInventory,
            Supplier<Optional<Description>> producer, Description initial,
            BoundedMenuInventoryView view, String baseTargetIdentity, BackingContextKind declaredKind) {
        this.menu = menu;
        this.playerInventory = playerInventory;
        this.producer = producer;
        this.initial = initial;
        this.view = view;
        this.baseTargetIdentity = baseTargetIdentity;
        this.declaredKind = declaredKind;
    }

    public static Optional<MenuPhysicalBacking> resolve(AbstractContainerMenu menu,
            Container playerInventory, String targetIdentity,
            Supplier<Optional<Description>> producer) {
        return resolve(menu, playerInventory, targetIdentity, BackingContextKind.WORLD_BLOCK, producer);
    }

    /** Concrete item storage reuses the same bounded cell contract, not a virtual-network scan. */
    public static Optional<MenuPhysicalBacking> resolve(AbstractContainerMenu menu,
            Container playerInventory, String targetIdentity, BackingContextKind kind,
            Supplier<Optional<Description>> producer) {
        return resolveBoundCells(menu, playerInventory, targetIdentity, kind, producer, false);
    }

    /** A deliberate exact route; the ordinary resolver still rejects every SHARED_CHANNEL. */
    public static Optional<MenuPhysicalBacking> resolveExactSharedChannel(AbstractContainerMenu menu,
            Container playerInventory, String targetIdentity, MenuTransactionAdapter adapter,
            Supplier<Optional<Description>> producer) {
        if (!isExactSharedChannelProducer(adapter)) return Optional.empty();
        return resolveBoundCells(menu, playerInventory, targetIdentity,
                BackingContextKind.SHARED_CHANNEL, producer, true);
    }

    /**
     * Source-owned allowlist does not link the optional mod or grant arbitrary adapter flags. The class compared is
     * the implementation's (R2-menu: the loader registers the Ender adapter inside {@link GuardedMenuAdapter}).
     */
    public static boolean isExactSharedChannelProducer(MenuTransactionAdapter adapter) {
        return adapter != null && adapter.exact() && adapter.usesSharedChannelBacking()
                && adapter.usesHandlerBackedView() && adapter.usesIndependentBackingTarget()
                && adapter.usesCapturedAccessAuthority()
                && adapter.observationStrategy() == ObservationStrategy.ACTION_SCOPED_PHYSICAL
                && "enderstorage-2.13.0.191".equals(adapter.adapterId())
                && "io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderStorageMenuAdapter"
                        .equals(GuardedMenuAdapter.implementationClassName(adapter));
    }

    private static Optional<MenuPhysicalBacking> resolveBoundCells(AbstractContainerMenu menu,
            Container playerInventory, String targetIdentity, BackingContextKind kind,
            Supplier<Optional<Description>> producer, boolean exactSharedChannel) {
        if (menu == null || playerInventory == null || producer == null) return Optional.empty();
        if (kind == null || kind == BackingContextKind.UNKNOWN || kind == BackingContextKind.NETWORK
                || kind == BackingContextKind.SHARED_CHANNEL && !exactSharedChannel
                || kind == BackingContextKind.PROXY) return Optional.empty();
        try {
            Optional<Description> declared = producer.get();
            if (declared == null || declared.isEmpty()) return Optional.empty();
            Description description = declared.orElseThrow();
            if (!withinResourceBounds(menu, description)) return Optional.empty();
            String canonicalTarget = canonicalTarget(targetIdentity, description, kind);
            Optional<BoundedMenuInventoryView> view = validate(menu, playerInventory, canonicalTarget, description);
            return view.map(value -> new MenuPhysicalBacking(menu, playerInventory, producer, description, value, targetIdentity, kind));
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    /** Re-check host membership, exact slot objects, cells and roles at each boundary. */
    public boolean isCurrent() {
        try {
            Optional<Description> current = producer.get();
            if (current == null || current.isEmpty()) return false;
            Description next = current.orElseThrow();
            if (!withinResourceBounds(menu, next)) return false;
            if (!sameBindings(initial, next)) return false;
            if (!view.targetIdentity().equals(canonicalTarget(baseTargetIdentity, next, declaredKind))) return false;
            return validate(menu, playerInventory, view.targetIdentity(), next).isPresent();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    public Optional<BoundedMenuInventoryView.Capture> capture() {
        // R2-menu (review-01 MINOR-1): per capture, so a later owner or menu replacement after one failed read is not
        // read as another read failure.
        readFailureObserved = false;
        if (!isCurrent()) return Optional.empty();
        BoundedMenuInventoryView.Capture result = view.capture();
        if (!result.valid()) readFailureObserved = true;
        return result.valid() && isCurrent() ? Optional.of(result) : Optional.empty();
    }

    /** Whether the last {@link #capture()} failed on a read; distinct from normal owner/menu replacement. */
    public boolean readFailureObserved() { return readFailureObserved; }

    public boolean durableAccessIdentity() { return initial.durableAccessIdentity(); }
    public String targetIdentity() { return view.targetIdentity(); }
    public BackingContextKind backingContextKind() {
        return physicalMembers(declaredKind) && initial.ownershipMembers().size() > 1
                ? BackingContextKind.COMPOUND_BLOCK : declaredKind;
    }
    public List<DeclaredSlot> slots() { return initial.slots(); }
    public List<Object> ownershipMembers() { return initial.ownershipMembers(); }

    private static boolean physicalMembers(BackingContextKind kind) {
        return kind == BackingContextKind.WORLD_BLOCK || kind == BackingContextKind.COMPOUND_BLOCK
                || kind == BackingContextKind.PLACED_BLOCK;
    }

    private static String canonicalTarget(String base, Description description, BackingContextKind kind) {
        // Portable anchors identify an exact provider/stack/parent chain, not world coordinates.
        if (!physicalMembers(kind) || description.ownershipMembers().size() <= 1) return base;
        var positions = description.ownershipMembers().stream().map(member -> {
            if (!(member instanceof BlockEntity block)) throw new IllegalArgumentException("physical compound member");
            return block.getBlockPos().immutable();
        }).sorted(Comparator.comparingInt((net.minecraft.core.BlockPos p) -> p.getX())
                .thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ())).toList();
        if (positions.stream().distinct().count() != positions.size()) throw new IllegalArgumentException("compound positions");
        StringBuilder key = new StringBuilder(base).append("|members");
        positions.forEach(p -> key.append('|').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()));
        return key.toString();
    }

    private static Optional<BoundedMenuInventoryView> validate(AbstractContainerMenu menu,
            Container player, String target, Description description) {
        if (!withinResourceBounds(menu, description)) return Optional.empty();
        IdentityHashMap<Object, Backing> owners = new IdentityHashMap<>();
        IdentityHashMap<Object, boolean[]> represented = new IdentityHashMap<>();
        IdentityHashMap<Object, Map<Integer, InventorySlotRole>> cellRoles = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> members = new IdentityHashMap<>();
        for (Object member : description.ownershipMembers()) {
            if (member == null || members.put(member, true) != null) return Optional.empty();
        }
        int declaredCells = 0;
        for (Backing backing : description.backings()) {
            if (backing.identity() == player || owners.put(backing.identity(), backing) != null) return Optional.empty();
            // Every cell (including configuration/non-custody) requires a descriptor.
            // This limits coverage bookkeeping; the custody-only budget is checked below.
            if (backing.size() > description.slots().size() - declaredCells) return Optional.empty();
            declaredCells += backing.size();
            represented.put(backing.identity(), new boolean[backing.size()]);
            cellRoles.put(backing.identity(), new HashMap<>());
        }
        boolean[] indices = new boolean[MenuInventoryLimits.MAX_MENU_DESCRIPTORS];
        IdentityHashMap<Slot, Boolean> slotObjects = new IdentityHashMap<>();
        List<BoundedMenuInventoryView.SlotBinding> bindings = new ArrayList<>();
        int custodyCells = 0;
        for (DeclaredSlot declared : description.slots()) {
            int index = declared.menuSlotIndex();
            Slot slot = declared.slot();
            if (index < 0 || index >= indices.length || indices[index]
                    || slotObjects.put(slot, true) != null) return Optional.empty();
            indices[index] = true;
            if (index < menu.slots.size()) {
                if (menu.slots.get(index) != slot) return Optional.empty();
            } else if (slot.index != index) return Optional.empty();
            if (declared.role() == InventorySlotRole.UNKNOWN) return Optional.empty();
            if (declared.role() == InventorySlotRole.PLAYER) {
                if (slot.container != player || declared.access().isPresent()) return Optional.empty();
                continue;
            }
            if (slot.container == player) return Optional.empty();
            if (declared.access().isEmpty()) {
                if (declared.role().isRealDeviceCustody()) return Optional.empty();
                continue;
            }
            MenuSlotAccess access = declared.access().orElseThrow();
            Backing backing = owners.get(access.backingIdentity());
            if (backing == null || access.backingSlotIndex() >= backing.size()) return Optional.empty();
            InventorySlotRole priorRole = cellRoles.get(backing.identity()).putIfAbsent(access.backingSlotIndex(), declared.role());
            if (priorRole != null && priorRole != declared.role()) return Optional.empty();
            represented.get(backing.identity())[access.backingSlotIndex()] = true;
            if (declared.role().isRealDeviceCustody()) {
                if (priorRole == null && ++custodyCells > MenuInventoryLimits.MAX_DEVICE_CUSTODY_CELLS) {
                    return Optional.empty();
                }
                // Read the producer's owner-derived backing, not an unrelated
                // reader merely attached to a visually similar menu slot.
                MenuSlotAccess owned = new MenuSlotAccess(backing.identity(), access.backingSlotIndex(),
                        () -> backing.reader().apply(access.backingSlotIndex()));
                bindings.add(new BoundedMenuInventoryView.SlotBinding(slot, declared.role(), target, Optional.of(owned)));
            }
        }
        for (int i = 0; i < menu.slots.size(); i++) if (!indices[i]) return Optional.empty();
        for (boolean[] slots : represented.values()) for (boolean present : slots) if (!present) return Optional.empty();
        return BoundedMenuInventoryView.resolve(bindings).view();
    }

    private static boolean withinResourceBounds(AbstractContainerMenu menu, Description description) {
        return description.slots().size() >= menu.slots.size()
                && description.slots().size() <= MenuInventoryLimits.MAX_MENU_DESCRIPTORS
                && !description.backings().isEmpty()
                && description.backings().size() <= MenuInventoryLimits.MAX_BACKING_OBJECTS
                && !description.ownershipMembers().isEmpty()
                && description.ownershipMembers().size() <= MenuInventoryLimits.MAX_OWNERSHIP_MEMBERS;
    }

    private static boolean sameBindings(Description first, Description next) {
        if (first.durableAccessIdentity() != next.durableAccessIdentity()
                || first.ownerIdentity() != next.ownerIdentity()
                || first.ownershipMembers().size() != next.ownershipMembers().size()
                || first.backings().size() != next.backings().size()
                || first.slots().size() != next.slots().size()) return false;
        IdentityHashMap<Object, Boolean> members = new IdentityHashMap<>();
        first.ownershipMembers().forEach(member -> members.put(member, true));
        if (next.ownershipMembers().stream().anyMatch(member -> !members.containsKey(member))) return false;
        for (int i = 0; i < first.backings().size(); i++) {
            Backing a = first.backings().get(i), b = next.backings().get(i);
            if (a.identity() != b.identity() || a.size() != b.size()) return false;
        }
        for (int i = 0; i < first.slots().size(); i++) {
            DeclaredSlot a = first.slots().get(i), b = next.slots().get(i);
            if (a.slot() != b.slot() || a.menuSlotIndex() != b.menuSlotIndex() || a.role() != b.role()
                    || a.access().isPresent() != b.access().isPresent()) return false;
            if (a.access().isPresent()) {
                MenuSlotAccess x = a.access().orElseThrow(), y = b.access().orElseThrow();
                if (x.backingIdentity() != y.backingIdentity() || x.backingSlotIndex() != y.backingSlotIndex()) return false;
            }
        }
        return true;
    }

    public record Backing(Object identity, int size, IntFunction<ItemStack> reader) {
        public Backing {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reader, "reader");
            // Declared size includes non-custodial cells: complete menu coverage bounds it.
            // Resolution separately limits the unique real-custody subset before any read.
            if (size < 1 || size > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) throw new IllegalArgumentException("backing size");
        }
    }

    public record DeclaredSlot(int menuSlotIndex, Slot slot, InventorySlotRole role, Optional<MenuSlotAccess> access) {
        public DeclaredSlot {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(access, "access");
        }
    }

    /** Owner is an exact live object identity; never a guessed position or class name. */
    public record Description(Object ownerIdentity, List<Backing> backings,
            List<DeclaredSlot> slots, boolean durableAccessIdentity, List<Object> ownershipMembers) {
        public Description(Object ownerIdentity, List<Backing> backings, List<DeclaredSlot> slots,
                boolean durableAccessIdentity) {
            this(ownerIdentity, backings, slots, durableAccessIdentity, List.of(ownerIdentity));
        }
        public Description {
            Objects.requireNonNull(ownerIdentity, "ownerIdentity");
            backings = List.copyOf(backings);
            slots = List.copyOf(slots);
            ownershipMembers = List.copyOf(ownershipMembers);
        }
    }
}
