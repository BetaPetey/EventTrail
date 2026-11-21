package io.github.betapetey.eventtrail.events.parts;

import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.Conduit;
import com.enderio.enderio.api.conduits.ConduitApi;
import com.enderio.enderio.api.conduits.ConduitType;
import com.enderio.enderio.api.conduits.bundle.ConduitBundle;
import com.enderio.enderio.foundation.block.entity.Wrenchable;
import io.github.betapetey.eventtrail.compat.MissingAdapterMemberException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Ender IO part reader (parts readers design-03 section 3; key PARTS_ENDERIO, mod id enderio), loaded by class
 * name from the bridge's reader table only when Ender IO is loaded. Compiled against Ender IO 8.2.12-beta.
 *
 * <p>It claims every Ender IO conduit bundle ({@code ConduitBundle}) and every other Ender IO block entity that the
 * Ender IO wrench takes down ({@code Wrenchable}: the machines); a bundle reports one piece per conduit, keyed by its
 * conduit type, and its facade (at most 10 pieces, keys from {@link EnderIoPartSlots}); every other claimed block
 * entity reports no pieces, so only the bridge's block-gone rule applies (the sneak + wrench machine take-down).</p>
 *
 * <p>The reader's own steps (the {@link PartReader} contract):</p>
 * <ul>
 *   <li>Piece items: a conduit names {@code ConduitApi.INSTANCE.getConduitItem(holder)}, which is Ender IO's own drop
 *       builder ({@code ConduitBlockItem.getStackFor(holder, 1)}: the conduit item with the conduit component and no
 *       name; {@code removeConduit} hands over exactly that stack). A facade names a copy of
 *       {@code getFacadeProvider()}: Ender IO drops that stored stack itself ({@code dropFacadeItem}), so the copy
 *       carries every component of the drop, a custom name included, and nothing is rebuilt.</li>
 *   <li>Hand-over: a left-click removal gives the item to {@code Block.popResource} (inside the claimed block's cell);
 *       a facade or sneak + wrench removal gives it to the bundle's {@code dropItem}, an item entity at the block's
 *       centre (inside the cell); neither in creative. A conduit's connection inventories (filters, upgrades) leave
 *       through the same routine. The machine take-down gives every drop to {@code Inventory.placeItemBackInInventory}
 *       (the player's inventory), the overflow at the drop point.</li>
 *   <li>The bundle block entity survives a piece removal (its conduit list and facade field are edited in place); the
 *       last conduit taken out with the wrench sets the old fluid's block (gone).</li>
 *   <li>One piece is one item (one conduit item per conduit), and Ender IO completes the removal before the
 *       right-click or {@code onDestroyedByPlayer} returns.</li>
 *   <li>{@code ConduitBundleBlock.onDestroyedByPlayer} returns false for a single-piece removal (the block stays, the
 *       bridge decides); it returns true (the existing break path owns the action) only in the simple cases where the
 *       whole block goes: no conduit, one conduit and no facade, or a facade and no conduit.</li>
 * </ul>
 *
 * <p>The constructor checks every Ender IO class and member this file calls, once per server start, with this class's
 * own {@link MethodHandles#lookup()} and named-member lookups only (design-03 0.2), so a changed Ender IO switches the
 * key OFF at server start with the member named. No state, no world access.</p>
 */
public final class EnderIoPartReader implements PartReader {
    /** Every Ender IO class this file names, the first one first (design-03 0.8: the absent-mod path names it). */
    static final List<String> CLASSES = List.of(
            "com.enderio.enderio.api.conduits.bundle.ConduitBundle",
            "com.enderio.enderio.api.conduits.Conduit",
            "com.enderio.enderio.api.conduits.ConduitType",
            "com.enderio.enderio.api.EnderIORegistries",
            "com.enderio.enderio.api.conduits.ConduitApi",
            "com.enderio.enderio.foundation.block.entity.Wrenchable");

    public EnderIoPartReader() {
        check(CLASSES, EnderIoPartReader::members);
    }

    @Override
    public boolean claims(BlockEntity blockEntity) {
        return blockEntity instanceof ConduitBundle || blockEntity instanceof Wrenchable;
    }

    @Override
    public List<Piece> pieces(BlockEntity blockEntity) {
        if (!(blockEntity instanceof ConduitBundle bundle)) return List.of();
        List<Piece> pieces = new ArrayList<>(EnderIoPartSlots.MAX_PIECES);
        for (Holder<Conduit<?, ?>> conduit : bundle.getConduits()) addConduit(pieces, conduit);
        if (bundle.hasFacade()) {
            ItemStack facade = bundle.getFacadeProvider();
            ItemStack named = facade == null ? ItemStack.EMPTY : facade.copy();
            pieces.add(new Piece(EnderIoPartSlots.FACADE, EnderIoPartSlots.pieceId(registryId(named)), named));
        }
        return List.copyOf(pieces);
    }

    /**
     * One conduit's piece: the slot of its conduit type (an unregistered type throws, so the action is skipped with a
     * warning), its registered name as the piece id, and Ender IO's own drop stack as the item.
     */
    private static void addConduit(List<Piece> pieces, Holder<Conduit<?, ?>> conduit) {
        ResourceLocation type = EnderIORegistries.CONDUIT_TYPE.getKey(conduit.value().type());
        String slot = EnderIoPartSlots.conduitSlot(type == null ? null : type.toString());
        pieces.add(new Piece(slot, EnderIoPartSlots.pieceId(conduit.getRegisteredName()),
                ConduitApi.INSTANCE.getConduitItem(conduit)));
    }

    private static String registryId(ItemStack item) {
        return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
    }

    /**
     * Every Ender IO member the calls above use, with the exact types of the compiled call (return type included), as
     * the same class literals. Built only after every class of {@link #CLASSES} was found.
     */
    private static List<Member> members() {
        return List.of(
                Member.method(ConduitBundle.class, Call.INTERFACE, "getConduits",
                        MethodType.methodType(List.class)),
                Member.method(ConduitBundle.class, Call.INTERFACE, "hasFacade",
                        MethodType.methodType(boolean.class)),
                Member.method(ConduitBundle.class, Call.INTERFACE, "getFacadeProvider",
                        MethodType.methodType(ItemStack.class)),
                Member.method(Conduit.class, Call.INTERFACE, "type",
                        MethodType.methodType(ConduitType.class)),
                Member.staticField(EnderIORegistries.class, "CONDUIT_TYPE", Registry.class),
                Member.staticField(ConduitApi.class, "INSTANCE", ConduitApi.class),
                Member.method(ConduitApi.class, Call.INTERFACE, "getConduitItem",
                        MethodType.methodType(ItemStack.class, Holder.class)));
    }

    /**
     * The start-time check (design-03 0.2): each class by {@code Class.forName(name, false, loader)} (nothing is
     * initialised), then each member by this class's own lookup ({@code findVirtual}, {@code findStatic} or
     * {@code findStaticGetter} by the compiled call's shape; nothing is invoked or initialised), plus one
     * {@code isInterface()} compare per method owner. Throws {@link MissingAdapterMemberException} (MISSING_MEMBER)
     * naming the first class or member that is absent or has another shape. Package-private only so the unit test can
     * run it against JDK classes.
     */
    static void check(List<String> classNames, Supplier<List<Member>> members) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassLoader loader = EnderIoPartReader.class.getClassLoader();
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
        /** {@code getstatic}: a static field (of a class or an interface); {@code type} is {@code ()<field type>}. */
        STATIC_FIELD
    }

    /** One checked member: its owner, call shape, name and exact method type (for a static field {@code ()<field type>}). */
    record Member(Class<?> owner, Call call, String name, MethodType type) {
        Member {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (call == Call.STATIC_FIELD && type.parameterCount() != 0) {
                throw new IllegalArgumentException("a static field's type is ()<field type>");
            }
        }

        static Member method(Class<?> owner, Call call, String name, MethodType type) {
            if (call == Call.STATIC_FIELD) throw new IllegalArgumentException("a method needs a method call shape");
            return new Member(owner, call, name, type);
        }

        static Member staticField(Class<?> owner, String name, Class<?> fieldType) {
            return new Member(owner, Call.STATIC_FIELD, name, MethodType.methodType(fieldType));
        }

        /** The JVM descriptor: the method's, or the field type's for a static field. */
        String descriptor() {
            return call == Call.STATIC_FIELD ? type.returnType().descriptorString() : type.toMethodDescriptorString();
        }
    }
}
