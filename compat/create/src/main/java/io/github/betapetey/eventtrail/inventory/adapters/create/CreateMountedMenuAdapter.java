package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.item.chest.ChestMountedStorage;
import com.simibubi.create.api.contraption.storage.item.simple.SimpleMountedStorage;
import com.simibubi.create.api.contraption.storage.item.menu.StorageInteractionWrapper;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.dispenser.storage.DispenserMountedStorage;
import com.simibubi.create.impl.contraption.storage.FallbackMountedStorage;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import io.github.betapetey.eventtrail.inventory.spi.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Native mounted finite cells; vanilla-looking menu slots never substitute for acquisition authority.
 *
 * <p>Built on any installed Create build (build B F1-4, R1c): no artifact pin decides construction. Its checks are the
 * exact menu, wrapper and storage classes in {@code match} and {@code capture}; a menu or storage they do not
 * recognise gives no binding and no row.</p>
 */
public final class CreateMountedMenuAdapter implements MenuTransactionAdapter {
    private static final Field COMBINED_HANDLERS = field(CombinedInvWrapper.class, "itemHandler");
    private static final Field COMBINED_OFFSETS = field(CombinedInvWrapper.class, "baseIndex");
    @Override public String adapterId() { return "create-mounted-6.0.10"; }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public boolean usesMovingBackingTarget() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }

    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
        if (context.menu().slots.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) {
            return !context.menu().slots.isEmpty() && context.menu().slots.getFirst() != null
                    && context.menu().slots.getFirst().container instanceof StorageInteractionWrapper
                    ? AdapterMatch.AMBIGUOUS : AdapterMatch.NO_MATCH;
        }
        Container wrapper = wrapper(context);
        if (wrapper != null) {
            return (context.menu().getClass() == ChestMenu.class || context.menu().getClass() == DispenserMenu.class)
                    && wrapper.getClass() == StorageInteractionWrapper.class && wrapper instanceof CreateStorageCarrier
                    ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
        }
        return AdapterMatch.NO_MATCH;
    }

    static Optional<CreateMountedBinding> capture(CreateOpeningScope.Opening opening,
            MountedItemStorage storage, IItemHandlerModifiable handler) {
        var entity = opening.entity(); var contraption = entity.getContraption();
        if (contraption == null || !CreateMountedBinding.admittedEntity(entity)) return Optional.empty();
        var info = contraption.getBlocks().get(opening.local());
        if (info == null || !supported(storage, info.state())) return Optional.empty();
        List<CreateMountedBinding.Member> members = new ArrayList<>();
        var first = new CreateMountedBinding.Member(info.pos(), info.state(), storage);
        members.add(first);
        if (storage.getClass() == ChestMountedStorage.class && info.state().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            var otherPos = info.pos().relative(ChestBlock.getConnectedDirection(info.state()));
            var otherInfo = contraption.getBlocks().get(otherPos);
            var other = contraption.getStorage().getAllItemStorages().get(otherPos);
            if (otherInfo == null || other == storage || other == null || other.getClass() != ChestMountedStorage.class
                    || !supported(other, otherInfo.state()) || otherInfo.state().getBlock() != info.state().getBlock()
                    || otherInfo.state().getValue(ChestBlock.FACING) != info.state().getValue(ChestBlock.FACING)
                    || otherInfo.state().getValue(ChestBlock.TYPE) != info.state().getValue(ChestBlock.TYPE).getOpposite()
                    || !otherPos.relative(ChestBlock.getConnectedDirection(otherInfo.state())).equals(info.pos())) return Optional.empty();
            var second = new CreateMountedBinding.Member(otherPos, otherInfo.state(), other);
            if (info.state().getValue(ChestBlock.TYPE) == ChestType.RIGHT) members.add(second);
            else members.addFirst(second);
        }
        if (!handlerMatches(handler, members, contraption)) return Optional.empty();
        var binding = new CreateMountedBinding(opening, members, handler, null, "create:6.0.10",
                DisplayNameResolver.blockDisplayName(info.state().getBlock()));
        return binding.target().isPresent() ? Optional.of(binding) : Optional.empty();
    }

    /**
     * The exact storage classes that keep their own items and open through the base
     * {@code MountedItemStorage#handleInteraction} (Part 1 SA-create-mounted). FallbackMountedStorage is what Create
     * gives a modded container whose handler is exactly an ItemStackHandler; it extends SimpleMountedStorage and keeps
     * a copy of the cells. No fixed slot count or vanilla block decides any more: the handler identity
     * ({@link #handlerMatches}) and the menu's slot count ({@code current()}) are the proofs. A storage class that
     * overrides handleInteraction (an Ender chest, toolbox, vault or depot on a contraption) is never accepted: its
     * contents may belong to a shared channel, and the storage scope never opens for it. A chest storage also needs the
     * chest block properties the double-chest pairing reads.
     */
    static boolean supported(MountedItemStorage storage, BlockState state) {
        if (storage == null || state == null) return false;
        Class<?> type = storage.getClass();
        if (type != ChestMountedStorage.class && type != DispenserMountedStorage.class
                && type != SimpleMountedStorage.class && type != FallbackMountedStorage.class) return false;
        if (!basesInteraction(type)) return false;
        return type != ChestMountedStorage.class || state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE) && state.hasProperty(ChestBlock.FACING);
    }

    /**
     * Whether {@code type} opens through the base handleInteraction body (the one the storage scope wraps). A class
     * whose methods cannot be read is refused (fail closed).
     */
    static boolean basesInteraction(Class<?> type) {
        return type != null && MountedItemStorage.class.isAssignableFrom(type) && BASE_INTERACTION.get(type);
    }
    private static final ClassValue<Boolean> BASE_INTERACTION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("handleInteraction", ServerPlayer.class, Contraption.class, StructureBlockInfo.class)
                        .getDeclaringClass() == MountedItemStorage.class;
            } catch (NoSuchMethodException | LinkageError unreadable) { return false; }
        }
    };

    /** The handler proof with no opening contraption known: Offroad's wrapper cannot be proved here, so it refuses. */
    static boolean handlerMatches(IItemHandlerModifiable handler, List<CreateMountedBinding.Member> members) {
        return handlerMatches(handler, members, null);
    }

    /** {@code contraption} is the opening's contraption, which Offroad's wrapper must name (r2, review-01 NIT-1). */
    static boolean handlerMatches(IItemHandlerModifiable handler, List<CreateMountedBinding.Member> members,
            Contraption contraption) {
        if (members.size() == 1) return handler == members.getFirst().storage() || offroadWraps(handler, members.getFirst(), contraption);
        if (members.size() != 2 || handler == null || handler.getClass() != CombinedInvWrapper.class) return false;
        try {
            var handlers = (IItemHandlerModifiable[]) COMBINED_HANDLERS.get(handler);
            var offsets = (int[]) COMBINED_OFFSETS.get(handler);
            if (handlers.length != 2 || offsets.length != 2) return false;
            int size = 0;
            for (int i = 0; i < 2; i++) {
                if (handlers[i] != members.get(i).storage()) return false;
                size += handlers[i].getSlots();
                if (offsets[i] != size) return false;
            }
            return handler.getSlots() == size;
        } catch (IllegalAccessException failure) { throw new IllegalStateException("Mounted combined owner unavailable", failure); }
    }

    static final String OFFROAD_WRAPPED_STORAGE = "dev.ryanhcode.offroad.neoforge.mixin_helpers.WrappedWrappedMountedItemStorage";

    /**
     * Part 1 SCM-OFFROAD: Offroad's MountedItemStorageMixin (a WrapMethod on the base
     * {@code MountedItemStorage#getHandlerForMenu}) gives a Borehead contraption's menu an exact
     * WrappedWrappedMountedItemStorage over the storage's own handler; its item methods delegate to that handler
     * (offroad-neoforge-1.21.1-1.3.2). It stands for the member only when its inner handler is the member storage itself,
     * its contraption is {@code expected} (the opening's contraption, compared directly) and that contraption holds the
     * storage at the member's position. Any other class, inner object or contraption, a null {@code expected}, or a
     * field that cannot be read, is refused: no binding, which the caller counts as an unresolved mounted target.
     */
    static boolean offroadWraps(IItemHandlerModifiable handler, CreateMountedBinding.Member member, Contraption expected) {
        if (handler == null || member == null || expected == null
                || !OFFROAD_WRAPPED_STORAGE.equals(handler.getClass().getName())) return false;
        Field[] fields = OFFROAD_WRAPPED_FIELDS.get(handler.getClass());
        if (fields.length != 2) return false;
        try {
            return fields[0].get(handler) == member.storage()
                    && fields[1].get(handler) instanceof WeakReference<?> reference
                    && reference.get() instanceof Contraption contraption && contraption == expected
                    && contraption.getStorage() != null
                    && contraption.getStorage().getAllItemStorages().get(member.local()) == member.storage();
        } catch (IllegalAccessException | RuntimeException | LinkageError unreadable) { return false; }
    }
    /** Per wrapper class: its {@code wrappedInv} and {@code associatedContraption} fields, or none if unreadable. */
    private static final ClassValue<Field[]> OFFROAD_WRAPPED_FIELDS = new ClassValue<>() {
        @Override protected Field[] computeValue(Class<?> type) {
            try {
                Field inner = type.getDeclaredField("wrappedInv");
                Field owner = type.getDeclaredField("associatedContraption");
                if (inner.getType() != IItemHandlerModifiable.class || owner.getType() != WeakReference.class
                        || java.lang.reflect.Modifier.isStatic(inner.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(owner.getModifiers())) return new Field[0];
                inner.setAccessible(true);
                owner.setAccessible(true);
                return new Field[] {inner, owner};
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) { return new Field[0]; }
        }
    };

    private static Container wrapper(MenuAdapterContext context) {
        for (Slot slot : context.menu().slots) if (slot != null && slot.container instanceof StorageInteractionWrapper) return slot.container;
        return null;
    }
    private Optional<CreateMountedBinding> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.player().containerMenu != context.menu()
                || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        Container wrapper = wrapper(context);
        var binding = ((CreateStorageCarrier) wrapper).eventtrail$createBinding();
        if (binding == null || binding.player != context.player() || !(binding.handler instanceof IItemHandlerModifiable handler)
                || !binding.current()) return Optional.empty();
        if (!menuFits(context.menu(), wrapper, handler, binding.members, binding.contraption)) { binding.invalidate(); return Optional.empty(); }
        return Optional.of(binding);
    }

    /**
     * current()'s proofs that the open menu is the bound storage's own screen: the handler is still the bound storage
     * (or Create's exact pair wrapper over the two chest halves), and the wrapper size, the menu's slot count and a
     * dispenser's or chest's own size all agree with the handler. Since Part 1 SA-create-mounted removed the fixed 27/9
     * counts from {@link #supported}, these are the only size checks (r2, review-01 m-3).
     */
    static boolean menuFits(AbstractContainerMenu menu, Container wrapper, IItemHandlerModifiable handler,
            List<CreateMountedBinding.Member> members) {
        return menuFits(menu, wrapper, handler, members, null);
    }

    /** As above, with the binding's contraption for Offroad's wrapper (r2, review-01 NIT-1). */
    static boolean menuFits(AbstractContainerMenu menu, Container wrapper, IItemHandlerModifiable handler,
            List<CreateMountedBinding.Member> members, Contraption contraption) {
        return handlerMatches(handler, members, contraption) && wrapper.getContainerSize() == handler.getSlots()
                && menu.slots.size() == handler.getSlots() + 36
                && (menu.getClass() != DispenserMenu.class || handler.getSlots() == 9)
                && (!(menu instanceof ChestMenu chest)
                    || chest.getContainer() == wrapper && chest.getRowCount() * 9 == handler.getSlots());
    }
    @Override public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return current(context).flatMap(CreateMountedBinding::target);
    }
    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(binding -> binding.target().map(target -> new MenuAccessTarget(target, binding.targetId())));
    }
    @Override public Optional<BooleanSupplier> captureMovingAccessGuard(MenuAdapterContext context) {
        return current(context).map(binding -> binding.guard(() -> current(context).orElse(null) == binding));
    }
    @Override public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return current(context).flatMap(binding -> describe(context, wrapper(context), (IItemHandlerModifiable) binding.handler, binding));
    }
    static Optional<MenuPhysicalBacking.Description> describe(MenuAdapterContext context, Container wrapper,
            IItemHandlerModifiable handler, Object owner) {
        int size = handler.getSlots();
        if (size < 1 || size > 54 || context.menu().slots.size() != size + 36) return Optional.empty();
        List<MenuPhysicalBacking.DeclaredSlot> slots = new ArrayList<>();
        for (int i = 0; i < context.menu().slots.size(); i++) {
            Slot slot = context.menu().slots.get(i);
            int cell = i < size ? i : i < size + 27 ? i - size + 9 : i - size - 27;
            boolean device = i < size;
            if (slot == null || slot.getClass() != Slot.class || slot.index != i
                    || slot.container != (device ? wrapper : context.player().getInventory()) || slot.getContainerSlot() != cell) return Optional.empty();
            slots.add(new MenuPhysicalBacking.DeclaredSlot(i, slot, device ? InventorySlotRole.STORAGE : InventorySlotRole.PLAYER,
                    device ? Optional.of(new MenuSlotAccess(handler, cell, () -> handler.getStackInSlot(cell))) : Optional.empty()));
        }
        return Optional.of(new MenuPhysicalBacking.Description(owner,
                List.of(new MenuPhysicalBacking.Backing(handler, size, handler::getStackInSlot)), slots, true, List.of(owner)));
    }
    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        if (context == null || context.player() == null || slot == null || slot.getClass() != Slot.class) return InventorySlotRole.UNKNOWN;
        if (slot.container == context.player().getInventory()) return InventorySlotRole.PLAYER;
        return slot.container instanceof StorageInteractionWrapper ? InventorySlotRole.STORAGE : InventorySlotRole.UNKNOWN;
    }
    private static Field field(Class<?> type, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mounted handler contract unavailable", failure); }
    }
}
