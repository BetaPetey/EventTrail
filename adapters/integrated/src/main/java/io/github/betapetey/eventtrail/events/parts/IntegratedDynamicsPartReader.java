package io.github.betapetey.eventtrail.events.parts;

import io.github.betapetey.eventtrail.compat.MissingAdapterMemberException;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.cyclops.integrateddynamics.RegistryEntries;
import org.cyclops.integrateddynamics.api.block.IFacadeable;
import org.cyclops.integrateddynamics.api.block.cable.ICable;
import org.cyclops.integrateddynamics.api.block.cable.ICableFakeable;
import org.cyclops.integrateddynamics.api.part.IPartContainer;
import org.cyclops.integrateddynamics.api.part.IPartType;
import org.cyclops.integrateddynamics.capability.cable.CableTileMultipartTicking;
import org.cyclops.integrateddynamics.capability.facadeable.FacadeableTileMultipartTicking;
import org.cyclops.integrateddynamics.capability.partcontainer.PartContainerTileMultipartTicking;
import org.cyclops.integrateddynamics.core.blockentity.BlockEntityMultipartTicking;
import org.cyclops.integrateddynamics.item.ItemFacade;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Integrated Dynamics part reader (key PARTS_INTEGRATED_DYNAMICS, parts design-03 section 2). Compiled against
 * Integrated Dynamics 1.35.0 and CyclopsCore 1.29.3; the packs run 1.38.2, which only adds members to the classes
 * named here. The constructor checks every mod class and member this reader calls (design-03 0.2), so a changed
 * mod is OFF from server start with the member named.
 *
 * <p>The reader contract's own steps ({@link PartReader}):</p>
 * <ul>
 *   <li>Claims: every {@code BlockEntityMultipartTicking} (every cable block, with or without parts; Integrated
 *       Terminals and Integrated Tunnels parts sit on the same block entity). Integrated Dynamics has no machine
 *       take-down.</li>
 *   <li>Pieces: {@code side:<direction>} for each part, {@code cable} while the cable is real, {@code facade} while
 *       a facade is held; at most eight pieces. Items: the part type's item, the cable's own
 *       {@code ICable.getItemStack()}, and for the facade a new facade item written by the mod's own
 *       {@code ItemFacade.writeFacadeBlock} at run time, so the running version's facade data is used.</li>
 *   <li>Hand-over routine (rule 7): CyclopsCore's {@code ItemStackHelpers.spawnItemStackToPlayer}, a new item entity
 *       inside the claimed block's cell; network-element drops go out by {@code Block.popResource} at the block
 *       (the cell). The block entity object survives a piece removal (the part, facade or cable-fake changes in
 *       place); the last piece sets air (the block is gone).</li>
 *   <li>Timing (rule 9): every removal completes inside the bracketed call (the left-click's
 *       {@code onDestroyedByPlayer} or the sneak + wrench right-click).</li>
 *   <li>{@code onDestroyedByPlayer} (rule 10): returns {@code false} for a single-part removal (the block stays),
 *       so the bridge decides.</li>
 * </ul>
 *
 * <p>The reader keeps no state and reads only the given block entity. Its one catch is the constructor check's,
 * which rethrows every reflective failure as a {@link MissingAdapterMemberException}.</p>
 */
public final class IntegratedDynamicsPartReader implements PartReader {
    /** Every mod class the reader names, checked present first (design-03 0.2). */
    static final List<String> CLASS_NAMES = List.of(
            "org.cyclops.integrateddynamics.core.blockentity.BlockEntityMultipartTicking",
            "org.cyclops.integrateddynamics.capability.partcontainer.PartContainerTileMultipartTicking",
            "org.cyclops.integrateddynamics.capability.cable.CableTileMultipartTicking",
            "org.cyclops.integrateddynamics.api.part.IPartContainer",
            "org.cyclops.integrateddynamics.api.part.IPartType",
            "org.cyclops.integrateddynamics.api.block.cable.ICableFakeable",
            "org.cyclops.integrateddynamics.api.block.cable.ICable",
            "org.cyclops.integrateddynamics.capability.facadeable.FacadeableTileMultipartTicking",
            "org.cyclops.integrateddynamics.api.block.IFacadeable",
            "org.cyclops.integrateddynamics.RegistryEntries",
            "org.cyclops.integrateddynamics.item.ItemFacade");

