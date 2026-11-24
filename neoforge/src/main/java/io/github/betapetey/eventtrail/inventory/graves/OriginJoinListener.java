package io.github.betapetey.eventtrail.inventory.graves;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The join listener of graves design r6, 1.6.4 and 3.1: an {@code EntityJoinLevelEvent} listener at LOWEST priority
 * that also receives cancelled joins, the same shape as the PartRemovalItemCollector listener. It hands an item entity
 * that joins on the server thread, not loaded from disk, to the innermost open {@link SpillWindow}; with no window open
 * it does nothing. It never changes or cancels the join.
 *
 * <p>Registration (lead-owned, EventTrailMod):
 * {@code NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, EntityJoinLevelEvent.class,
 * OriginJoinListener::onEntityJoin)}.</p>
 */
public final class OriginJoinListener {
    private OriginJoinListener() {
    }

    /** The listener (LOWEST priority, cancelled joins delivered). */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        try {
            if (!SpillWindow.open()) return;
            if (!(event.getEntity() instanceof ItemEntity item)) return;
            if (event.loadedFromDisk()) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (level.getServer() == null || !level.getServer().isSameThread()) return;
            SpillWindow.onJoin(item.getItem(), item.getPersistentData(), level.registryAccess(), event.isCanceled());
        } catch (Throwable ignored) {
            // A label is never worth a failed join: the entity joins as it is, unlabelled.
            GraveCounts.count(GraveCounts.Reason.ORIGIN_NOT_WRITTEN);
        }
    }
}
