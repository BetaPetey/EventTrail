package io.github.betapetey.eventtrail.events;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlayerProvenance;
import io.github.betapetey.eventtrail.events.platform.NeoForgePlacementAuditBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

public class EventTrailEventHandler {
    public static boolean isFakePlayer(Player player) {
        return NeoForgePlayerProvenance.isSynthetic(player);
    }

    /** GF-FAKEPLAYER-ID (B51): {@link NeoForgePlayerProvenance#isAdmittedPlayer}, for admission points only. */
    public static boolean isAdmittedPlayer(Player player) {
        return NeoForgePlayerProvenance.isAdmittedPlayer(player);
    }

    /** GF-FAKEPLAYER-ID (review-01 M3): {@link NeoForgePlayerProvenance#otherListedPlayer}; admits nothing. */
    public static ServerPlayer otherListedPlayer(ServerPlayer player) {
        return NeoForgePlayerProvenance.otherListedPlayer(player);
    }

    static boolean shouldTrackPlayer(boolean fakePlayer) {
        return !fakePlayer;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        // Multi-place is a subclass and has a dedicated single owner below.
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) return;
        NeoForgePlacementAuditBridge.observeSingle(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        NeoForgePlacementAuditBridge.observeMulti(
                event, EventTrailConfig.TRACK_MULTI_BLOCK_PLACE.get());
    }

}
