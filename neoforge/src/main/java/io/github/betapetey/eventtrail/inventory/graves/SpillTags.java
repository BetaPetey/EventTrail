package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.events.platform.CommandPresence;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.OriginLabelView;
import io.github.betapetey.eventtrail.inventory.graves.api.OwnerPortion;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exact call-site spill tagging (graves design r6, 1.6.4): the mod-side wrap around a spill call sets a thread-local
 * pending spill, and the base spawn-helper mixins tag the exact {@link ItemEntity} the helper adds, before it is added.
 *
 * <p><b>Origins.</b> A pending spill takes its origin from the innermost open frame on this thread: a grave take's
 * context ({@link GraveTakes} opens it only after steps 0 and 0b pass, 1.3) or an outside-take origin ({@link
 * #outsideTake}, a removal, drop or timer). With no frame open, a spill wrap sets nothing.</p>
 *
 * <p><b>Inside a take</b> a stack whose object reference is one of the taker's pre-take stacks is his own: untagged,
 * its joined units counted in {@code ownSpilled}, capped per reference by its pre-take count; units above the cap are
 * grave units the mod merged in, tagged (a grave part plus an {@code unlabelled} own part) and counted in
 * {@code spilledGrave}. Any other stack is a grave stack, tagged from the take's piles and counted in
 * {@code spilledGrave}. A site declared as handing the helper a copy is tagged all {@code unknown}, counted in
 * {@code uncertainSpilled} and {@code grave-spill-copied}, never credited. In the take of a grave whose contents arrive
 * during the call (graves r6 2.6), the piles are empty until the mod reports its loaded list, so a spill before the
 * report is tagged from empty piles, all {@code unknown}. <b>Outside a take</b> every stack is a grave stack, tagged
 * from the origin's piles.</p>
 *
 * <p><b>Helper rules</b> (1.6.4 rules 1 to 4): only units of entities whose {@code addFreshEntity} returned true are
 * counted, summed per entity; a cancelled join counts nowhere ({@code origin-join-cancelled}); a pending spill lasts
 * for its one helper call and is cleared in a {@code finally}; nested pending spills stack and the innermost wins;
 * several owner portions are split over the entities in the order they are added, with the pile rule on what remains
 * after each; a pending spill beats a window ({@link SpillWindow}).</p>
 *
 * <p><b>Inside a command</b> (1.7) no pending spill and no outside-take origin is set, so nothing is tagged.</p>
 */
public final class SpillTags {
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Pending>> PENDING = ThreadLocal.withInitial(ArrayDeque::new);

    private SpillTags() {
    }

    /** The origin of spills outside a take: the grave and its piles (owner portions per identity). */
    public record SpillOrigin(GraveIdentity grave, Map<InventoryItemIdentity, OwnerPortions.Pile> piles) {
        public SpillOrigin {
            Objects.requireNonNull(grave, "grave");
            piles = Map.copyOf(piles);
        }

        /** The origin of a grave whose contents and portions were read before the spill call. */
        public static SpillOrigin of(GraveIdentity grave, Map<InventoryItemIdentity, Long> contents,
                List<OwnerPortion> portions) {
            return new SpillOrigin(grave, OwnerPortions.piles(contents, portions));
        }
    }

    /** What an open frame gives a spill: the grave id and the piles that the spills draw down. */
    abstract static sealed class Frame permits TakeFrame, OutsideFrame {
        final ResourceLocation graveId;
        final Map<InventoryItemIdentity, OwnerPortions.Pile> piles;

        Frame(ResourceLocation graveId, Map<InventoryItemIdentity, OwnerPortions.Pile> piles) {
            this.graveId = Objects.requireNonNull(graveId, "graveId");
            this.piles = new LinkedHashMap<>(piles);
        }

        OwnerPortions.Pile pile(InventoryItemIdentity item) {
            OwnerPortions.Pile pile = piles.get(item);
            return pile != null ? pile : OwnerPortions.Pile.empty(item);
        }
    }

    /** An open take's context (1.3 steps 1 and 3). Confined to the take's thread. */
    static final class TakeFrame extends Frame {
        /** Each own reference's {pre-take count, units spilled so far}. */
        final IdentityHashMap<ItemStack, long[]> own;
        final Map<InventoryItemIdentity, Long> ownSpilled = new HashMap<>();
        final Map<InventoryItemIdentity, Long> spilledGrave = new HashMap<>();
        final Map<InventoryItemIdentity, Long> uncertainSpilled = new HashMap<>();
        final Map<InventoryItemIdentity, Long> lost = new HashMap<>();
        /** Graves r6 2.6: the piles of a list loaded during the call (contentsDuringCall), else null. */
        Function<Map<InventoryItemIdentity, Long>, Map<InventoryItemIdentity, OwnerPortions.Pile>> pilesOfLoaded;
        /** The contents the mod reported during the call (the first report only), else null. */
        Map<InventoryItemIdentity, Long> loaded;
        /** The failure of the take's first report during the call (it counts as the report), else null. */
        Throwable loadFailure;

        TakeFrame(ResourceLocation graveId, Map<InventoryItemIdentity, OwnerPortions.Pile> piles,
                IdentityHashMap<ItemStack, long[]> own) {
            super(graveId, piles);
            this.own = own;
        }
    }

    static final class OutsideFrame extends Frame {
        OutsideFrame(SpillOrigin origin) {
            super(origin.grave().graveId(), origin.piles());
        }
    }

    enum Kind {
        /** The taker's own displaced stack (inside a take). */
        OWN,
        /** A grave stack. */
        GRAVE,
        /** A site that passes a copy (inside a take): all unknown, never credited. */
        COPY
    }

    private record Pending(Frame frame, Kind kind, ItemStack reference) {
    }

    /** A tentative tag for one entity, committed only if its join succeeds. */
    record Plan(Frame frame, OriginLabelView label, InventoryItemIdentity item, Kind kind, ItemStack reference,
            long ownUnits, long graveUnits, long copiedUnits, OwnerPortions.Pile remaining) {
    }

    /**
     * The mod-side wrap around one spill call (a {@code Containers.dropItemStack} or
     * {@code ItemHandlerHelper.giveItemToPlayer} call in the mod's code): sets the pending spill for {@code stack} for
     * the extent of {@code helperCall}, which runs exactly once, and clears it in a {@code finally}.
     * {@code passesCopy} is the fresh-jar fact that this site hands the helper a copy (1.3-3 "Copies").
     */
    public static void atSpill(ItemStack stack, boolean passesCopy, Runnable helperCall) {
        Objects.requireNonNull(helperCall, "helperCall");
        Pending pending = null;
        try {
            pending = pending(stack, passesCopy);
            if (pending != null) PENDING.get().push(pending);
        } catch (Throwable unreadable) {
            pending = null;
        }
        if (pending == null) {
            helperCall.run();
            return;
        }
        try {
            helperCall.run();
        } finally {
            pop(PENDING.get(), pending);
        }
    }

    /**
     * The mod-side wrap around a spill outside a take (a removal, a drop, a timer): opens {@code origin}, read before the
     * call, for the extent of {@code body}, which runs exactly once. Inside a command nothing is opened (1.7).
     */
    public static void outsideTake(Supplier<SpillOrigin> origin, Runnable body) {
        Objects.requireNonNull(body, "body");
        Frame frame = null;
        try {
            if (!CommandPresence.inCommand()) {
                SpillOrigin read = origin.get();
                if (read != null) {
                    frame = new OutsideFrame(read);
                    FRAMES.get().push(frame);
                }
            }
        } catch (Throwable unreadable) {
            frame = null;
        }
        if (frame == null) {
            body.run();
            return;
        }
        try {
            body.run();
        } finally {
            pop(FRAMES.get(), frame);
        }
    }

    /** Whether a pending spill is set on this thread. */
    public static boolean pendingOpen() {
        try {
            return !PENDING.get().isEmpty();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Whether a grave take's context is open on this thread. */
    static boolean takeOpen() {
        try {
            for (Frame frame : FRAMES.get()) {
                if (frame instanceof TakeFrame) return true;
            }
            return false;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** The innermost open frame, or null. */
    static Frame currentFrame() {
        return FRAMES.get().peek();
    }

    /** The innermost open take context, or null when the innermost frame is not a take. */
    static TakeFrame currentTake() {
        return FRAMES.get().peek() instanceof TakeFrame take ? take : null;
    }

    static void openTake(TakeFrame frame) {
        FRAMES.get().push(Objects.requireNonNull(frame, "frame"));
    }

    static void closeTake(TakeFrame frame) {
        pop(FRAMES.get(), frame);
    }

    /**
     * The base spawn-helper mixins' entry, at the {@code addFreshEntity} call inside the helper: with a pending spill
     * set and an item entity, its label is written before the add, and its units are counted only if the add returns
     * true. {@code add} runs exactly once and its result is returned unchanged; its exception passes unchanged.
     */
    public static boolean addFreshEntity(Level level, Entity entity, BooleanSupplier add) {
        ItemStack stack = null;
        CompoundTag data = null;
        HolderLookup.Provider registries = null;
        try {
            if (pendingOpen() && entity instanceof ItemEntity item) {
                stack = item.getItem();
                data = item.getPersistentData();
                registries = level.registryAccess();
            }
        } catch (Throwable unreadable) {
            stack = null;
        }
        if (stack == null || data == null || registries == null) return add.getAsBoolean();
        return spawn(stack, data, registries, add);
    }

    /** {@link #addFreshEntity} after the entity reads (test seam). */
    static boolean spawn(ItemStack entityStack, CompoundTag persistentData, HolderLookup.Provider registries,
            BooleanSupplier add) {
        Plan plan = null;
        try {
            Pending pending = PENDING.get().peek();
            if (pending != null && !entityStack.isEmpty()) {
                plan = plan(pending.frame(), pending.kind(), pending.reference(), entityStack, registries);
                if (plan.label() != null) OriginLabelCodec.write(persistentData, plan.label());
            }
        } catch (Throwable failure) {
            plan = null;
            try {
                OriginLabelCodec.remove(persistentData);
            } catch (Throwable ignored) {
                // The entity is added as it is.
            }
            GraveCounts.count(GraveCounts.Reason.ORIGIN_NOT_WRITTEN);
        }
        boolean added = add.getAsBoolean();
        try {
            if (!added) {
                if (plan != null) GraveCounts.count(GraveCounts.Reason.ORIGIN_JOIN_CANCELLED);
            } else if (plan != null) {
                commit(plan);
            }
        } catch (Throwable ignored) {
            // The add's result is the mod's own and is returned unchanged.
        }
        return added;
    }

    /** The tentative tag and counts of one entity of {@code entityStack} spilled by a pending spill of {@code kind}. */
    static Plan plan(Frame frame, Kind kind, ItemStack reference, ItemStack entityStack,
            HolderLookup.Provider registries) {
        InventoryItemIdentity item = InventoryItemIdentity.of(entityStack);
        ResourceLocation itemId = OriginLabel.itemId(entityStack);
        String sha = OriginLabel.componentsSha256(entityStack, registries);
        int count = entityStack.getCount();
        switch (kind) {
            case COPY -> {
                return new Plan(frame, OriginLabel.allUnknown(itemId, sha, count), item, kind, reference, 0L, 0L,
                        count, null);
            }
            case OWN -> {
                long[] state = ((TakeFrame) frame).own.get(reference);
                long ownLeft = state == null ? 0L : Math.max(0L, state[0] - state[1]);
                long ownUnits = Math.min(count, ownLeft);
                long excess = count - ownUnits;
                if (excess == 0L) return new Plan(frame, null, item, kind, reference, ownUnits, 0L, 0L, null);
                OwnerPortions.Taken taken = frame.pile(item).take(excess);
                OriginLabelView label = OriginLabel.fromParts(itemId, sha, frame.graveId, taken.parts(),
                        Math.toIntExact(ownUnits));
                return new Plan(frame, label, item, kind, reference, ownUnits, excess, 0L, taken.remaining());
            }
            default -> {
                OwnerPortions.Taken taken = frame.pile(item).take(count);
                OriginLabelView label = OriginLabel.fromParts(itemId, sha, frame.graveId, taken.parts(), 0);
                return new Plan(frame, label, item, Kind.GRAVE, reference, 0L, count, 0L, taken.remaining());
            }
        }
    }

    /** Commits a plan whose entity joined. */
    static void commit(Plan plan) {
        Frame frame = plan.frame();
        if (plan.remaining() != null) frame.piles.put(plan.item(), plan.remaining());
        if (!(frame instanceof TakeFrame take)) return;
        if (plan.ownUnits() > 0L) {
            long[] state = take.own.get(plan.reference());
            if (state != null) state[1] += plan.ownUnits();
            take.ownSpilled.merge(plan.item(), plan.ownUnits(), Math::addExact);
        }
        if (plan.graveUnits() > 0L) take.spilledGrave.merge(plan.item(), plan.graveUnits(), Math::addExact);
        if (plan.copiedUnits() > 0L) take.uncertainSpilled.merge(plan.item(), plan.copiedUnits(), Math::addExact);
    }

    private static Pending pending(ItemStack stack, boolean passesCopy) {
        if (stack == null || CommandPresence.inCommand()) return null;
        Frame frame = FRAMES.get().peek();
        if (frame == null) return null;
        if (frame instanceof TakeFrame take) {
            if (passesCopy) {
                GraveCounts.count(GraveCounts.Reason.GRAVE_SPILL_COPIED);
                return new Pending(frame, Kind.COPY, stack);
            }
            return new Pending(frame, take.own.containsKey(stack) ? Kind.OWN : Kind.GRAVE, stack);
        }
        return new Pending(frame, Kind.GRAVE, stack);
    }

    private static <T> void pop(ArrayDeque<T> stack, T expected) {
        try {
            // Normally the top; a frame left by a broken nesting is removed wherever it is.
            if (stack.peek() == expected) {
                stack.pop();
            } else {
                stack.removeFirstOccurrence(expected);
            }
        } catch (Throwable ignored) {
            // Closing never interrupts the mod's own result or exception.
        }
    }
}
