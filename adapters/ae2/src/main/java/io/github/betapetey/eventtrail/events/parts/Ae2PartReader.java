package io.github.betapetey.eventtrail.events.parts;

import appeng.api.parts.IFacadeContainer;
import appeng.api.parts.IFacadePart;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.util.SettingsFrom;
import io.github.betapetey.eventtrail.adapters.MissingAdapterMemberException;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The AE2 part reader (parts readers design-03 section 1; key PARTS_AE2, mod id ae2), loaded by class name from the
 * bridge's reader table only when AE2 is loaded. Compiled against AE2 19.2.17.
 *
 * <p>It claims every AE2 cable bus ({@code IPartHost}) and every other AE2 or addon block entity
 * ({@code AEBaseBlockEntity}); a cable bus reports its cable, six side parts and six facades (at most 13 pieces, keys
 * from {@link Ae2PartSlots}); every other claimed block entity reports no pieces, so only the bridge's block-gone rule
 * applies (the machine take-down).</p>
 *
 * <p>The reader's own steps (the {@link PartReader} contract):</p>
 * <ul>
 *   <li>Piece items: a part names its item as AE2's own drop builds it ({@code IPart.addPartDrop}: the part item,
 *       then the part's {@code exportSettings(DISMANTLE_ITEM)} export), keeping every exported component, so a part
 *       is named as it drops, a renamed part as the player sees it (see {@link #partStack}). A facade names
 *       {@code IFacadePart.getItemStack()}.</li>
 *   <li>Hand-over: AE2's wrench take-down ({@code disassembleWithWrench}) gives every stack to
 *       {@code Inventory.placeItemBackInInventory} (the player's inventory) and drops the overflow with
 *       {@code Player.drop} (the drop point).</li>
 *   <li>The cable bus block entity survives a piece removal (the container is edited in place); the last piece
 *       removes the block. A crafting-unit upgrade replaces the block entity (the bridge's block-replaced rule).</li>
 *   <li>One piece is one item, and AE2 completes the removal before the right-click returns.</li>
 *   <li>No AE2 class overrides {@code onDestroyedByPlayer}: a left-click breaks the whole block through the existing
 *       break path.</li>
 * </ul>
 *
 * <p>The constructor checks every AE2 class and member this file calls, once per server start, with this class's own
 * {@link MethodHandles#lookup()} and named-member lookups only (design-03 0.2), so a changed AE2 switches the key OFF
 * at server start with the member named. No state, no world access.</p>
 */
public final class Ae2PartReader implements PartReader {
    /** Every AE2 class this file names, the first one first (design-03 0.8: the absent-mod path names it). */
    static final List<String> CLASSES = List.of(
            "appeng.api.parts.IPartHost",
            "appeng.api.parts.IPart",
            "appeng.api.parts.IPartItem",
            "appeng.api.parts.IFacadeContainer",
            "appeng.api.parts.IFacadePart",
            "appeng.blockentity.AEBaseBlockEntity",
            "appeng.util.SettingsFrom");

    public Ae2PartReader() {
        check(CLASSES, Ae2PartReader::members);
    }

    @Override
    public boolean claims(BlockEntity blockEntity) {
        return blockEntity instanceof IPartHost || blockEntity instanceof AEBaseBlockEntity;
    }

    @Override
    public List<Piece> pieces(BlockEntity blockEntity) {
        if (!(blockEntity instanceof IPartHost host)) return List.of();
        List<Piece> pieces = new ArrayList<>(Ae2PartSlots.MAX_PIECES);
        addPart(pieces, null, host.getPart(null));
        for (Direction side : Ae2PartSlots.SIDES) addPart(pieces, side, host.getPart(side));
        IFacadeContainer facades = host.getFacadeContainer();
        if (facades != null) {
            for (Direction side : Ae2PartSlots.SIDES) {
                IFacadePart facade = facades.getFacade(side);
                if (facade != null) pieces.add(piece(Ae2PartSlots.facadeSlot(side), facade.getItemStack()));
            }
        }
        return List.copyOf(pieces);
    }

    private static void addPart(List<Piece> pieces, Direction side, IPart part) {
        if (part == null) return;
        DataComponentMap.Builder exported = DataComponentMap.builder();
        part.exportSettings(SettingsFrom.DISMANTLE_ITEM, exported);
        pieces.add(piece(Ae2PartSlots.partSlot(side), partStack(part.getPartItem(), exported.build())));
    }

    /**
     * A part's piece item, built as AE2's own drop builds it ({@code IPart.addPartDrop}: {@code new ItemStack} of the
     * part item, then every component of the part's {@code exportSettings(DISMANTLE_ITEM)} export applied to it).
     * F6 (KL-202): the whole export is kept (a custom name, settings, enchantments and the like), so a piece-line row
     * names the part as it drops. An empty export gives the same stack as the part item alone. Plain game types
     * only, so the unit test can run it without AE2.
     */
    static ItemStack partStack(ItemLike partItem, DataComponentMap exported) {
        ItemStack stack = new ItemStack(partItem);
        stack.applyComponents(exported);
        return stack;
    }

    private static Piece piece(String slot, ItemStack item) {
        ItemStack named = item == null ? ItemStack.EMPTY : item;
        return new Piece(slot, Ae2PartSlots.pieceId(BuiltInRegistries.ITEM.getKey(named.getItem()).toString()), named);
    }

    /**
     * Every AE2 member the calls above use, with the exact types of the compiled call (return type included), as the
     * same class literals. Built only after every class of {@link #CLASSES} was found.
     */
    private static List<Member> members() {
        return List.of(
                Member.method(IPartHost.class, Call.INTERFACE, "getPart",
                        MethodType.methodType(IPart.class, Direction.class)),
                Member.method(IPartHost.class, Call.INTERFACE, "getFacadeContainer",
                        MethodType.methodType(IFacadeContainer.class)),
                Member.method(IPart.class, Call.INTERFACE, "getPartItem",
                        MethodType.methodType(IPartItem.class)),
                Member.method(IPart.class, Call.INTERFACE, "exportSettings",
                        MethodType.methodType(void.class, SettingsFrom.class, DataComponentMap.Builder.class)),
                Member.staticField(SettingsFrom.class, "DISMANTLE_ITEM", SettingsFrom.class),
                Member.supertype(IPartItem.class, ItemLike.class),
                Member.method(IFacadeContainer.class, Call.INTERFACE, "getFacade",
                        MethodType.methodType(IFacadePart.class, Direction.class)),
                Member.method(IFacadePart.class, Call.INTERFACE, "getItemStack",
                        MethodType.methodType(ItemStack.class)));
    }

    /**
     * The start-time check (design-03 0.2): each class by {@code Class.forName(name, false, loader)} (nothing is
     * initialised), then each member by this class's own lookup ({@code findVirtual}, {@code findStatic} or
     * {@code findStaticGetter} by the compiled call's shape; nothing is invoked or initialised), plus one
     * {@code isInterface()} compare per method owner and the supertype compares. Throws {@link MissingAdapterMemberException} (MISSING_MEMBER) naming the first class or
     * member that is absent or has another shape. Package-private only so the unit test can run it against JDK
     * classes.
     */
    static void check(List<String> classNames, Supplier<List<Member>> members) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassLoader loader = Ae2PartReader.class.getClassLoader();
        String owner = "";
        String name = null;
        String descriptor = null;
        try {
            for (String className : classNames) {
                owner = className;
                Class.forName(className, false, loader);
            }
            for (Member member : members.get()) {
                owner = member.owner().getName();
                name = member.name();
                descriptor = member.descriptor();
                switch (member.call()) {
                    case INTERFACE, VIRTUAL -> {
                        if (member.owner().isInterface() != (member.call() == Call.INTERFACE)) {
                            throw MissingAdapterMemberException.missing(owner, name, descriptor, null);
                        }
                        lookup.findVirtual(member.owner(), name, member.type());
                    }
                    case STATIC, STATIC_INTERFACE -> {
                        if (member.owner().isInterface() != (member.call() == Call.STATIC_INTERFACE)) {
                            throw MissingAdapterMemberException.missing(owner, name, descriptor, null);
                        }
                        lookup.findStatic(member.owner(), name, member.type());
                    }
                    case STATIC_FIELD -> lookup.findStaticGetter(member.owner(), name, member.type().returnType());
                    case SUPERTYPE -> {
                        if (!member.superType().isAssignableFrom(member.owner())) {
                            throw MissingAdapterMemberException.missing(owner, name, null, null);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            if (e instanceof ClassNotFoundException) throw MissingAdapterMemberException.missingClass(owner, e);
            if (e instanceof IllegalAccessException) {
                throw MissingAdapterMemberException.inaccessible(owner, name, descriptor, e);
            }
            throw MissingAdapterMemberException.missing(owner, name, descriptor, e);
        }
    }

    /** How the compiled call reaches a checked member. */
    enum Call {
        /** {@code invokeinterface}: an instance method of an interface. */
        INTERFACE,
        /** {@code invokevirtual}: an instance method of a class. */
        VIRTUAL,
        /** {@code invokestatic}: a static method of a class. */
        STATIC,
        /** {@code invokestatic} through an interface method reference: a static method of an interface. */
        STATIC_INTERFACE,
        /** {@code getstatic}: a static field; {@code type} is {@code ()<field type>}. */
        STATIC_FIELD,
        /** The owner must stay a subtype of {@code superType}: the reader hands its values on as that type. */
        SUPERTYPE
    }

    /**
     * One checked member: its owner, call shape, name and exact method type (for a static field {@code ()<field
     * type>}), or the required supertype.
     */
    record Member(Class<?> owner, Call call, String name, MethodType type, Class<?> superType) {
        Member {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(name, "name");
            if ((call == Call.SUPERTYPE) != (superType != null) || (call == Call.SUPERTYPE) == (type != null)) {
                throw new IllegalArgumentException("a method needs a type, a supertype compare a supertype");
            }
        }

        static Member method(Class<?> owner, Call call, String name, MethodType type) {
            return new Member(owner, call, name, Objects.requireNonNull(type, "type"), null);
        }

        static Member staticField(Class<?> owner, String name, Class<?> fieldType) {
            return new Member(owner, Call.STATIC_FIELD, name, MethodType.methodType(fieldType), null);
        }

        static Member supertype(Class<?> owner, Class<?> superType) {
            return new Member(owner, Call.SUPERTYPE, "extends " + superType.getName(), null, superType);
        }

        /** The JVM descriptor: the method's, the field type's for a static field, or null for a supertype compare. */
        String descriptor() {
            if (type == null) return null;
            return call == Call.STATIC_FIELD ? type.returnType().descriptorString() : type.toMethodDescriptorString();
        }
    }
}
