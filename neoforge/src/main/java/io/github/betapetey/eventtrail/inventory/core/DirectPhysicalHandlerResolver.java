package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.mixin.CompoundContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One exact native null-face lookup, with no menu and no capability re-query.
 * Only source-owned finite wrapper/backing contracts are admitted. Merely
 * implementing IItemHandler or looking like a chest is never physical authority.
 */
public final class DirectPhysicalHandlerResolver {
    private static final Field SIDED_INVENTORY = field("inv");
    private static final Field SIDED_FACE = field("side");
    private static final String ARCHWOOD_TILE = "com.hollingsworth.arsnouveau.common.block.tile.ArchwoodChestTile";
    private static final String ARCHWOOD_BLOCK = "com.hollingsworth.arsnouveau.common.block.ArchwoodChest";
    private static final int SINGLE_CELLS = 27;
    private static final int MAX_CELLS = 54;
    /**
     * Build B f2-7 r3 (Advisor M-1): the Archwood tile shape the witness covers (Ars 5.13.0 to 5.13.3) declares no
     * method of its own, only constructors, so it overrides no custody method (container cells, backing list, loot
     * table, position). Cached per class; a failure is not cached and refuses.
     */
    private static final ClassValue<Boolean> ARCHWOOD_CUSTODY_SHAPE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return declaresNoMethods(type); }
    };
    private static final AtomicBoolean ARCHWOOD_SHAPE_WARNED = new AtomicBoolean();
    private static final AtomicBoolean ARCHWOOD_FAILURE_WARNED = new AtomicBoolean();
    private static final AtomicBoolean ARCHWOOD_WITHHELD_WARNED = new AtomicBoolean();

    private DirectPhysicalHandlerResolver() { }

    public static Optional<Binding> resolve(ServerLevel level, BlockHitResult hit,
            Direction capabilityFace, IItemHandler exactReturnedHandler) {
        Optional<Binding> binding = resolve(level, hit, capabilityFace, exactReturnedHandler, AuditBlockLocations::capture,
                DirectPhysicalHandlerResolver::compoundMembers,
                () -> level != null && level.getServer() != null && level.getServer().isSameThread());
        if (binding.isEmpty()) archwoodWithheld(exactReturnedHandler, DirectPhysicalHandlerResolver::compoundMembers);
        return binding;
    }

    static Optional<Binding> resolve(ServerLevel level, BlockHitResult hit,
            Direction capabilityFace, IItemHandler handler,
            BiFunction<ServerLevel, BlockPos, Optional<AuditBlockLocations.Frame>> frames,
            Function<CompoundContainer, List<Container>> compoundMembers, BooleanSupplier serverThread) {
        if (level == null || hit == null || hit.getType() != HitResult.Type.BLOCK
                || capabilityFace != null || handler == null || frames == null || compoundMembers == null
                || serverThread == null) return Optional.empty();
        try {
            if (!serverThread.getAsBoolean() || !level.hasChunkAt(hit.getBlockPos())) return Optional.empty();
            Container container = ownedContainer(handler);
            if (container == null) return Optional.empty();
            List<Container> parts;
            if (container.getClass() == CompoundContainer.class) {
                parts = compoundMembers.apply((CompoundContainer) container);
                if (parts == null || parts.size() != 2 || parts.get(0) == parts.get(1)) return Optional.empty();
            } else parts = List.of(container);
            List<Member> members = new ArrayList<>(parts.size());
            for (Container part : parts) {
                if (!(part instanceof RandomizableContainerBlockEntity block)) return Optional.empty();
                BlockPos position = block.getBlockPos().immutable();
                if (!level.hasChunkAt(position)) return Optional.empty();
                BlockState state = level.getBlockState(position);
                if (!finiteBlock(block, state, parts.size() == 2)) return Optional.empty();
                var frame = frames.apply(level, position).orElse(null);
                if (frame == null || frame.moving() || frame.level() != level || frame.frameIdentity() == null
                        || frame.stableContextKey() == null
                        || !position.equals(frame.nativePosition())) return Optional.empty();
                members.add(new Member(block, position, state, frame, frame.frameIdentity(), frame.stableContextKey()));
            }
            if (members.stream().noneMatch(member -> member.position.equals(hit.getBlockPos()))
                    || members.size() == 2 && !connected(members.get(0), members.get(1))) return Optional.empty();
            if (handler.getClass() == SidedInvWrapper.class
                    && (members.size() != 1 || members.getFirst().block.getClass() != ShulkerBoxBlockEntity.class)) {
                return Optional.empty();
            }
            if (handler.getClass() == InvWrapper.class && members.stream()
                    .anyMatch(member -> member.block.getClass() == ShulkerBoxBlockEntity.class)) return Optional.empty();
            int cells = SINGLE_CELLS * members.size();
            if (container.getContainerSize() != cells || handler.getSlots() != cells) return Optional.empty();
            var binding = new Binding(level, hit.getBlockPos().immutable(), handler, container,
                    members, cells, compoundMembers, serverThread);
            return binding.current() ? Optional.of(binding) : Optional.empty();
        } catch (RuntimeException | LinkageError | StackOverflowError invalid) { return Optional.empty(); }
    }

    private static Container ownedContainer(IItemHandler handler) {
        if (handler.getClass() == InvWrapper.class) return ((InvWrapper) handler).getInv();
        if (handler.getClass() != SidedInvWrapper.class || SIDED_INVENTORY == null || SIDED_FACE == null) return null;
        try {
            return SIDED_FACE.get(handler) == null && SIDED_INVENTORY.get(handler) instanceof Container container
                    ? container : null;
        } catch (IllegalAccessException failure) { return null; }
    }

    private static boolean finiteBlock(RandomizableContainerBlockEntity block, BlockState state, boolean compound) {
        if (state == null || block.getLootTable() != null || block.getContainerSize() != SINGLE_CELLS) return false;
        boolean chest = block.getClass() == ChestBlockEntity.class && state.is(Blocks.CHEST)
                || block.getClass() == TrappedChestBlockEntity.class && state.is(Blocks.TRAPPED_CHEST);
        if (chest) return (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) == compound;
        if (compound) return false;
        if (block.getClass() == BarrelBlockEntity.class) return state.is(Blocks.BARREL);
        if (block.getClass() == ShulkerBoxBlockEntity.class) return state.getBlock().getClass() == ShulkerBoxBlock.class
                && "minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
        // The witness (Ars 5.13.0 to 5.13.3) shows this exact Ars class only adding constructors to vanilla
        // finite chest storage; exactArchwoodClasses refuses any tile that declares a method of its own (M-1).
        // Its own provider wraps this tile (never the compound).
        return ARCHWOOD_TILE.equals(block.getClass().getName())
                && block.getClass().getSuperclass() == ChestBlockEntity.class
                && ARCHWOOD_BLOCK.equals(state.getBlock().getClass().getName())
                && state.getBlock().getClass().getSuperclass() == ChestBlock.class
                && "ars_nouveau:archwood_chest".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE && exactArchwoodClasses(block, state);
    }

    // Build B (D7): no Ars version or byte admission. The live object is recognised by the checks above and the exact
    // class identity below, resolved through EventTrail's own class loader; a missing Archwood class matches nothing.
    // f2-7 r3 (M-1): after the identity checks, the tile must have the witnessed custody shape, inside the same catch.
    private static boolean exactArchwoodClasses(BlockEntity block, BlockState state) {
        try {
            ClassLoader loader = DirectPhysicalHandlerResolver.class.getClassLoader();
            boolean exact = block.getClass() == Class.forName(ARCHWOOD_TILE, false, loader)
                    && state.getBlock().getClass() == Class.forName(ARCHWOOD_BLOCK, false, loader);
            return exact && witnessedCustodyShape(block.getClass());
        } catch (ClassNotFoundException | RuntimeException | LinkageError | StackOverflowError failure) {
            warnOnce(ARCHWOOD_FAILURE_WARNED, "Ars Nouveau Archwood chest check failed ({}); that deposit is not recorded"
                    + " at the chest. Later Archwood check failures are not logged until restart",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * M-1: refuse-only. True only for a tile class that declares no method (the witnessed shape); any other shape is
     * refused with one warning, and only the Archwood binding is refused (ARS_ITEM stays on for other targets).
     */
    static boolean witnessedCustodyShape(Class<?> tile) {
        if (ARCHWOOD_CUSTODY_SHAPE.get(tile)) return true;
        warnOnce(ARCHWOOD_SHAPE_WARNED, "Ars Nouveau Archwood chest deposits are not recorded at the chest: the tile class"
                + " {} declares methods of its own, and only the witnessed shape with none (vanilla chest storage) is"
                + " bound. Every Archwood chest deposit is withheld until restart", tile.getName());
        return false;
    }

    /** The shape rule itself (no declared method; constructors are not methods). */
    static boolean declaresNoMethods(Class<?> type) { return type.getDeclaredMethods().length == 0; }

    /**
     * M-3: a deposit whose handler owns an Archwood-named tile was withheld. Log it once, so a server shows whether
     * Archwood deposits are recorded at all. Read-only. It swallows a RuntimeException, LinkageError or
     * StackOverflowError; any other throwable propagates.
     */
    private static void archwoodWithheld(IItemHandler handler, Function<CompoundContainer, List<Container>> compoundMembers) {
        if (handler == null || ARCHWOOD_WITHHELD_WARNED.get()) return;
        try {
            Container container = ownedContainer(handler);
            if (container == null) return;
            List<Container> parts = container.getClass() == CompoundContainer.class
                    ? compoundMembers.apply((CompoundContainer) container) : List.of(container);
            if (parts == null || parts.stream().noneMatch(part -> part != null
                    && ARCHWOOD_TILE.equals(part.getClass().getName()))) return;
            warnOnce(ARCHWOOD_WITHHELD_WARNED, "An Ars Nouveau Archwood chest deposit was not recorded at the chest:"
                    + " only a single 27-cell Archwood chest with no loot table, at a stationary position, that passes"
                    + " every check is bound (double and loot-bearing Archwood chests never are; if an earlier Archwood"
                    + " warning was logged, it names the reason). Later Archwood withholds are not logged until restart");
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) { }
    }

    private static void warnOnce(AtomicBoolean once, String message, Object... details) {
        if (!once.compareAndSet(false, true)) return;
        try {
            LogManager.getLogger("eventtrail").warn(message, details);
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private static boolean connected(Member first, Member second) {
        ChestType type = first.state.getValue(ChestBlock.TYPE);
        return first.block != second.block && !first.position.equals(second.position)
                && first.state.getBlock() == second.state.getBlock() && type != ChestType.SINGLE
                && second.state.getValue(ChestBlock.TYPE) == (type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT)
                && first.state.getValue(ChestBlock.FACING) == second.state.getValue(ChestBlock.FACING)
                && first.position.relative(ChestBlock.getConnectedDirection(first.state)).equals(second.position)
                && second.position.relative(ChestBlock.getConnectedDirection(second.state)).equals(first.position);
    }

    private static List<Container> compoundMembers(CompoundContainer compound) {
        CompoundContainerAccessor accessor = (CompoundContainerAccessor) (Object) compound;
        return List.of(accessor.eventtrail$firstContainer(), accessor.eventtrail$secondContainer());
    }

    private static Field field(String name) {
        try { Field field = SidedInvWrapper.class.getDeclaredField(name); return field.trySetAccessible() ? field : null; }
        catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    private record Member(RandomizableContainerBlockEntity block, BlockPos position, BlockState state,
            AuditBlockLocations.Frame frame, Object frameIdentity, String frameKey) { }

    public static final class Binding {
        private final ServerLevel level;
        private final BlockPos accessPosition;
        private final IItemHandler handler;
        private final Container container;
        private final List<Member> members;
        private final int cells;
        private final Function<CompoundContainer, List<Container>> compoundMembers;
        private final BooleanSupplier serverThread;
        private boolean valid = true;

        private Binding(ServerLevel level, BlockPos accessPosition, IItemHandler handler, Container container,
                List<Member> members, int cells, Function<CompoundContainer, List<Container>> compoundMembers,
                BooleanSupplier serverThread) {
            this.level = level; this.accessPosition = accessPosition; this.handler = handler; this.container = container;
            this.members = List.copyOf(members); this.cells = cells; this.compoundMembers = compoundMembers;
            this.serverThread = serverThread;
        }

        public boolean owns(IItemHandler exactHandler) { return handler == exactHandler && current(); }

        public boolean current() {
            if (!valid) return false;
            try {
                if (!serverThread.getAsBoolean() || ownedContainer(handler) != container
                        || cells < 1 || cells > MAX_CELLS || handler.getSlots() != cells
                        || container.getContainerSize() != cells) return invalidate();
                if (members.size() == 2) {
                    List<Container> parts = compoundMembers.apply((CompoundContainer) container);
                    if (parts == null || parts.size() != 2 || parts.get(0) != members.get(0).block
                            || parts.get(1) != members.get(1).block || !connected(members.get(0), members.get(1))) return invalidate();
                }
                for (Member member : members) {
                    if (!level.hasChunkAt(member.position) || member.block.isRemoved()
                            || member.block.getLevel() != level || !member.position.equals(member.block.getBlockPos())
                            || level.getBlockEntity(member.position) != member.block
                            || level.getBlockState(member.position) != member.state
                            || member.block.getBlockState() != member.state
                            || !finiteBlock(member.block, member.state, members.size() == 2)
                            || member.frame.moving() || member.frame.level() != level
                            || !member.position.equals(member.frame.nativePosition())
                            || member.frame.frameIdentity() != member.frameIdentity
                            || !java.util.Objects.equals(member.frameKey, member.frame.stableContextKey())
                            || !member.frame.isCurrent()) return invalidate();
                    var location = member.frame.currentLocation().orElse(null);
                    if (location == null || !location.dimensionId().equals(level.dimension().location())
                            || !location.position().equals(member.position)) return invalidate();
                }
                return true;
            } catch (RuntimeException | LinkageError | StackOverflowError invalid) { return invalidate(); }
        }

        /** A complete bounded copy, with native handler-to-cell equivalence checked. */
        public Optional<List<ItemStack>> snapshot() {
            if (!current()) return Optional.empty();
            try {
                List<ItemStack> stacks = new ArrayList<>(cells);
                for (int slot = 0; slot < cells; slot++) {
                    ItemStack nativeCell = handler.getStackInSlot(slot), ownedCell = container.getItem(slot);
                    if (nativeCell == null || ownedCell == null || !ItemStack.matches(nativeCell, ownedCell)) {
                        invalidate(); return Optional.empty();
                    }
                    stacks.add(nativeCell.copy());
                }
                return current() ? Optional.of(List.copyOf(stacks)) : Optional.empty();
            } catch (RuntimeException | LinkageError | StackOverflowError invalid) { invalidate(); return Optional.empty(); }
        }

        /** Called immediately after native insertion: no mutable target escapes. */
        public Optional<ContainerTarget> committedTarget() {
            if (!current()) return Optional.empty();
            var positions = members.stream().map(Member::position).sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).toList();
            StringBuilder key = new StringBuilder(level.dimension().location().toString());
            positions.forEach(position -> key.append('|').append(position.getX()).append(',')
                    .append(position.getY()).append(',').append(position.getZ()));
            Member touched = members.stream().filter(member -> member.position.equals(accessPosition)).findFirst().orElseThrow();
            String label = BuiltInRegistries.BLOCK.getKey(touched.state.getBlock()).toString();
            try { label = touched.state.getBlock().getName().getString(); } catch (RuntimeException ignored) { }
            return Optional.of(ContainerTarget.block(level.dimension().location().toString(), accessPosition, label,
                    members.size() == 1 ? BackingContextKind.WORLD_BLOCK : BackingContextKind.COMPOUND_BLOCK, key.toString()));
        }

        private boolean invalidate() { valid = false; return false; }
    }
}