    public IntegratedDynamicsPartReader() {
        check(CLASS_NAMES, IntegratedDynamicsPartReader::members);
    }

    @Override
    public boolean claims(BlockEntity blockEntity) {
        return blockEntity instanceof BlockEntityMultipartTicking;
    }

    @Override
    public List<Piece> pieces(BlockEntity blockEntity) {
        if (!(blockEntity instanceof BlockEntityMultipartTicking tile)) return List.of();
        List<Piece> pieces = new ArrayList<>(IntegratedDynamicsPartSlots.MAX_PIECES);
        IPartContainer container = tile.getPartContainer();
        Map<Direction, IPartType<?, ?>> parts = container.getParts();
        for (Direction side : Direction.values()) {
            IPartType<?, ?> type = parts.get(side);
            if (type != null) {
                pieces.add(new Piece(IntegratedDynamicsPartSlots.side(side),
                        IntegratedDynamicsPartSlots.pieceId(type.getUniqueName().toString()),
                        new ItemStack(type.getItem())));
            }
        }
        ICableFakeable fakeable = tile.getCableFakeable();
        if (fakeable.isRealCable()) {
            ICable cable = tile.getCable();
            pieces.add(new Piece(IntegratedDynamicsPartSlots.CABLE, IntegratedDynamicsPartSlots.CABLE_ID,
                    cable.getItemStack()));
        }
        IFacadeable facade = new FacadeableTileMultipartTicking(tile);
        if (facade.hasFacade()) {
            ItemFacade facadeItem = RegistryEntries.ITEM_FACADE.get();
            ItemStack stack = new ItemStack(facadeItem);
            facadeItem.writeFacadeBlock(stack, facade.getFacade());
            pieces.add(new Piece(IntegratedDynamicsPartSlots.FACADE, IntegratedDynamicsPartSlots.FACADE_ID, stack));
        }
        return List.copyOf(pieces);
    }

    /**
     * Every mod member the reader calls, with the exact type of the compiled call (return type included) and the
     * owner's kind (interface or class). Built only after every class of {@link #CLASS_NAMES} was found, so a missing
     * class is named as a missing class.
     */
    private static List<Need> members() {
        return List.of(
                Need.virtual(BlockEntityMultipartTicking.class, false, "getPartContainer",
                        MethodType.methodType(PartContainerTileMultipartTicking.class)),
                Need.virtual(BlockEntityMultipartTicking.class, false, "getCableFakeable",
                        MethodType.methodType(ICableFakeable.class)),
                Need.virtual(BlockEntityMultipartTicking.class, false, "getCable",
                        MethodType.methodType(CableTileMultipartTicking.class)),
                Need.virtual(IPartContainer.class, true, "getParts", MethodType.methodType(Map.class)),
                Need.virtual(IPartType.class, true, "getItem", MethodType.methodType(Item.class)),
                Need.virtual(IPartType.class, true, "getUniqueName", MethodType.methodType(ResourceLocation.class)),
                Need.virtual(ICableFakeable.class, true, "isRealCable", MethodType.methodType(boolean.class)),
                Need.virtual(ICable.class, true, "getItemStack", MethodType.methodType(ItemStack.class)),
                Need.constructor(FacadeableTileMultipartTicking.class, false,
                        MethodType.methodType(void.class, BlockEntityMultipartTicking.class)),
                Need.virtual(IFacadeable.class, true, "hasFacade", MethodType.methodType(boolean.class)),
                Need.virtual(IFacadeable.class, true, "getFacade", MethodType.methodType(BlockState.class)),
                Need.staticGetter(RegistryEntries.class, false, "ITEM_FACADE", DeferredHolder.class),
                Need.virtual(ItemFacade.class, false, "writeFacadeBlock",
                        MethodType.methodType(void.class, ItemStack.class, BlockState.class)));
    }

