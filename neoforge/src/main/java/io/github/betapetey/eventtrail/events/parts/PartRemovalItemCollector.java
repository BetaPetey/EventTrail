package io.github.betapetey.eventtrail.events.parts;

import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts what one part-removal action hands over (design r2 section 3): item entities that joined the level during
 * the action, in the claimed block's own cell or exactly at the acting player's drop point, and the player's
 * inventory gains.
 *
 * <p>{@link #onEntityJoin} is glue only (registered at LOWEST priority without {@code receiveCanceled}, so a
 * cancelled join is never delivered); the rules are the plain-value methods below (seam b), which the unit tests
 * reach directly.</p>
 *
 * <p>Why the cell: removing a block updates its neighbours inside the same call, and a neighbour that loses its
 * support (a lever, torch, button, rail, sign) pops its item through {@code Block.popResource} in its own cell.
 * A mod's face pop toward the claimed cell would land inside it; no vanilla neighbour update is known to use one
 * (UNVERIFIED, caught by the stage-2 lever instance only if it occurs). A mod that spawns removed items outside the
 * cell and not at the drop point loses those rows: fewer rows, never wrong ones.</p>
 */
public final class PartRemovalItemCollector {
    /** Item entities one action may count; the next one voids the action (nothing written, one warning). */
    static final int MAX_ITEM_ENTITIES = 256;
    /** {@code Player.drop} spawns at the player's eye height minus this. */
    static final double DROP_EYE_OFFSET = 0.3;
    /** Per-axis tolerance of the drop-point match. */
    static final double DROP_POINT_TOLERANCE = 1.0E-4;

    /** The filter's answer for one joining item entity. */
    enum Verdict {
        COUNT,
        IGNORE,
        /** It would count, but the action already counted {@link PartRemovalItemCollector#MAX_ITEM_ENTITIES}: void it. */
        OVERFLOW
    }

    private PartRemovalItemCollector() {
    }

    /** NeoForge {@code EntityJoinLevelEvent} listener (LOWEST priority, cancelled joins not delivered). */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        try {
            if (!(event.getEntity() instanceof ItemEntity item)) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (level.getServer() == null || !level.getServer().isSameThread()) return;
            PartRemovalAuditBridge.offerItemEntity(level, item, event.loadedFromDisk());
        } catch (Throwable failure) {
            PartRemovalAuditBridge.collectorFailed(failure);
        }
    }

    /**
     * The collector filter (seam b). An item entity counts only if an action is open ({@code actionLevel} not
     * null), it was not loaded from disk, it joins the action's own level (identity), and it spawned inside the
     * claimed block's cell ({@code bx <= x < bx + 1} on each axis) or exactly at the acting player's drop point
     * (each axis within {@value #DROP_POINT_TOLERANCE}).
     */
    static Verdict admits(
            Object actionLevel,
            Object entityLevel,
            boolean loadedFromDisk,
            double x,
            double y,
            double z,
            BlockPos claimed,
            double dropX,
            double dropY,
            double dropZ,
            int countedSoFar) {
        if (actionLevel == null || claimed == null || loadedFromDisk || entityLevel != actionLevel) {
            return Verdict.IGNORE;
        }
        boolean inCell = inside(x, claimed.getX()) && inside(y, claimed.getY()) && inside(z, claimed.getZ());
        boolean atDropPoint = near(x, dropX) && near(y, dropY) && near(z, dropZ);
        if (!inCell && !atDropPoint) return Verdict.IGNORE;
        return countedSoFar >= MAX_ITEM_ENTITIES ? Verdict.OVERFLOW : Verdict.COUNT;
    }

    /**
     * The same entity object is already counted by this action (r3 review m-4): a mod that adds one entity twice in
     * one call is counted once. Compared by identity, not by {@code equals}.
     */
    static boolean alreadyCounted(List<?> counted, Object entity) {
        for (Object each : counted) {
            if (each == entity) return true;
        }
        return false;
    }

    /**
     * The finish check (seam b): a counted entity counts only if it is still in the level and the level still
     * returns this same entity for its id. An entity a later listener cancelled, one a mod removed and put into
     * the inventory, or one replaced under its id is then counted once, through the inventory gain, never twice.
     */
    static boolean presentAtFinish(boolean removed, Object entityById, Object entity) {
        return !removed && entity != null && entityById == entity;
    }

    /**
     * Inventory gains per exact item identity: the positive differences of the slot totals, each capped by the net
     * gain of its item type (the type's total after minus before, floor 0), walking the identities in slot order of
     * the after inventory. A stack whose data changed in place (a bundle or backpack that took an item in, a charge
     * or id component written) leaves its type's total unchanged and is never a gain (review-01 M1); a real
     * hand-over raises the type's total. The used hand's slot is left out unless its item type changed, so tool wear
     * and memory-card data are never a gain (design r2 2.4 item 4). Slots beyond the shorter list are ignored.
     *
     * <p>Residual: when a stack of one type changes its data in the same call that hands over another stack of that
     * same type, the count is right but the identity named may be the changed stack's (the first in slot order).</p>
     */
    static Map<InventoryItemIdentity, Long> inventoryGains(
            List<ItemStack> before, List<ItemStack> after, int usedSlot) {
        int slots = Math.min(before.size(), after.size());
        boolean handChanged = usedSlot >= 0 && usedSlot < slots
                && before.get(usedSlot).getItem() != after.get(usedSlot).getItem();
        Map<InventoryItemIdentity, Long> was = new LinkedHashMap<>();
        Map<InventoryItemIdentity, Long> now = new LinkedHashMap<>();
        for (int slot = 0; slot < slots; slot++) {
            if (slot == usedSlot && !handChanged) continue;
            add(was, before.get(slot));
            add(now, after.get(slot));
        }
        Map<String, Long> typeBudget = new LinkedHashMap<>();
        now.forEach((item, count) -> typeBudget.merge(item.registryId(), count, Long::sum));
        was.forEach((item, count) -> typeBudget.merge(item.registryId(), -count, Long::sum));
        Map<InventoryItemIdentity, Long> gains = new LinkedHashMap<>();
        now.forEach((item, count) -> {
            long gained = count - was.getOrDefault(item, 0L);
            long budget = typeBudget.getOrDefault(item.registryId(), 0L);
            long taken = Math.min(gained, budget);
            if (taken <= 0L) return;
            gains.put(item, taken);
            typeBudget.put(item.registryId(), budget - taken);
        });
        return gains;
    }

    /** Everything handed over: the inventory gains plus the stacks of the counted entities, merged per identity. */
    static Map<InventoryItemIdentity, Long> handedOver(
            Map<InventoryItemIdentity, Long> inventoryGains, List<ItemStack> entityStacks) {
        Map<InventoryItemIdentity, Long> merged = new LinkedHashMap<>(inventoryGains);
        for (ItemStack stack : entityStacks) add(merged, stack);
        return merged;
    }

    private static void add(Map<InventoryItemIdentity, Long> totals, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        totals.merge(InventoryItemIdentity.of(stack), (long) stack.getCount(), Long::sum);
    }

    private static boolean inside(double value, int cell) {
        return value >= cell && value < cell + 1.0;
    }

    private static boolean near(double value, double point) {
        return Math.abs(value - point) <= DROP_POINT_TOLERANCE;
    }
}
