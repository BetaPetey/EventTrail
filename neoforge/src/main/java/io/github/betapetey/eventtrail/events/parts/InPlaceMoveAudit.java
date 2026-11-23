package io.github.betapetey.eventtrail.events.parts;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The plain rules of the AE2 in-place hook family (parts readers design-03 section 5): identity totals, the
 * expected player change of each move, the mirror, the scope verdict and row building from a net change. No AE2
 * type is named here, so every rule is unit-tested with fake inventories (AE2 is not on the unit-test runtime).
 *
 * <p>Signs: a change map is "after minus before" per identity. A player-side gain is a take row (WITHDRAWAL), a
 * player-side loss a put row (DEPOSIT), with the net count. No row ever credits a player with something the
 * player-side inventory does not show: rows are built only from the observed player change, and only when it is
 * exactly what the machine change requires.</p>
 */
public final class InPlaceMoveAudit {
    /** A live player, but no family frame (or the hand fails the paste hooks' memory-card test). */
    public static final String NO_FRAME = "in-place-no-frame";
    /** Not a live ServerPlayer (fake players, deployers, automation): no dropped-capture count. */
    public static final String NOT_A_PLAYER = "in-place-not-a-player";
    /** The target's block position could not be found. */
    public static final String TARGET_UNKNOWN = "in-place-target-unknown";
    /** A creative paste with a change, or a crafting change whose mirror fails in creative. */
    public static final String CREATIVE = "in-place-creative";
    /** The observed player change is not what the machine change requires. */
    public static final String MISMATCH = "in-place-mismatch";
    /** A family window started inside another window of the same frame. */
    public static final String NESTED = "in-place-nested";
    /** The mod's own call threw. */
    public static final String ORIGINAL_THREW = "in-place-original-threw";

    private InPlaceMoveAudit() {
    }

    /** One family row: the exact identity, its net count (positive), the direction, the slot role and a label. */
    public record Row(
            InventoryItemIdentity item,
            long count,
            TransactionDirection direction,
            InventorySlotRole role,
            String label) {
        public Row {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(role, "role");
            if (count <= 0L) throw new IllegalArgumentException("count must be positive");
        }

        /** The signed player-side change this row stands for: a take (+), a put (-). */
        long signed() {
            return direction == TransactionDirection.WITHDRAWAL ? count : -count;
        }
    }

    /** Identity totals of the non-empty stacks. */
    public static Map<InventoryItemIdentity, Long> totals(Iterable<ItemStack> stacks) {
        return totals(stacks, stack -> false);
    }