    /**
     * The constructor check (design-03 0.2): each class by name without initialising it, then each member with this
     * reader's own lookup and only the named-member {@code find*} lookups, so no other signature of the class is
     * resolved and nothing is initialised or invoked. A member of the other static-or-instance shape fails the lookup
     * (IllegalAccessException, inaccessible); an owner of the other interface-or-class kind fails the compare.
     *
     * @param classNames every class, looked up first
     * @param members    the members, built only after every class was found
     * @throws MissingAdapterMemberException for the first class or member that is absent or of the wrong shape
     */
    static void check(List<String> classNames, Supplier<List<Need>> members) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassLoader loader = IntegratedDynamicsPartReader.class.getClassLoader();
        String className = null;
        Need need = null;
        try {
            for (String name : classNames) {
                className = name;
                Class.forName(name, false, loader);
            }
            for (Need member : members.get()) {
                need = member;
                if (member.owner().isInterface() != member.ownerInterface()) {
                    throw MissingAdapterMemberException.missing(member.ownerName(), member.name(),
                            member.descriptor(), null);
                }
                switch (member.kind()) {
                    case VIRTUAL -> lookup.findVirtual(member.owner(), member.name(), member.type());
                    case STATIC -> lookup.findStatic(member.owner(), member.name(), member.type());
                    case GETTER -> lookup.findGetter(member.owner(), member.name(), member.type().returnType());
                    case STATIC_GETTER ->
                            lookup.findStaticGetter(member.owner(), member.name(), member.type().returnType());
                    case CONSTRUCTOR -> lookup.findConstructor(member.owner(), member.type());
                }
            }
        } catch (ReflectiveOperationException e) {
            if (need == null) throw MissingAdapterMemberException.missingClass(className, e);
            if (e instanceof IllegalAccessException) {
                throw MissingAdapterMemberException.inaccessible(need.ownerName(), need.name(), need.descriptor(), e);
            }
            throw MissingAdapterMemberException.missing(need.ownerName(), need.name(), need.descriptor(), e);
        }
    }

    /** How the compiled call reaches a member. */
    enum Kind {
        VIRTUAL,
        STATIC,
        GETTER,
        STATIC_GETTER,
        CONSTRUCTOR
    }

    /**
     * One checked member. For a field, {@code type} is {@code ()<field type>}.
     *
     * @param ownerInterface whether the compiled call reaches the owner as an interface ({@code invokeinterface})
     */
    record Need(Kind kind, Class<?> owner, boolean ownerInterface, String name, MethodType type) {
        Need {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        static Need virtual(Class<?> owner, boolean ownerInterface, String name, MethodType type) {
            return new Need(Kind.VIRTUAL, owner, ownerInterface, name, type);
        }

        static Need staticMethod(Class<?> owner, boolean ownerInterface, String name, MethodType type) {
            return new Need(Kind.STATIC, owner, ownerInterface, name, type);
        }

        static Need getter(Class<?> owner, boolean ownerInterface, String name, Class<?> fieldType) {
            return new Need(Kind.GETTER, owner, ownerInterface, name, MethodType.methodType(fieldType));
        }

        static Need staticGetter(Class<?> owner, boolean ownerInterface, String name, Class<?> fieldType) {
            return new Need(Kind.STATIC_GETTER, owner, ownerInterface, name, MethodType.methodType(fieldType));
        }

        static Need constructor(Class<?> owner, boolean ownerInterface, MethodType type) {
            return new Need(Kind.CONSTRUCTOR, owner, ownerInterface, "<init>", type);
        }

        String ownerName() {
            return owner.getName();
        }

        /** The JVM descriptor: the method's, or the field type's for a getter. */
        String descriptor() {
            return kind == Kind.GETTER || kind == Kind.STATIC_GETTER
                    ? type.returnType().descriptorString()
                    : type.toMethodDescriptorString();
        }
    }
}