    /** Identity totals of the non-empty stacks {@code skip} does not leave out. */
    public static Map<InventoryItemIdentity, Long> totals(Iterable<ItemStack> stacks, Predicate<ItemStack> skip) {
        Map<InventoryItemIdentity, Long> totals = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty() || skip.test(stack)) continue;
            totals.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Long::sum);
        }
        return totals;
    }

    /** After minus before, per identity; identities whose change is zero are left out. */
    public static Map<InventoryItemIdentity, Long> change(
            Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after) {
        Set<InventoryItemIdentity> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        Map<InventoryItemIdentity, Long> change = new LinkedHashMap<>();
        for (InventoryItemIdentity key : keys) {
            long delta = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            if (delta != 0L) change.put(key, delta);
        }
        return change;
    }

    /** The negated change (what the other side of a move must show). */
    public static Map<InventoryItemIdentity, Long> opposite(Map<InventoryItemIdentity, Long> change) {
        Map<InventoryItemIdentity, Long> opposite = new LinkedHashMap<>();
        for (Map.Entry<InventoryItemIdentity, Long> entry : change.entrySet()) {
            if (entry.getValue() != 0L) opposite.put(entry.getKey(), -entry.getValue());
        }
        return opposite;
    }

    /**
     * The player change a pattern paste requires (design-03 5.3): every old stack {@code returnsAsBlank} accepts
     * adds its count to {@code blank}; every other old stack comes back as itself; then the player gives one
     * {@code blank} per pattern now in the inventory (the sum of the after counts). AE2, AdvancedAE and the Quantum
     * Crafter accept the five pattern kinds; Applied Pneumatics accepts every stack.
     */
    public static Map<InventoryItemIdentity, Long> patternExpected(
            List<ItemStack> beforeSlots,
            List<ItemStack> afterSlots,
            Predicate<ItemStack> returnsAsBlank,
            ItemStack blank) {
        InventoryItemIdentity blankIdentity = InventoryItemIdentity.of(blank);
        Map<InventoryItemIdentity, Long> expected = new LinkedHashMap<>();
        for (ItemStack stack : beforeSlots) {
            if (stack == null || stack.isEmpty()) continue;
            InventoryItemIdentity identity = returnsAsBlank.test(stack) ? blankIdentity : InventoryItemIdentity.of(stack);
            expected.merge(identity, (long) stack.getCount(), Long::sum);
        }
        long imported = 0L;
        for (ItemStack stack : afterSlots) {
            if (stack != null && !stack.isEmpty()) imported += stack.getCount();
        }
        if (imported != 0L) expected.merge(blankIdentity, -imported, Long::sum);
        expected.values().removeIf(value -> value == 0L);
        return expected;
    }

    /**
     * The player change a crafting-unit change requires, by item type (design-03 5.4): +1 of the upgrade the
     * before block held (handed back), -1 of the upgrade the after block holds (taken from the hand). An empty
     * stack is a plain unit.
     */
    public static Map<Item, Long> craftingExpected(ItemStack removedBefore, ItemStack removedAfter) {
        Map<Item, Long> expected = new LinkedHashMap<>();
        if (removedBefore != null && !removedBefore.isEmpty()) expected.merge(removedBefore.getItem(), 1L, Long::sum);
        if (removedAfter != null && !removedAfter.isEmpty()) expected.merge(removedAfter.getItem(), -1L, Long::sum);
        expected.values().removeIf(value -> value == 0L);
        return expected;
    }

    /** For every identity, the observed change equals the expected change (zeros ignored). */
    public static boolean mirrors(Map<InventoryItemIdentity, Long> expected, Map<InventoryItemIdentity, Long> observed) {
        return nonZero(expected).equals(nonZero(observed));
    }

    /**
     * As {@link #mirrors}, with the observed change summed per item type, and at most one changed identity per item
     * type (review-02 NIT-2): a +A/-B swap of two stacks of one type with different components never nets to a
     * pass.
     */
    public static boolean mirrorsByType(Map<Item, Long> expected, Map<InventoryItemIdentity, Long> observed) {
        Map<Item, Long> byType = new LinkedHashMap<>();
        Set<Item> seen = new LinkedHashSet<>();
        for (Map.Entry<InventoryItemIdentity, Long> entry : observed.entrySet()) {
            if (entry.getValue() == null || entry.getValue() == 0L) continue;
            Item type = entry.getKey().stackWithCount(1).getItem();
            if (!seen.add(type)) return false;
            byType.merge(type, entry.getValue(), Long::sum);
        }
        byType.values().removeIf(value -> value == 0L);
        Map<Item, Long> wanted = new LinkedHashMap<>(expected);
        wanted.values().removeIf(value -> value == 0L);
        return wanted.equals(byType);
    }

    /**
     * The scope verdict of a hook whose machine side changed (design-03 5.1), in order: not a live player, the
     * target position unknown, no frame (or the hand fails the paste hooks' memory-card test), creative (paste
     * hooks only; pass false otherwise). Null when every rule holds.
     */
    public static String refusal(boolean live, boolean positionKnown, boolean frame, boolean handOk, boolean creative) {
        if (!live) return NOT_A_PLAYER;
        if (!positionKnown) return TARGET_UNKNOWN;
        if (!frame || !handOk) return NO_FRAME;
        if (creative) return CREATIVE;
        return null;
    }

    /** Every counted line but {@link #NOT_A_PLAYER} also counts one dropped capture (a player's action was lost). */
    public static boolean countsDropped(String reason) {
        return !NOT_A_PLAYER.equals(reason);
    }

    /**
     * One counted line (design-03 5.1): a de-duplicated warning and the key's skipped-action count, plus one
     * dropped capture when a live player's action was lost; once per action when a frame is open.
     */
    static void count(AdapterKey key, String site, String reason, PartRemovalAuditBridge.InPlaceFrame frame) {
        if (frame != null && !frame.markReported()) return;
        AdapterFailSafe.invalid(key, site, reason);
        if (countsDropped(reason)) EventTrailManager.getInstance().countDroppedCaptures(1);
    }

    /**
     * The mod's own call threw (design-03 5.1, review-02 MAJOR-1). A live player's action is counted, with a frame
     * (once per action) or without one (one line and one dropped capture each time: nothing else in EventTrail
     * counts it, because the guard sends no report for the mod's own throw). Automation's throw is counted as the
     * same action without a throw is (re-check 01 MINOR-1): one not-a-player line, no dropped capture, never a row.
     */
    static void originalThrew(AdapterKey key, String site, boolean live, PartRemovalAuditBridge.InPlaceFrame frame) {
        if (live) count(key, site, ORIGINAL_THREW, frame);
        else count(key, site, NOT_A_PLAYER, null);
    }

    /**
     * The decision of a hook body once the mod's call returned (design-03 5.1; review-02 MINOR-4: on plain values,
     * so that each outcome is tested). In order: no machine change, nothing; a change through a handling hook
     * marks the frame handled (a REPLACED ending then adds no block-replaced count); a scope refusal is counted; a
     * frame a counted line already covers writes nothing; a failed mirror is counted (creative for a handling hook
     * of a creative player, else mismatch); otherwise the frame takes the rows of the observed player change.
     * {@code observed} and {@code mirrors} are asked only when every rule before them holds.
     *
     * @param paste    a memory-card paste hook: the hand must hold a memory card and creative is refused
     * @param handles  a hook that changes the block itself (5.4)
     * @param creative the live player is in creative mode (false when not a live player)
     * @return the reason of the line this call asked for (counted unless a line already covers the frame), or null
     */
    static String afterCall(
            AdapterKey key,
            String site,
            boolean changed,
            boolean paste,
            boolean handles,
            boolean live,
            boolean creative,
            boolean positionKnown,
            PartRemovalAuditBridge.InPlaceFrame frame,
            Predicate<Item> memoryCard,
            Supplier<Map<InventoryItemIdentity, Long>> observed,
            Predicate<Map<InventoryItemIdentity, Long>> mirrors,
            InventorySlotRole role,
            String label) {
        if (!changed) return null;
        if (handles && frame != null) frame.markHandled();
        boolean handOk = !paste || frame != null && memoryCard.test(frame.hand());
        String refusal = refusal(live, positionKnown, frame != null, handOk, paste && creative);
        if (refusal != null) {
            count(key, site, refusal, frame);
            return refusal;
        }
        if (frame.dropped()) return null;
        Map<InventoryItemIdentity, Long> change = observed.get();
        if (!mirrors.test(change)) {
            String failed = handles && creative ? CREATIVE : MISMATCH;
            count(key, site, failed, frame);
            return failed;
        }
        frame.report(rows(change, role, label));
        return null;
    }

    /**
     * Design-03 5.1 N-2 (review-02 MAJOR-2), the leftover of an action armed with a memory card: the player's whole
     * change since open ({@code now} minus {@code atOpen}; both already leave out memory cards and Network Tools,
     * and {@code now} adds the item entities collected since open) less the signed change of every reported row,
     * the rows of {@code skip}'s identities (memory cards) left out as the totals leave them out. Empty when the
     * family's reports cover the player's whole change.
     */
    public static Map<InventoryItemIdentity, Long> leftover(
            Map<InventoryItemIdentity, Long> atOpen,
            Map<InventoryItemIdentity, Long> now,
            List<Row> reported,
            Predicate<InventoryItemIdentity> skip) {
        Map<InventoryItemIdentity, Long> leftover = change(atOpen, now);
        for (Row row : reported) {
            if (skip.test(row.item())) continue;
            leftover.merge(row.item(), -row.signed(), Long::sum);
        }
        leftover.values().removeIf(value -> value == 0L);
        return leftover;
    }

    /**
     * A cable-bus part target's label (review m-7, review-02 MINOR-2): the part item's name through the row
     * writer's item-name call, with the part's custom name when its {@code exportSettings} export has one, as the
     * AE2 parts reader names a renamed part. Only {@code CUSTOM_NAME} of the export is used.
     */
    public static String partLabel(ItemLike partItem, DataComponentMap exported) {
        ItemStack stack = new ItemStack(partItem);
        Component customName = exported == null ? null : exported.get(DataComponents.CUSTOM_NAME);
        if (customName != null) stack.set(DataComponents.CUSTOM_NAME, customName);
        return DisplayNameResolver.stackDisplayName(stack);
    }

    /** The rows of an observed player change: a gain is a take row, a loss a put row, with the net count. */
    public static List<Row> rows(Map<InventoryItemIdentity, Long> observed, InventorySlotRole role, String label) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<InventoryItemIdentity, Long> entry : observed.entrySet()) {
            long delta = entry.getValue();
            if (delta == 0L) continue;
            rows.add(new Row(entry.getKey(), Math.abs(delta),
                    delta > 0L ? TransactionDirection.WITHDRAWAL : TransactionDirection.DEPOSIT, role, label));
        }
        return rows;
    }

    /**
     * One row per exact identity, slot role and label, with its net count, in first-seen order: the rows of
     * several windows of one action are written once, never doubled. A net of zero writes no row.
     */
    public static List<Row> net(List<Row> rows) {
        Map<List<Object>, Long> sums = new LinkedHashMap<>();
        Map<List<Object>, Row> first = new LinkedHashMap<>();
        for (Row row : rows) {
            List<Object> key = new ArrayList<>(3);
            key.add(row.item());
            key.add(row.role());
            key.add(row.label() == null ? NULL_LABEL : row.label());
            sums.merge(key, row.signed(), Long::sum);
            first.putIfAbsent(key, row);
        }
        List<Row> net = new ArrayList<>();
        for (Map.Entry<List<Object>, Long> entry : sums.entrySet()) {
            long delta = entry.getValue();
            if (delta == 0L) continue;
            Row seen = first.get(entry.getKey());
            net.add(new Row(seen.item(), Math.abs(delta),
                    delta > 0L ? TransactionDirection.WITHDRAWAL : TransactionDirection.DEPOSIT, seen.role(),
                    seen.label()));
        }
        return net;
    }

    private static final Object NULL_LABEL = new Object();

    private static Map<InventoryItemIdentity, Long> nonZero(Map<InventoryItemIdentity, Long> change) {
        Map<InventoryItemIdentity, Long> kept = new LinkedHashMap<>();
        for (Map.Entry<InventoryItemIdentity, Long> entry : change.entrySet()) {
            if (entry.getValue() != null && entry.getValue() != 0L) kept.put(entry.getKey(), entry.getValue());
        }
        return kept;
    }
}
